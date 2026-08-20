package dev.linhvu.news_aggregator.summarization;

import java.time.Duration;
import java.util.List;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizerTest {

	/**
	 * Bằng đúng `news.summarization.max-summary-chars` của production. Chôn con số
	 * ở từng chỗ gọi — như bản trước làm với `400` ở ba nơi — thì đổi trần là sửa
	 * ba dòng, và cái bị quên luôn là dòng của test biên.
	 *
	 * Hằng số này KHÔNG đọc từ `application.yaml`: `SummarizerTest` gọi thẳng
	 * constructor nên nó không đi qua Spring. Chốt chặn cho giá trị cấu hình nằm ở
	 * `SummarizerWiringTest#the_configured_length_cap_is_500`; ở đây chỉ kiểm HÀNH VI
	 * quanh một trần cho trước.
	 */
	private static final int LENGTH_CAP = 500;

	private static final SummarizableArticle ARTICLE = new SummarizableArticle(
			"a1", "Spring Boot 4.1 released",
			"Spring Boot 4.1 introduces @ImportHttpServices and a new RestClient story.");

	/**
	 * `ChatClient` là interface fluent nhiều tầng, mock nó bằng Mockito rất giòn.
	 * Thay vào đó dựng một `ChatModel` giả trả `ChatResponse` cố định và bọc bằng
	 * `ChatClient.builder(model)` thật — cùng đường code với production, chỉ khác
	 * ở tầng cuối cùng.
	 *
	 * Đây cũng là lý do test này KHÔNG BAO GIỜ chạm mạng: một test gọi Gemini
	 * thật sẽ đỏ vào ngày Google đổi model, tốn tiền mỗi lần chạy CI, và không
	 * chứng minh được gì ổn định.
	 */
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void captureLog() {
		logs.start();
		((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Summarizer.class))
				.addAppender(logs);
	}

	@AfterEach
	void releaseLog() {
		((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Summarizer.class))
				.detachAppender(logs);
		logs.stop();
	}

	private List<String> logEvents() {
		return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private Summarizer summarizerReturns(String modelOutput) {
		ChatClient client = ChatClient.builder(
				new FakeChatModel(modelOutput)).build();
		return new Summarizer(client, Duration.ofSeconds(25), LENGTH_CAP);
	}

	@Test
	void returns_the_summary_when_the_model_answers_with_text() {
		assertThat(summarizerReturns("Spring Boot 4.1 thêm @ImportHttpServices.")
				.summarize(ARTICLE))
				.contains("Spring Boot 4.1 thêm @ImportHttpServices.");
	}

	@Test
	void trims_the_surrounding_whitespace() {
		assertThat(summarizerReturns("  Có khoảng trắng.  \n").summarize(ARTICLE))
				.contains("Có khoảng trắng.");
	}

	/**
	 * Model trả rỗng hoặc từ chối ⇒ KHÔNG ghi gì. Ghi một `summary` rỗng vào
	 * bảng là hỏng vĩnh viễn: `findSummarizable` coi bài đó đã xong nên sweep
	 * không bao giờ thử lại, mà trang thì hiển thị một đoạn trống.
	 */
	@Test
	void returns_empty_when_the_model_answers_empty() {
		assertThat(summarizerReturns("").summarize(ARTICLE)).isEmpty();
		assertThat(summarizerReturns("   \n  ").summarize(ARTICLE)).isEmpty();
	}

	/**
	 * Trần độ dài là chốt chặn cuối cùng chống việc model phớt lờ prompt. Một
	 * "tóm tắt" 3.000 ký tự không phải tóm tắt — nó là bài viết chép lại, và nó
	 * sẽ phá vỡ layout của trang lẫn item DynamoDB.
	 */
	@Test
	void returns_empty_when_the_model_answers_too_long() {
		assertThat(summarizerReturns("x".repeat(LENGTH_CAP + 1)).summarize(ARTICLE)).isEmpty();
	}

	/**
	 * Chiều ngược lại của test trên, và là chiều đã HỎNG THẬT.
	 *
	 * Nợ Phase 3 §20B #6, đo trên `dev` 2026-08-11: model trả **410** ký tự trên
	 * trần **400**, `Summarizer` vứt cả lời gọi, lần retry cho ra bản 337 ký tự và
	 * được nhận — trả tiền hai lần cho một bản hợp lệ vì lố 2,5%.
	 *
	 * Cơ chế `consecutive-failure-limit` của Phase 3 §17 #8 chặn hỏng HÀNG LOẠT;
	 * nó không chạm chế độ này chút nào.
	 *
	 * Núm vặn rẻ nhất là NỚI TRẦN, không phải cắt chuỗi: cắt ở giữa câu cho ra một
	 * đoạn cụt hiển thị cho người đọc, tệ hơn hẳn một đoạn dài hơn 10 ký tự.
	 *
	 * 410 là con số ĐO ĐƯỢC, không phải con số tròn cho đẹp — giữ nguyên nó để test
	 * còn chỉ về đúng sự cố đã xảy ra.
	 */
	@Test
	void a_summary_just_over_the_old_cap_is_no_longer_thrown_away() {
		String four_hundred_ten = "x".repeat(410);

		assertThat(summarizerReturns(four_hundred_ten).summarize(ARTICLE))
				.as("410 ký tự từng bị vứt khi trần là 400 — xem nợ §20B #6")
				.contains(four_hundred_ten);
	}

	/**
	 * Đảo `.param("title", …)` với `.param("excerpt", …)` cho nhau thì mọi test
	 * khác vẫn xanh: model nhận excerpt ở chỗ tiêu đề và ngược lại, rồi trả về
	 * một tóm tắt trông hợp lệ. Chỉ có đọc prompt thật mới bắt được.
	 */
	@Test
	void title_and_excerpt_land_in_the_right_slots_of_the_prompt() {
		FakeChatModel model = new FakeChatModel("Tóm tắt.");
		new Summarizer(ChatClient.builder(model).build(), Duration.ofSeconds(25), LENGTH_CAP)
				.summarize(ARTICLE);

		assertThat(model.lastPrompt)
				.contains("Tiêu đề: " + ARTICLE.title())
				.contains("Đoạn trích: " + ARTICLE.excerpt());
	}

	/**
	 * Model ném ⇒ rỗng, KHÔNG ném tiếp. Đây là nhánh nuôi `batchItemFailures`:
	 * handler thấy rỗng thì báo đúng message đó và SQS giao lại. Nếu ngoại lệ
	 * thoát ra khỏi `summarize` thì nó nổ ngược lên `handle` và CẢ batch quay
	 * lại — kể cả những bài đã tóm tắt xong trong cùng lượt.
	 *
	 * `calls` khẳng định rỗng đến TỪ lời gọi ném, không phải từ một nhánh
	 * short-circuit nào đó trả rỗng mà chưa từng chạm model.
	 */
	@Test
	void returns_empty_when_the_model_throws() {
		FakeChatModel model = new FakeChatModel("",
				new IllegalStateException("429 rate limit"));
		Summarizer summarizer = new Summarizer(
				ChatClient.builder(model).build(), Duration.ofSeconds(25), LENGTH_CAP);

		assertThat(summarizer.summarize(ARTICLE)).isEmpty();
		assertThat(model.calls).isEqualTo(1);
	}

	/**
	 * 429 phải phân biệt được với mọi lỗi model khác TRONG LOG. Đây không phải
	 * chuyện chi phí — chi phí model nằm ngoài phạm vi Phase 4 (TDD §17 #13) — mà
	 * là chuyện đúng/sai: hết quota làm bài LẶNG LẼ không có tóm tắt, và triệu
	 * chứng nhìn giống hệt "Gemini hay hỏng".
	 *
	 * Bốn môi trường dùng CHUNG một Google Cloud project nên chia chung 15 RPM /
	 * 1.000 RPD — quota tính theo project, không theo API key. Chiều siết là RPM:
	 * một lượt sweep `max-per-run: 25` chia thành 3 invoke song song có thể vượt 15
	 * lời gọi trong một phút. Chưa ai thấy vì lượt sweep thật lớn nhất từng chạy là
	 * `enqueued=2`.
	 *
	 * KHÔNG thêm metric, KHÔNG thêm alarm — chỉ một chữ trong dòng log đã có. Hành
	 * động khác hẳn nhau ("hết quota, đợi hoặc hạ `max-per-run`" so với "Google
	 * hỏng, chờ nó tự khỏi") nên người vận hành phải đọc ra được là loại nào.
	 */
	@Test
	void a_429_logs_differently_from_other_model_errors() {
		FakeChatModel model = new FakeChatModel("",
				new IllegalStateException("429 RESOURCE_EXHAUSTED"));

		assertThat(new Summarizer(ChatClient.builder(model).build(),
				Duration.ofSeconds(25), LENGTH_CAP).summarize(ARTICLE)).isEmpty();
		assertThat(logEvents()).anyMatch(m -> m.contains("quota"));
	}

	/**
	 * Chiều PHỦ ĐỊNH, và nó mới là chiều quan trọng.
	 *
	 * Nhận diện 429 làm bằng khớp chuỗi, vì Spring AI bọc lỗi provider qua nhiều
	 * lớp exception và không expose status code ổn định. Cách đó thô, nên rủi ro
	 * thật không phải "bỏ sót một 429" — sót thì người vận hành vẫn thấy một lỗi
	 * model bình thường và đi điều tra. Rủi ro thật là **gán nhãn quota cho một lỗi
	 * KHÔNG phải quota**: khi đó người vận hành đi hạ `max-per-run` và chờ quota
	 * hồi, trong khi thứ hỏng là chỗ khác hoàn toàn.
	 */
	@Test
	void other_model_errors_are_not_labelled_as_quota() {
		FakeChatModel model = new FakeChatModel("",
				new IllegalStateException("500 INTERNAL"));

		assertThat(new Summarizer(ChatClient.builder(model).build(),
				Duration.ofSeconds(25), LENGTH_CAP).summarize(ARTICLE)).isEmpty();
		assertThat(logEvents()).noneMatch(m -> m.contains("quota"));
	}

	/**
	 * CHUỖI EXCEPTION THẬT của production, không phải chuỗi rút gọn mà ba test
	 * trên dùng — và khác biệt đó từng làm nhánh 429 thành CODE CHẾT suốt Phase 3.
	 *
	 * `GoogleGenAiChatModel#getContentResponse` (spring-ai-google-genai 2.0.0,
	 * dòng 913) bắt MỌI exception của Google rồi ném lại
	 * `new RuntimeException("Failed to generate content", e)` — message là HẰNG
	 * SỐ. `CompletableFuture#get` bọc thêm một lớp `ExecutionException`. Kết quả:
	 *
	 *   ExecutionException → RuntimeException("Failed to generate content") → lỗi THẬT
	 *
	 * `Throwable#toString` chỉ ghép tên class với message của CHÍNH nó, và message
	 * của `ExecutionException` là `cause.toString()` — tức đúng HAI lớp trên cùng.
	 * Lỗi thật nằm ở lớp thứ ba nên `e.toString()` không bao giờ chạm tới nó.
	 *
	 * Ba test trên xanh vì fake ném thẳng `IllegalStateException("429 …")`, thiếu
	 * đúng lớp bọc mà Spring AI chèn vào. Đó là lý do một fake phải bọc ĐÚNG BẰNG
	 * production, không phải "gần giống".
	 *
	 * Đo trên prod 2026-08-11/12: 15 lượt thất bại liên tiếp đều ghi
	 * `java.util.concurrent.ExecutionException: java.lang.RuntimeException: Failed
	 * to generate content` — không một chữ nào nói vì sao.
	 */
	@Test
	void recognises_429_even_when_spring_ai_wraps_it_in_a_constant_message() {
		FakeChatModel model = new FakeChatModel("", new RuntimeException(
				"Failed to generate content",
				new IllegalStateException("429 RESOURCE_EXHAUSTED")));

		assertThat(new Summarizer(ChatClient.builder(model).build(),
				Duration.ofSeconds(25), LENGTH_CAP).summarize(ARTICLE)).isEmpty();
		assertThat(logEvents()).anyMatch(m -> m.contains("quota"));
	}

	/**
	 * Điều kiện cần để CHẨN ĐOÁN được, tách khỏi việc gán nhãn ở test trên.
	 *
	 * Với free tier 15 RPM / 1.000 RPD dùng chung cho bốn môi trường, "model hỏng"
	 * và "hết quota" dẫn tới hai hành động khác hẳn nhau — nhưng dòng log chỉ nói
	 * `Failed to generate content` thì không phân biệt được, và người trực phải
	 * chờ lượt sweep kế tiếp (6 giờ) để đoán tiếp.
	 */
	@Test
	void the_log_keeps_the_root_cause_not_just_the_wrapper() {
		FakeChatModel model = new FakeChatModel("", new RuntimeException(
				"Failed to generate content",
				new IllegalStateException("503 UNAVAILABLE model overloaded")));

		assertThat(new Summarizer(ChatClient.builder(model).build(),
				Duration.ofSeconds(25), LENGTH_CAP).summarize(ARTICLE)).isEmpty();
		assertThat(logEvents()).anyMatch(m -> m.contains("503 UNAVAILABLE"));
	}
}
