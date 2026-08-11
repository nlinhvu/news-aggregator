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
	void nhan_dung_job_cua_minh() {
		assertThat(handler.supports(Map.of("job", "summarize-sweep"))).isTrue();
	}

	@Test
	void khong_nhan_payload_cua_nguon_khac() {
		assertThat(handler.supports(Map.of("job", "ingest-feeds"))).isFalse();
		assertThat(handler.supports(Map.of("Records", List.of(
				Map.of("eventSource", "aws:sqs"))))).isFalse();
		assertThat(handler.supports(Map.of())).isFalse();
	}

	@Test
	void day_bai_tim_duoc_qua_cong_queue() {
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
	void hoi_catalog_dung_han_muc_moi_luot() {
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
	void dem_phan_bi_han_muc_chan() {
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
	void sinceHours_ghi_de_cua_so_mac_dinh() {
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
	void sinceHours_nhan_ca_Long_lan_Integer() {
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
	void reset_so_dem_dau_moi_luot() {
		metrics.countEnqueued();
		metrics.countEnqueued();
		given(catalog.findSummarizable(any(Duration.class), anyInt()))
				.willReturn(List.of());

		handler.handle(Map.of("job", "summarize-sweep"));

		assertThat(metrics.enqueued()).isZero();
	}
}
