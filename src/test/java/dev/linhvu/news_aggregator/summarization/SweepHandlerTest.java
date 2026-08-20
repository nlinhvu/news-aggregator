package dev.linhvu.news_aggregator.summarization;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SweepHandlerTest {

	private final ArticleCatalog catalog = mock(ArticleCatalog.class);

	private final SummarizationQueue queue = mock(SummarizationQueue.class);

	private final SummarizationRunMetrics metrics = new SummarizationRunMetrics();

	private final SweepHandler handler =
			new SweepHandler(catalog, queue, metrics, Duration.ofHours(48), 25);

	private static SummarizableArticle article(String id) {
		return new SummarizableArticle(id, "Tiêu đề " + id, "Đoạn trích " + id);
	}

	@Test
	void accepts_only_its_own_job() {
		assertThat(handler.supports(Map.of("job", "summarize-sweep"))).isTrue();
	}

	@Test
	void does_not_accept_a_payload_from_another_producer() {
		assertThat(handler.supports(Map.of("job", "ingest-feeds"))).isFalse();
		assertThat(handler.supports(Map.of("Records", List.of(
				Map.of("eventSource", "aws:sqs"))))).isFalse();
		assertThat(handler.supports(Map.of())).isFalse();
	}

	@Test
	void pushes_the_articles_it_finds_through_the_queue_gate() {
		given(catalog.findSummarizable(any(Duration.class), anyInt()))
				.willReturn(List.of(article("a1"), article("a2")));
		given(queue.enqueueAll(List.of("a1", "a2"))).willReturn(2);

		assertThat(handler.handle(Map.of("job", "summarize-sweep")))
				.isEqualTo(Map.of("scanned", 2, "enqueued", 2, "skipped", 0));
	}

	/**
	 * Hạn mức đọc từ config chứ không viết cứng, và nó phải tới được `catalog`:
	 * query lấy về nhiều hơn `maxPerRun` thì phần dư chỉ để `SummarizationQueue`
	 * chặn lại — tức ta đã trả tiền đọc cho những item không bao giờ dùng.
	 */
	@Test
	void asks_the_catalog_for_exactly_the_per_run_quota() {
		given(catalog.findSummarizable(any(Duration.class), anyInt()))
				.willReturn(List.of());

		handler.handle(Map.of("job", "summarize-sweep"));

		verify(catalog).findSummarizable(Duration.ofHours(48), 25);
	}

	/**
	 * `skipped` = tìm thấy nhưng KHÔNG gửi được, và đó gần như luôn là do chạm
	 * hạn mức mỗi lượt. Con số này phải hiện trong response chứ không chỉ trong
	 * log: nó là cách người vận hành biết còn tồn đọng mà không phải đọc log.
	 */
	@Test
	void counts_what_the_quota_blocked() {
		given(catalog.findSummarizable(any(Duration.class), anyInt()))
				.willReturn(List.of(article("a1"), article("a2"), article("a3")));
		given(queue.enqueueAll(any())).willReturn(1);

		assertThat(handler.handle(Map.of("job", "summarize-sweep")))
				.isEqualTo(Map.of("scanned", 3, "enqueued", 1, "skipped", 2));
	}

	/**
	 * `sinceHours` mở rộng cửa sổ, dùng khi flag tắt lâu hơn 48 giờ. Đây là
	 * cách chữa DUY NHẤT cho edge case đầu bảng của walkthrough slice 3 — và
	 * nó phải chạy được bằng một lần `aws lambda invoke`, không phải deploy.
	 */
	@Test
	void sinceHours_overrides_the_default_window() {
		given(catalog.findSummarizable(any(Duration.class), anyInt()))
				.willReturn(List.of());

		handler.handle(Map.of("job", "summarize-sweep", "sinceHours", 720));

		ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
		verify(catalog).findSummarizable(captor.capture(), anyInt());
		assertThat(captor.getValue()).isEqualTo(Duration.ofHours(720));
	}

	/**
	 * Payload đi qua Jackson trước khi tới đây, và Jackson dựng `Integer` cho số
	 * nhỏ nhưng `Long` cho số lớn — `instanceof Integer` sẽ nuốt im lặng
	 * `sinceHours` lớn và lặng lẽ dùng cửa sổ mặc định. Chính là ca dùng thật:
	 * người ta chỉ đụng tới `sinceHours` khi cần cửa sổ RỘNG.
	 */
	@Test
	void sinceHours_accepts_both_Long_and_Integer() {
		given(catalog.findSummarizable(any(Duration.class), anyInt()))
				.willReturn(List.of());

		handler.handle(Map.of("job", "summarize-sweep", "sinceHours", 720L));

		verify(catalog).findSummarizable(Duration.ofHours(720), 25);
	}

	/**
	 * Lambda dùng lại execution environment, nên bean metrics singleton mang
	 * theo số đếm của lượt TRƯỚC nếu không reset. Phase 2 đã trả giá này với
	 * `IngestionRunMetrics`; hạn mức ở đây phụ thuộc vào số đếm nên quên reset
	 * nghĩa là lượt thứ hai trở đi enqueue được ÍT dần rồi về 0.
	 */
	@Test
	void resets_the_counters_at_the_start_of_every_run() {
		metrics.countEnqueued();
		metrics.countEnqueued();
		given(catalog.findSummarizable(any(Duration.class), anyInt()))
				.willReturn(List.of());

		handler.handle(Map.of("job", "summarize-sweep"));

		assertThat(metrics.enqueued()).isZero();
	}
}
