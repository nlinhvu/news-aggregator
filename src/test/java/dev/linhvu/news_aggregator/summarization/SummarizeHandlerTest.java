package dev.linhvu.news_aggregator.summarization;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
import dev.linhvu.news_aggregator.summarization.events.ArticleSummarized;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SummarizeHandlerTest {

	private final ArticleCatalog catalog = mock(ArticleCatalog.class);

	private final Summarizer summarizer = mock(Summarizer.class);

	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final SummarizeHandler handler =
			new SummarizeHandler(catalog, summarizer, events, 3);

	private static Map<String, Object> sqsPayload(String... articleIds) {
		return Map.of("Records", Arrays.stream(articleIds)
				.map(id -> Map.<String, Object>of(
						"messageId", "msg-" + id,
						"eventSource", "aws:sqs",
						"body", "{\"articleId\":\"" + id + "\"}"))
				.toList());
	}

	private static SummarizableArticle article(String id) {
		return new SummarizableArticle(id, "Tiêu đề " + id,
				"Đoạn trích đủ dài để tóm tắt cho " + id);
	}

	@Test
	void nhan_payload_sqs() {
		assertThat(handler.supports(sqsPayload("a1"))).isTrue();
	}

	@Test
	void khong_nhan_records_cua_nguon_khac() {
		assertThat(handler.supports(Map.of("Records", List.of(
				Map.of("eventSource", "aws:s3"))))).isFalse();
		assertThat(handler.supports(Map.of("job", "ingest-feeds"))).isFalse();
		assertThat(handler.supports(Map.of("Records", List.of()))).isFalse();
	}

	@Test
	void tom_tat_xong_thi_phat_event() {
		given(catalog.findSummarizable("a1")).willReturn(Optional.of(article("a1")));
		given(summarizer.summarize(any())).willReturn(Optional.of("Tóm tắt a1."));

		Object result = handler.handle(sqsPayload("a1"));

		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(events).publishEvent(captor.capture());
		assertThat(captor.getValue())
				.isEqualTo(new ArticleSummarized("a1", "Tóm tắt a1."));
		assertThat(result).isEqualTo(Map.of("batchItemFailures", List.of()));
	}

	/**
	 * CHỐT CHẶN IDEMPOTENT — test quan trọng nhất của cả phase.
	 *
	 * Hai producer (đường tươi + sweep) đẩy được cùng một article, và SQS còn
	 * giao lại message tới 3 lần. Nếu handler gọi model khi `findSummarizable`
	 * trả rỗng thì ta trả tiền lại cho một bài đã xong — và triệu chứng DUY NHẤT
	 * là hoá đơn: trang vẫn hiển thị đúng, log vẫn sạch, test khác vẫn xanh.
	 *
	 * Message vẫn được ACK (không nằm trong batchItemFailures): nó đã hoàn thành
	 * mục đích của mình, chỉ là bởi một lượt khác.
	 */
	@Test
	void khong_goi_model_khi_bai_da_co_summary() {
		given(catalog.findSummarizable("a1")).willReturn(Optional.empty());

		Object result = handler.handle(sqsPayload("a1"));

		verify(summarizer, never()).summarize(any());
		verify(events, never()).publishEvent(any());
		assertThat(result).isEqualTo(Map.of("batchItemFailures", List.of()));
	}

	/**
	 * Model hỏng ⇒ message vào `batchItemFailures` ⇒ SQS giao lại. Bài chưa có
	 * summary nên sweep cũng sẽ nhặt lại — hai lưới, và cửa sổ 48h là thứ chặn
	 * vòng lặp (ADR-0014).
	 */
	@Test
	void model_hong_thi_bao_dung_message_do() {
		given(catalog.findSummarizable("a1")).willReturn(Optional.of(article("a1")));
		given(catalog.findSummarizable("a2")).willReturn(Optional.of(article("a2")));
		given(summarizer.summarize(any()))
				.willReturn(Optional.empty())
				.willReturn(Optional.of("Tóm tắt a2."));

		Object result = handler.handle(sqsPayload("a1", "a2"));

		assertThat(result).isEqualTo(Map.of("batchItemFailures",
				List.of(Map.of("itemIdentifier", "msg-a1"))));
	}

	/**
	 * Gemini hỏng hàng loạt: sau K=3 lần hỏng LIÊN TIẾP, bỏ phần còn lại của
	 * batch và trả TẤT CẢ chúng về `batchItemFailures`.
	 *
	 * Không có cơ chế này thì 10 bài × 25s timeout = 250s > 120s function
	 * timeout ⇒ invoke chết, `batchItemFailures` không kịp trả, và CẢ batch quay
	 * lại — kể cả những bài đã tóm tắt xong, tức trả tiền lại cho chúng.
	 *
	 * `times(3)` là phần quan trọng nhất: nó khẳng định ta THẬT SỰ dừng gọi
	 * model, chứ không chỉ gắn nhãn hỏng cho phần còn lại sau khi vẫn gọi hết —
	 * và chính việc gọi hết mới là thứ làm invoke chết vì timeout.
	 */
	@Test
	void bo_phan_con_lai_sau_k_lan_hong_lien_tiep() {
		for (String id : List.of("a1", "a2", "a3", "a4", "a5")) {
			given(catalog.findSummarizable(id)).willReturn(Optional.of(article(id)));
		}
		given(summarizer.summarize(any())).willReturn(Optional.empty());

		Object result = handler.handle(sqsPayload("a1", "a2", "a3", "a4", "a5"));

		verify(summarizer, times(3)).summarize(any());
		assertThat(result).isEqualTo(Map.of("batchItemFailures", List.of(
				Map.of("itemIdentifier", "msg-a1"),
				Map.of("itemIdentifier", "msg-a2"),
				Map.of("itemIdentifier", "msg-a3"),
				Map.of("itemIdentifier", "msg-a4"),
				Map.of("itemIdentifier", "msg-a5"))));
	}

	/**
	 * LIÊN TIẾP, không phải tổng cộng. Một bài hỏng xen giữa những bài thành
	 * công là chuyện bình thường (content filter, một bài lạ) — dừng cả batch vì
	 * nó là phản ứng thái quá và làm mất thông lượng.
	 */
	@Test
	void hong_xen_ke_khong_lam_dung_batch() {
		for (String id : List.of("a1", "a2", "a3", "a4")) {
			given(catalog.findSummarizable(id)).willReturn(Optional.of(article(id)));
		}
		given(summarizer.summarize(any()))
				.willReturn(Optional.empty())
				.willReturn(Optional.of("ok"))
				.willReturn(Optional.empty())
				.willReturn(Optional.of("ok"));

		Object result = handler.handle(sqsPayload("a1", "a2", "a3", "a4"));

		verify(summarizer, times(4)).summarize(any());
		assertThat(result).isEqualTo(Map.of("batchItemFailures", List.of(
				Map.of("itemIdentifier", "msg-a1"),
				Map.of("itemIdentifier", "msg-a3"))));
	}

	/**
	 * Một lời gọi THÀNH CÔNG reset bộ đếm — nó là bằng chứng model còn sống.
	 *
	 * `hong_xen_ke_khong_lam_dung_batch` KHÔNG ghim được điều này: với K=3 và
	 * chỉ 4 bài, bộ đếm không reset vẫn chưa chạm ngưỡng, nên bỏ hẳn dòng reset
	 * đi thì test đó vẫn xanh. Chuỗi hỏng-hỏng-XONG-hỏng-hỏng-hỏng dưới đây là
	 * chuỗi ngắn nhất phân biệt được: có reset thì chạm ngưỡng ở bài THỨ SÁU và
	 * cả sáu đều được thử; không reset thì chạm ở bài thứ tư và hai bài cuối
	 * không bao giờ được gọi model.
	 */
	@Test
	void mot_lan_thanh_cong_reset_bo_dem() {
		for (String id : List.of("a1", "a2", "a3", "a4", "a5", "a6")) {
			given(catalog.findSummarizable(id)).willReturn(Optional.of(article(id)));
		}
		given(summarizer.summarize(any()))
				.willReturn(Optional.empty())
				.willReturn(Optional.empty())
				.willReturn(Optional.of("ok"))
				.willReturn(Optional.empty())
				.willReturn(Optional.empty())
				.willReturn(Optional.empty());

		handler.handle(sqsPayload("a1", "a2", "a3", "a4", "a5", "a6"));

		verify(summarizer, times(6)).summarize(any());
	}

	/**
	 * Bài bị BỎ QUA (đã có summary) không được reset bộ đếm hỏng liên tiếp —
	 * nó không phải bằng chứng rằng model đã hồi phục. Nếu nó reset, một batch
	 * xen kẽ hỏng/bỏ-qua sẽ không bao giờ chạm ngưỡng và ta quay lại đúng chế
	 * độ hỏng mà cơ chế này tồn tại để chặn.
	 */
	@Test
	void bai_bi_bo_qua_khong_reset_bo_dem() {
		given(catalog.findSummarizable("a1")).willReturn(Optional.of(article("a1")));
		given(catalog.findSummarizable("a2")).willReturn(Optional.empty());
		given(catalog.findSummarizable("a3")).willReturn(Optional.of(article("a3")));
		given(catalog.findSummarizable("a4")).willReturn(Optional.empty());
		given(catalog.findSummarizable("a5")).willReturn(Optional.of(article("a5")));
		given(catalog.findSummarizable("a6")).willReturn(Optional.of(article("a6")));
		given(summarizer.summarize(any())).willReturn(Optional.empty());

		handler.handle(sqsPayload("a1", "a2", "a3", "a4", "a5", "a6"));

		verify(summarizer, times(3)).summarize(any());
	}
}
