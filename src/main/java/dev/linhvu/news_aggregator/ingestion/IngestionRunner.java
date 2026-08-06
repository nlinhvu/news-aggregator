package dev.linhvu.news_aggregator.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import dev.linhvu.news_aggregator.ingestion.events.ArticleDiscovered;
import dev.linhvu.news_aggregator.platform.IngestionRunMetrics;
import dev.linhvu.news_aggregator.sources.SourceCatalog;
import dev.linhvu.news_aggregator.sources.api.SourceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
class IngestionRunner {

	private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

	/** Nguồn thất bại. `ingestOne` chỉ trả về số đếm ≥ 0 nên không đụng nhau. */
	private static final int THAT_BAI = -1;

	private final SourceCatalog sources;
	private final FeedFetcher fetcher;
	private final FeedParser parser;
	private final ApplicationEventPublisher events;
	private final IngestionRunMetrics metrics;
	private final int maxConcurrency;

	IngestionRunner(SourceCatalog sources, FeedFetcher fetcher, FeedParser parser,
			ApplicationEventPublisher events, IngestionRunMetrics metrics,
			@Value("${news.ingestion.max-concurrency}") int maxConcurrency) {
		this.sources = sources;
		this.fetcher = fetcher;
		this.parser = parser;
		this.events = events;
		this.metrics = metrics;
		this.maxConcurrency = maxConcurrency;
	}

	IngestResult run() {
		// BẮT BUỘC. Lambda dùng lại execution environment giữa các lượt invoke,
		// nên bean singleton này mang theo số đếm của lượt TRƯỚC nếu không reset.
		metrics.reset();

		List<SourceView> enabled = sources.enabledSources();
		if (enabled.isEmpty()) {
			throw new IllegalStateException(
					"bảng sources rỗng — đã chạy ./gradlew sourcesSync chưa?");
		}

		List<Integer> perSource = fetchAll(enabled);

		int discovered = perSource.stream()
				.filter(n -> n != THAT_BAI)
				.mapToInt(Integer::intValue)
				.sum();
		int failed = (int) perSource.stream().filter(n -> n == THAT_BAI).count();

		// Ranh giới phải rõ, nếu không DLQ hoặc không bao giờ nhận gì, hoặc nhận
		// mọi thứ: một nguồn hỏng là chuyện bình thường; KHÔNG nguồn nào chạy
		// được mới là lượt chạy hỏng.
		if (failed == enabled.size()) {
			throw new IllegalStateException(
					"cả %d nguồn đều thất bại".formatted(failed));
		}

		IngestResult result = new IngestResult(discovered, metrics.added(), failed);
		log.info("ingestion run xong: discovered={} added={} failed={}",
				result.discovered(), result.added(), result.failed());
		return result;
	}

	/**
	 * Virtual thread, KHÔNG `StructuredTaskScope` — cái sau vẫn là preview ở JDK
	 * 25 (JEP 505) và cần `--enable-preview` trên image do buildpack dựng.
	 *
	 * Song song ở đây là LỊCH SỰ chứ không thô lỗ: etiquette cấm bắn nhiều
	 * request vào CÙNG MỘT host, còn đây là 4 host khác nhau, mỗi host đúng một
	 * request mỗi lượt. `Semaphore` giữ lời hứa đó khi danh sách nguồn lớn dần
	 * tới trần ~30 của master §2 — `newVirtualThreadPerTaskExecutor` tự nó KHÔNG
	 * chặn gì cả, nó vui vẻ dựng 30 thread cùng lúc.
	 */
	private List<Integer> fetchAll(List<SourceView> enabled) {
		Semaphore permits = new Semaphore(maxConcurrency);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Integer>> futures = enabled.stream()
					.map(source -> executor.submit(() -> ingestOneIsolated(source, permits)))
					.toList();
			return futures.stream().map(IngestionRunner::join).toList();
		}
	}

	/**
	 * Cô lập mức NGUỒN. Bắt exception NGAY TẠI ĐÂY chứ không ở `Future.get()`:
	 * chỉ ở đây mới còn `source` trong tay để nói rõ nguồn NÀO hỏng, và dòng log
	 * đó là thứ duy nhất chỉ ra thủ phạm khi một nguồn im lặng chết.
	 */
	private int ingestOneIsolated(SourceView source, Semaphore permits)
			throws InterruptedException {
		permits.acquire();
		try {
			return ingestOne(source);
		}
		catch (Exception e) {
			log.warn("nguồn {} thất bại: {}", source.sourceId(), e.toString());
			return THAT_BAI;
		}
		finally {
			// TRONG finally: một permit không trả sẽ treo lượt chạy tới khi
			// Lambda hết giờ, và triệu chứng là timeout chứ không phải lỗi.
			permits.release();
		}
	}

	private static int join(Future<Integer> future) {
		try {
			return future.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("lượt ingestion bị ngắt", e);
		}
		catch (ExecutionException e) {
			// Lỗi của nguồn đã bị nuốt ở `ingestOneIsolated`, nên tới được đây
			// nghĩa là chính task hỏng (acquire bị ngắt). Vẫn đếm là một nguồn
			// thất bại chứ không để nó biến mất khỏi `failed`.
			log.warn("task của một nguồn hỏng bất thường: {}", e.getCause().toString());
			return THAT_BAI;
		}
	}

	private int ingestOne(SourceView source) {
		FetchOutcome outcome = fetcher.fetch(source);

		if (outcome instanceof FetchOutcome.NotModified notModified) {
			sources.recordFetch(source.sourceId(), notModified.etag(),
					notModified.lastModified(), Instant.now().toString());
			return 0;
		}

		FetchOutcome.Body body = (FetchOutcome.Body) outcome;
		int discovered = 0;

		for (ParsedItem item : parser.parse(body.content())) {
			// Cô lập mức ITEM: một item hỏng không giết cả nguồn. Event là đồng
			// bộ nên exception trong listener của `catalog` nổi ngược lên đây.
			try {
				publish(source, item);
				discovered++;
			}
			catch (Exception e) {
				log.warn("bỏ item của {} ({}): {}",
						source.sourceId(), item.link(), e.toString());
			}
		}

		sources.recordFetch(source.sourceId(), body.etag(), body.lastModified(),
				Instant.now().toString());
		return discovered;
	}

	private void publish(SourceView source, ParsedItem item) {
		if (item.link() == null || item.link().isBlank()) {
			throw new IllegalArgumentException("item không có link");
		}
		// Ngày không parse được → BỎ item, không lấy thời điểm fetch thay thế.
		// Ngày sai là sai VĨNH VIỄN vì dedupe chặn ghi lại; bỏ qua thì lượt sau
		// nhặt lại được, miễn bài còn trong ~20 bài mới nhất của feed.
		String publishedAt = FeedDates.parse(item.publishedAt())
				.orElseThrow(() -> new IllegalArgumentException(
						"ngày không parse được: " + item.publishedAt()));

		String canonical = CanonicalUrl.normalise(item.link());

		events.publishEvent(new ArticleDiscovered(source.sourceId(), source.name(),
				canonical, item.title(), publishedAt));
	}
}
