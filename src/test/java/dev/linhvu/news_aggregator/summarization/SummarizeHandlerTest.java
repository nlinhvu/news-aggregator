package dev.linhvu.news_aggregator.summarization;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
import dev.linhvu.news_aggregator.summarization.events.ArticleSummarized;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

	// `NOOP` chứ không phải mock: mọi test ở lớp ngoài đo hành vi batch, và một
	// `Propagator` mock phải stub cả chuỗi `extract().name().start()` mới chạy
	// được — công sức đó không mua thêm assertion nào. Phần tracing THẬT nằm ở
	// `TraceAcrossSqs`.
	private final SummarizeHandler handler = new SummarizeHandler(catalog, summarizer,
			events, Tracer.NOOP, Propagator.NOOP, 3);

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
	void accepts_an_sqs_payload() {
		assertThat(handler.supports(sqsPayload("a1"))).isTrue();
	}

	@Test
	void does_not_accept_records_from_another_producer() {
		assertThat(handler.supports(Map.of("Records", List.of(
				Map.of("eventSource", "aws:s3"))))).isFalse();
		assertThat(handler.supports(Map.of("job", "ingest-feeds"))).isFalse();
		assertThat(handler.supports(Map.of("Records", List.of()))).isFalse();
	}

	@Test
	void publishes_an_event_once_the_summary_is_done() {
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
	void does_not_call_the_model_when_the_article_already_has_a_summary() {
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
	void a_broken_model_reports_exactly_that_message() {
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
	void drops_the_remainder_after_k_consecutive_failures() {
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
	void interleaved_failures_do_not_stall_the_batch() {
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
	 * `interleaved_failures_do_not_stall_the_batch` KHÔNG ghim được điều này: với K=3 và
	 * chỉ 4 bài, bộ đếm không reset vẫn chưa chạm ngưỡng, nên bỏ hẳn dòng reset
	 * đi thì test đó vẫn xanh. Chuỗi hỏng-hỏng-XONG-hỏng-hỏng-hỏng dưới đây là
	 * chuỗi ngắn nhất phân biệt được: có reset thì chạm ngưỡng ở bài THỨ SÁU và
	 * cả sáu đều được thử; không reset thì chạm ở bài thứ tư và hai bài cuối
	 * không bao giờ được gọi model.
	 */
	@Test
	void a_single_success_resets_the_counter() {
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
	void a_skipped_article_does_not_reset_the_counter() {
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

	/**
	 * Chốt chặn DUY NHẤT cho việc `traceparent` được ĐỌC chứ không chỉ được parse.
	 * `SqsBatchTest` chứng minh chuỗi đó tới được `Message`; chỗ nó có thể chết
	 * lặng lẽ là ở đây — bỏ `propagator.extract(...)` mà chỉ `tracer.nextSpan()`
	 * thì mọi test khác vẫn xanh, chỉ là X-Ray hiện HAI trace rời rạc thay vì một.
	 *
	 * Cần `Tracer` và `Propagator` THẬT nên là `@SpringBootTest`; `Propagator.NOOP`
	 * không parse gì cả. Cùng khuôn với `IngestionRunnerTest.TraceContextIntoVirtualThread`.
	 */
	@Nested
	@SpringBootTest
	class TraceAcrossSqs {

		private static final String TRACEPARENT =
				"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

		private static final String PARENT_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

		@Autowired
		Tracer tracer;

		@Autowired
		Propagator propagator;

		/** Đo TRONG lúc xử lý — sau khi `span.end()` thì không còn gì để hỏi. */
		private AtomicReference<String> traceIdWhileHandling() {
			AtomicReference<String> traceId = new AtomicReference<>();
			given(summarizer.summarize(any())).willAnswer(inv -> {
				traceId.set(tracer.currentSpan().context().traceId());
				return Optional.of("Tóm tắt.");
			});
			return traceId;
		}

		private SummarizeHandler realHandler() {
			return new SummarizeHandler(catalog, summarizer, events, tracer, propagator, 3);
		}

		@Test
		void the_summarize_span_sits_inside_the_ingest_run_trace() {
			given(catalog.findSummarizable("a1")).willReturn(Optional.of(article("a1")));
			AtomicReference<String> traceId = traceIdWhileHandling();

			realHandler().handle(Map.of("Records", List.of(Map.of(
					"messageId", "msg-a1",
					"eventSource", "aws:sqs",
					"body", "{\"articleId\":\"a1\"}",
					"messageAttributes", Map.of("traceparent", Map.of(
							"stringValue", TRACEPARENT, "dataType", "String"))))));

			assertThat(traceId).hasValue(PARENT_TRACE_ID);
		}

		/**
		 * Message cũ và message gửi tay không có `traceparent`. Chúng phải sinh ra
		 * một trace MỚI — có span thật, chỉ là không nối vào đâu — chứ không phải
		 * chạy ngoài mọi span, vì lúc đó dòng log của lượt xử lý mất `trace_id`.
		 */
		@Test
		void no_traceparent_starts_a_brand_new_trace() {
			given(catalog.findSummarizable("a1")).willReturn(Optional.of(article("a1")));
			AtomicReference<String> traceId = traceIdWhileHandling();

			realHandler().handle(sqsPayload("a1"));

			assertThat(traceId.get()).isNotBlank().isNotEqualTo(PARENT_TRACE_ID);
		}
	}
}
