package dev.linhvu.news_aggregator.summarization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
import dev.linhvu.news_aggregator.platform.EventJobHandler;
import dev.linhvu.news_aggregator.summarization.events.ArticleSummarized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
class SummarizeHandler implements EventJobHandler {

	private static final Logger log = LoggerFactory.getLogger(SummarizeHandler.class);

	private final ArticleCatalog catalog;

	private final Summarizer summarizer;

	private final ApplicationEventPublisher events;

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
	SummarizeHandler(ArticleCatalog catalog, @Lazy Summarizer summarizer,
			ApplicationEventPublisher events) {
		this.catalog = catalog;
		this.summarizer = summarizer;
		this.events = events;
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

		for (SqsBatch.Message message : messages) {
			// CHỐT CHẶN IDEMPOTENT. Rỗng nghĩa là article đã có summary, không có
			// excerpt, hoặc excerpt quá ngắn — cả ba đều là "không có việc để
			// làm", và ACK message là đúng: nó đã hoàn thành mục đích, chỉ là bởi
			// một lượt khác.
			Optional<SummarizableArticle> article =
					catalog.findSummarizable(message.articleId());
			if (article.isEmpty()) {
				skipped++;
				log.debug("bỏ qua {} — không có việc để làm", message.articleId());
				continue;
			}

			Optional<String> summary = summarizer.summarize(article.get());
			if (summary.isEmpty()) {
				failed.add(message.messageId());
				continue;
			}
			events.publishEvent(new ArticleSummarized(
					message.articleId(), summary.get()));
			summarized++;
		}

		log.info("summarize batch: summarized={} skipped={} failed={}",
				summarized, skipped, failed.size());
		return SqsBatch.failures(failed);
	}
}
