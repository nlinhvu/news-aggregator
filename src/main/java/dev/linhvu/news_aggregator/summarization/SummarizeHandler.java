package dev.linhvu.news_aggregator.summarization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
import dev.linhvu.news_aggregator.platform.EventJobHandler;
import dev.linhvu.news_aggregator.summarization.events.ArticleSummarized;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
class SummarizeHandler implements EventJobHandler {

	private static final Logger log = LoggerFactory.getLogger(SummarizeHandler.class);

	private final ArticleCatalog catalog;

	private final Summarizer summarizer;

	private final ApplicationEventPublisher events;

	private final Tracer tracer;

	private final Propagator propagator;

	private final int consecutiveFailureLimit;

	// `@Lazy` phải ở ĐIỂM INJECT, không chỉ trên class `Summarizer`. Handler này
	// bị dựng EAGER dù không có `@Lazy` nào sai: `EventsController` là
	// `@RestController` và nhận `List<EventJobHandler>`, mà dựng một `List` thì
	// Spring phải khởi tạo mọi phần tử. `@Lazy` trên định nghĩa bean chỉ hoãn tới
	// khi CÓ NGƯỜI HỎI — và đây chính là người hỏi.
	//
	// Không có nó, hằng chuỗi `Summarizer → ChatClient → GoogleGenAiChatModel →
	// Client → apiKey()` chạy lúc khởi động: mỗi cold start của người ĐỌC cũng
	// trả một lời gọi SSM, và `NewsAggregatorApplicationTests#contextLoads` đỏ
	// bằng `SdkClientException` khi không có credential.
	//
	// Constructor vẫn chỉ có MỘT nên KHÔNG cần `@Autowired` — khác `SweepHandler`
	// và `Summarizer`, hai chỗ có constructor thứ hai cho test.
	SummarizeHandler(ArticleCatalog catalog, @Lazy Summarizer summarizer,
			ApplicationEventPublisher events, Tracer tracer, Propagator propagator,
			@Value("${news.summarization.consecutive-failure-limit}")
			int consecutiveFailureLimit) {
		this.catalog = catalog;
		this.summarizer = summarizer;
		this.events = events;
		this.tracer = tracer;
		this.propagator = propagator;
		this.consecutiveFailureLimit = consecutiveFailureLimit;
	}

	@Override
	public boolean supports(Map<String, Object> payload) {
		// `Records` MỘT MÌNH không đủ: S3, DynamoDB Streams và Kinesis cùng dùng
		// key đó. Kiểm `eventSource` của phần tử đầu là thứ phân biệt thật.
		if (!(payload.get("Records") instanceof List<?> records) || records.isEmpty()) {
			return false;
		}
		return records.get(0) instanceof Map<?, ?> first
				&& "aws:sqs".equals(first.get("eventSource"));
	}

	@Override
	public Object handle(Map<String, Object> payload) {
		List<SqsBatch.Message> messages = SqsBatch.parse(payload);
		List<String> failed = new ArrayList<>();
		int summarized = 0;
		int skipped = 0;
		int consecutiveFailures = 0;
		boolean aborted = false;

		for (SqsBatch.Message message : messages) {
			if (aborted) {
				// Phần còn lại KHÔNG được gọi model, nhưng vẫn phải vào
				// batchItemFailures — chúng chưa được xử lý, nên ACK chúng là mất
				// bài. SQS giao lại sau visibility timeout, lúc đó Gemini có thể
				// đã hồi phục.
				failed.add(message.messageId());
				continue;
			}

			// Nối lượt xử lý này vào trace của lượt ingest đã enqueue nó. Không có
			// bước này thì X-Ray hiện HAI trace rời rạc, trong khi chuỗi
			// *phát hiện → thêm → enqueue → tóm tắt* mới là distributed trace duy
			// nhất hệ thống này có.
			//
			// `traceparent` null ⇒ `extract` trả về một context rỗng và span mới
			// là gốc của trace mới. Đó là hành vi ĐÚNG, không phải fallback tạm.
			Span span = propagator
					.extract(message, (m, key) -> "traceparent".equals(key)
							? m.traceparent() : null)
					.name("summarize")
					.start();
			try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
				// CHỐT CHẶN IDEMPOTENT. Rỗng nghĩa là article đã có summary, không có
				// excerpt, hoặc excerpt quá ngắn — cả ba đều là "không có việc để
				// làm", và ACK message là đúng: nó đã hoàn thành mục đích, chỉ là bởi
				// một lượt khác.
				Optional<SummarizableArticle> article =
						catalog.findSummarizable(message.articleId());
				if (article.isEmpty()) {
					skipped++;
					// KHÔNG reset `consecutiveFailures`: bỏ qua không phải bằng chứng
					// model đã hồi phục. Reset ở đây thì một batch xen kẽ hỏng/bỏ-qua
					// không bao giờ chạm ngưỡng.
					log.debug("bỏ qua {} — không có việc để làm", message.articleId());
					continue;
				}

				Optional<String> summary = summarizer.summarize(article.get());
				if (summary.isEmpty()) {
					failed.add(message.messageId());
					consecutiveFailures++;
					if (consecutiveFailures >= consecutiveFailureLimit) {
						// Thứ thay thế circuit breaker (TDD §17 #8). Không có nó thì
						// 10 bài × 25s timeout vượt 120s function timeout, invoke chết,
						// batchItemFailures không kịp trả, và CẢ batch quay lại — kể cả
						// bài đã xong.
						log.warn("{} lời gọi model hỏng liên tiếp — bỏ phần còn lại "
								+ "của batch", consecutiveFailures);
						aborted = true;
					}
					continue;
				}
				// Thành công là bằng chứng DUY NHẤT model còn sống, nên nó là chỗ DUY
				// NHẤT được reset bộ đếm.
				consecutiveFailures = 0;
				events.publishEvent(new ArticleSummarized(
						message.articleId(), summary.get()));
				summarized++;
			}
			finally {
				span.end();
			}
		}

		log.info("summarize batch: summarized={} skipped={} failed={}",
				summarized, skipped, failed.size());
		return SqsBatch.failures(failed);
	}
}
