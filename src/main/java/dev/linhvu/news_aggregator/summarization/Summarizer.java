package dev.linhvu.news_aggregator.summarization;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@Lazy
class Summarizer {

	private static final Logger log = LoggerFactory.getLogger(Summarizer.class);

	// Prompt chốt Ở ĐÂY, một hằng số duy nhất — sửa prompt là sửa đúng một chỗ
	// và diff của nó đọc được. Nội dung khớp TDD §13.
	private static final String PROMPT = """
			Bạn tóm tắt bài viết kỹ thuật cho lập trình viên Việt Nam.

			Quy tắc:
			- Viết bằng TIẾNG VIỆT, nhưng GIỮ NGUYÊN technical term tiếng Anh
			  (dependency injection, virtual thread, circuit breaker — không dịch).
			- 2 tới 3 câu, tối đa 60 từ.
			- Plain text. Không markdown, không xuống dòng, không bullet.
			- CHỈ dùng thông tin trong đoạn trích bên dưới. Không suy diễn, không
			  bổ sung kiến thức bên ngoài. Đoạn trích mỏng thì tóm tắt mỏng.
			- Không mở đầu bằng "Bài viết này…". Vào thẳng nội dung.

			Tiêu đề: {title}
			Đoạn trích: {excerpt}
			""";

	private final ChatClient chatClient;

	private final Duration callTimeout;

	private final int maxSummaryChars;

	// `@Autowired` BẮT BUỘC ở đây, không phải trang trí: class có HAI constructor
	// nên Spring không suy ra được cái nào là điểm inject; không đánh dấu thì nó
	// rơi về constructor không tham số và chết bằng `NoSuchMethodException`. Và
	// vì bean này `@Lazy` cả hai đầu, lỗi đó KHÔNG lộ lúc dựng context — nó chờ
	// tới lượt summarize đầu tiên trên Lambda. `SummarizerWiringTest` là chỗ duy
	// nhất bắt được.
	@Autowired
	Summarizer(ChatClient chatClient,
			@Value("${news.summarization.call-timeout}") String callTimeout,
			@Value("${news.summarization.max-summary-chars}") int maxSummaryChars) {
		this(chatClient, DurationStyle.detectAndParse(callTimeout), maxSummaryChars);
	}

	Summarizer(ChatClient chatClient, Duration callTimeout, int maxSummaryChars) {
		this.chatClient = chatClient;
		this.callTimeout = callTimeout;
		this.maxSummaryChars = maxSummaryChars;
	}

	Optional<String> summarize(SummarizableArticle article) {
		String text;
		try {
			// Timeout CỨNG mỗi lời gọi. Không có nó thì 10 bài × timeout mặc định
			// của HTTP client vượt 120s, cả invoke chết vì Lambda timeout, và khi
			// đó `batchItemFailures` không kịp trả — CẢ batch quay lại, kể cả
			// những bài đã tóm tắt xong (TDD §5.4).
			text = CompletableFuture
					.supplyAsync(() -> chatClient.prompt()
							.user(u -> u.text(PROMPT)
									.param("title", article.title())
									.param("excerpt", article.excerpt()))
							.call()
							.content())
					.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			log.warn("model timeout sau {} cho article {}", callTimeout,
					article.articleId());
			return Optional.empty();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception e) {
			// KHÔNG log nội dung prompt hay excerpt (TDD §14.2).
			//
			// 429 tách riêng vì HÀNH ĐỘNG khác hẳn: "hết quota, đợi hoặc hạ
			// max-per-run" so với "Google hỏng, chờ nó tự khỏi". Bốn môi trường
			// dùng chung một Google Cloud project nên chia chung 15 RPM / 1.000
			// RPD — quota tính theo project, không theo API key — và một lượt sweep
			// lớn chạm trần RPM là chuyện có thật.
			//
			// Nhận diện bằng chuỗi vì Spring AI bọc lỗi provider vào nhiều lớp
			// exception khác nhau và không expose status code ổn định; ở đây còn
			// thêm một lớp `ExecutionException` của `CompletableFuture`. Thô, nhưng
			// nó có test canh cả hai chiều — và chiều PHỦ ĐỊNH mới là chiều quan
			// trọng: gán nhãn quota cho một lỗi không phải quota sẽ dẫn người vận
			// hành đi hạ max-per-run trong khi thứ hỏng nằm ở chỗ khác.
			String detail = e.toString();
			if (detail.contains("429") || detail.contains("RESOURCE_EXHAUSTED")) {
				log.warn("model từ chối vì quota cho article {}: {}",
						article.articleId(), detail);
			}
			else {
				log.warn("model hỏng cho article {}: {}", article.articleId(), detail);
			}
			return Optional.empty();
		}

		if (text == null || text.isBlank()) {
			// Ghi một `summary` rỗng là hỏng VĨNH VIỄN: `findSummarizable` sẽ coi
			// bài đó đã xong nên sweep không bao giờ thử lại, mà trang thì hiển
			// thị một đoạn trống.
			log.warn("model trả rỗng cho article {}", article.articleId());
			return Optional.empty();
		}
		String trimmed = text.trim();
		if (trimmed.length() > maxSummaryChars) {
			// Chốt chặn cuối chống việc model phớt lờ prompt. Một "tóm tắt" dài
			// hơn trần không phải tóm tắt — nó là bài viết chép lại.
			log.warn("model trả {} ký tự (trần {}) cho article {} — bỏ",
					trimmed.length(), maxSummaryChars, article.articleId());
			return Optional.empty();
		}
		return Optional.of(trimmed);
	}
}
