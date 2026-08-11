package dev.linhvu.news_aggregator.ingestion;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.linhvu.news_aggregator.ingestion.events.ArticleDiscovered;
import dev.linhvu.news_aggregator.platform.IngestionRunMetrics;
import dev.linhvu.news_aggregator.sources.SourceCatalog;
import dev.linhvu.news_aggregator.sources.api.SourceView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IngestionRunnerTest {

	private final SourceCatalog catalog = mock(SourceCatalog.class);
	private final FeedFetcher fetcher = mock(FeedFetcher.class);
	private final FeedParser parser = mock(FeedParser.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final IngestionRunMetrics metrics = new IngestionRunMetrics();

	private final IngestionRunner runner =
			new IngestionRunner(catalog, fetcher, parser, events, metrics, 8);

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void batLog() {
		logs.start();
		((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(IngestionRunner.class)).addAppender(logs);
	}

	@AfterEach
	void thaLog() {
		((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(IngestionRunner.class)).detachAppender(logs);
		logs.stop();
	}

	private List<String> logEvents() {
		return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Test
	void mot_nguon_hong_khong_giet_luot_chay() {
		given(catalog.enabledSources()).willReturn(List.of(source("a"), source("b")));
		given(fetcher.fetch(source("a"))).willThrow(new RuntimeException("hỏng"));
		given(fetcher.fetch(source("b"))).willReturn(body());
		given(parser.parse(any())).willReturn(List.of(
				new ParsedItem("T", "https://b.test/1", "Tue, 04 Aug 2026 10:00:00 GMT",
						"Đoạn trích.")));

		IngestResult result = runner.run();

		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.discovered()).isEqualTo(1);
	}

	/**
	 * Ranh giới duy nhất khiến DLQ có việc. Nếu ngưỡng này sai theo hướng lỏng,
	 * DLQ không bao giờ nhận gì và một lượt chạy hỏng hoàn toàn trông như thành
	 * công.
	 */
	@Test
	void khong_nguon_nao_chay_duoc_thi_luot_chay_that_bai() {
		given(catalog.enabledSources()).willReturn(List.of(source("a"), source("b")));
		given(fetcher.fetch(any())).willThrow(new RuntimeException("hỏng"));

		assertThatThrownBy(runner::run).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void bang_sources_rong_thi_that_bai() {
		given(catalog.enabledSources()).willReturn(List.of());

		assertThatThrownBy(runner::run)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("sourcesSync");
	}

	/**
	 * Lambda dùng lại execution environment, nên bean singleton mang theo số đếm
	 * của lượt TRƯỚC nếu không reset. Triệu chứng là `added` tăng dần vô hạn qua
	 * các lượt — trông như ingestion đang chạy rất tốt.
	 */
	@Test
	void reset_metrics_dau_moi_luot() {
		metrics.countAdded();
		metrics.countAdded();
		given(catalog.enabledSources()).willReturn(List.of(source("a")));
		given(fetcher.fetch(any())).willReturn(body());
		given(parser.parse(any())).willReturn(List.of());

		assertThat(runner.run().added()).isZero();
	}

	/**
	 * `excerpt` phải đi HẾT đường từ item đã parse tới event. `publish` là chỗ
	 * duy nhất nối hai đầu, và không có test nào khác nhìn vào thứ được publish
	 * — truyền nhầm `null` ở đó thì mọi test còn lại vẫn xanh, còn triệu chứng
	 * thật là không bài nào có tóm tắt.
	 */
	@Test
	void excerpt_di_tu_item_toi_event() {
		given(catalog.enabledSources()).willReturn(List.of(source("a")));
		given(fetcher.fetch(any())).willReturn(body());
		given(parser.parse(any())).willReturn(List.of(new ParsedItem("T",
				"https://b.test/1", "Tue, 04 Aug 2026 10:00:00 GMT", "Đoạn trích thật.")));

		runner.run();

		ArgumentCaptor<ArticleDiscovered> captor =
				ArgumentCaptor.forClass(ArticleDiscovered.class);
		verify(events).publishEvent(captor.capture());
		assertThat(captor.getValue().excerpt()).isEqualTo("Đoạn trích thật.");
	}

	/**
	 * Khẳng định các nguồn THẬT SỰ chạy chồng lên nhau. Không có test này thì
	 * xoá executor để quay về vòng `for` tuần tự vẫn xanh nguyên mọi test khác —
	 * đúng kiểu hỏng mà Task 16 đã gặp một lần: tính năng tự vô hiệu hoá, không
	 * một dòng lỗi.
	 *
	 * `latch` ba nhịp là bằng chứng không thể lách: mỗi nguồn đếm xuống rồi CHỜ
	 * hai nguồn kia. Chạy tuần tự thì nguồn đầu tiên chờ mãi và hết giờ.
	 */
	@Test
	void fetch_chay_song_song() {
		CountDownLatch gap = new CountDownLatch(3);
		AtomicInteger gapDu = new AtomicInteger();
		given(catalog.enabledSources())
				.willReturn(List.of(source("a"), source("b"), source("c")));
		given(fetcher.fetch(any())).willAnswer(inv -> {
			gap.countDown();
			if (gap.await(5, TimeUnit.SECONDS)) {
				gapDu.incrementAndGet();
			}
			return body();
		});
		given(parser.parse(any())).willReturn(List.of());

		IngestResult result = runner.run();

		assertThat(gapDu).hasValue(3);
		assertThat(result.failed()).isZero();
	}

	/**
	 * Song song ở đây là LỊCH SỰ chứ không thô lỗ, và `Semaphore` là thứ duy
	 * nhất giữ lời hứa đó khi danh sách nguồn lớn dần tới trần ~30 của master §2.
	 * Bỏ nó đi thì 30 nguồn bắn cùng lúc — mà `newVirtualThreadPerTaskExecutor`
	 * không hề chặn, nó vui vẻ dựng 30 thread.
	 *
	 * Đo đỉnh số lượt chồng nhau với trần 2 và 6 nguồn: phải CHẠM 2 (chứng minh
	 * có song song) và KHÔNG VƯỢT 2 (chứng minh có trần).
	 */
	@Test
	void semaphore_chan_tren_so_luot_song_song() {
		IngestionRunner tran2 =
				new IngestionRunner(catalog, fetcher, parser, events, metrics, 2);
		AtomicInteger dangChay = new AtomicInteger();
		AtomicInteger dinh = new AtomicInteger();
		given(catalog.enabledSources()).willReturn(List.of(source("a"), source("b"),
				source("c"), source("d"), source("e"), source("f")));
		given(fetcher.fetch(any())).willAnswer(inv -> {
			dinh.accumulateAndGet(dangChay.incrementAndGet(), Math::max);
			Thread.sleep(Duration.ofMillis(100));
			dangChay.decrementAndGet();
			return body();
		});
		given(parser.parse(any())).willReturn(List.of());

		tran2.run();

		assertThat(dinh).hasValue(2);
	}

	/**
	 * Một nguồn treo lâu không được kéo theo cả lượt chạy vào chỗ chết. Sau khi
	 * chuyển sang executor, `close()` CHỜ mọi task xong — nên nếu một task nuốt
	 * `InterruptedException` hoặc giữ permit mà không trả, lượt chạy đứng im cho
	 * tới khi Lambda hết giờ. Triệu chứng là timeout chứ không phải lỗi, và nó
	 * chỉ lộ ra ở production.
	 */
	@Test
	void luot_cham_van_ket_thuc_va_tra_permit() {
		given(catalog.enabledSources())
				.willReturn(List.of(source("a"), source("b"), source("c")));
		given(fetcher.fetch(source("a"))).willAnswer(inv -> {
			Thread.sleep(Duration.ofMillis(200));
			return body();
		});
		given(fetcher.fetch(source("b"))).willThrow(new RuntimeException("hỏng"));
		given(fetcher.fetch(source("c"))).willReturn(body());
		given(parser.parse(any())).willReturn(List.of());

		assertThatCode(() -> assertThat(runner.run().failed()).isEqualTo(1))
				.doesNotThrowAnyException();
	}

	/**
	 * Ghim TIỀN TỐ `ingestion run xong:` vì `ObservabilityStack` dựng một CloudWatch
	 * metric filter khớp chính chuỗi đó, và heartbeat alarm của prod đọc metric ấy.
	 *
	 * Hai repo không thấy nhau: đổi dòng log này ở đây thì metric filter bên
	 * `infra/` ngừng khớp, heartbeat mất datapoint, và vì alarm dùng
	 * `treatMissingData: BREACHING` nó sẽ nổ — một BÁO ĐỘNG GIẢ, đúng loại làm
	 * người vận hành mất niềm tin. Không có test này thì triệu chứng xuất hiện
	 * nhiều giờ sau, ở một repo khác.
	 *
	 * Ghim tiền tố chứ không ghim cả câu: thêm một trường số vào cuối là thay đổi
	 * lành tính và không nên làm test đỏ.
	 *
	 * Test này XANH ngay từ lúc viết — nó ghim một hợp đồng đang đúng từ Phase 2,
	 * không dẫn dắt một thay đổi. Loại test đó chỉ có giá trị nếu chứng minh được
	 * nó thật sự canh, nên nó đi kèm một bước mutation trong plan Task 6 Step 3.
	 */
	@Test
	void log_ket_thuc_luot_giu_dung_tien_to_ma_metric_filter_can() {
		given(catalog.enabledSources()).willReturn(List.of(source("a")));
		given(fetcher.fetch(any())).willReturn(body());
		given(parser.parse(any())).willReturn(List.of(new ParsedItem("T",
				"https://a.test/1", "Tue, 04 Aug 2026 10:00:00 GMT", "Đoạn trích.")));

		runner.run();

		assertThat(logEvents())
				.as("metric filter của heartbeat khớp tiền tố này — xem "
						+ "ObservabilityStack.ingestHeartbeat")
				.anyMatch(m -> m.startsWith("ingestion run xong:"));
	}

	private static FetchOutcome.Body body() {
		return new FetchOutcome.Body("<rss/>".getBytes(), null, null);
	}

	private static SourceView source(String id) {
		return new SourceView(id, "Nguồn " + id, "https://" + id + ".test/feed",
				null, null);
	}
}
