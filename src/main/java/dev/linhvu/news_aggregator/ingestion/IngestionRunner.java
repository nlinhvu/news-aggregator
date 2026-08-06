package dev.linhvu.news_aggregator.ingestion;

import java.time.Instant;
import java.util.List;

import dev.linhvu.news_aggregator.ingestion.events.ArticleDiscovered;
import dev.linhvu.news_aggregator.platform.IngestionRunMetrics;
import dev.linhvu.news_aggregator.sources.SourceCatalog;
import dev.linhvu.news_aggregator.sources.api.SourceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
class IngestionRunner {

	private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

	private final SourceCatalog sources;
	private final FeedFetcher fetcher;
	private final FeedParser parser;
	private final ApplicationEventPublisher events;
	private final IngestionRunMetrics metrics;

	IngestionRunner(SourceCatalog sources, FeedFetcher fetcher, FeedParser parser,
			ApplicationEventPublisher events, IngestionRunMetrics metrics) {
		this.sources = sources;
		this.fetcher = fetcher;
		this.parser = parser;
		this.events = events;
		this.metrics = metrics;
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

		int discovered = 0;
		int failed = 0;

		// Slice 2: tuần tự. Song song thêm ở Task 17.
		for (SourceView source : enabled) {
			try {
				discovered += ingestOne(source);
			}
			catch (Exception e) {
				failed++;
				log.warn("nguồn {} thất bại: {}", source.sourceId(), e.toString());
			}
		}

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
