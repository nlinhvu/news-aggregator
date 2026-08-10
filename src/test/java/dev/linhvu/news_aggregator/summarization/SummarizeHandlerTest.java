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
import static org.mockito.Mockito.verify;

class SummarizeHandlerTest {

	private final ArticleCatalog catalog = mock(ArticleCatalog.class);

	private final Summarizer summarizer = mock(Summarizer.class);

	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final SummarizeHandler handler =
			new SummarizeHandler(catalog, summarizer, events);

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
}
