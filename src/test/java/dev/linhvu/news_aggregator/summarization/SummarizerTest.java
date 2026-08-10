package dev.linhvu.news_aggregator.summarization;

import java.time.Duration;

import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizerTest {

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
	private Summarizer summarizerTraVe(String modelOutput) {
		ChatClient client = ChatClient.builder(
				new FakeChatModel(modelOutput)).build();
		return new Summarizer(client, Duration.ofSeconds(25), 400);
	}

	@Test
	void tra_ve_tom_tat_khi_model_tra_van_ban() {
		assertThat(summarizerTraVe("Spring Boot 4.1 thêm @ImportHttpServices.")
				.summarize(ARTICLE))
				.contains("Spring Boot 4.1 thêm @ImportHttpServices.");
	}

	@Test
	void cat_khoang_trang_thua_o_hai_dau() {
		assertThat(summarizerTraVe("  Có khoảng trắng.  \n").summarize(ARTICLE))
				.contains("Có khoảng trắng.");
	}

	/**
	 * Model trả rỗng hoặc từ chối ⇒ KHÔNG ghi gì. Ghi một `summary` rỗng vào
	 * bảng là hỏng vĩnh viễn: `findSummarizable` coi bài đó đã xong nên sweep
	 * không bao giờ thử lại, mà trang thì hiển thị một đoạn trống.
	 */
	@Test
	void tra_ve_rong_khi_model_tra_rong() {
		assertThat(summarizerTraVe("").summarize(ARTICLE)).isEmpty();
		assertThat(summarizerTraVe("   \n  ").summarize(ARTICLE)).isEmpty();
	}

	/**
	 * Trần độ dài là chốt chặn cuối cùng chống việc model phớt lờ prompt. Một
	 * "tóm tắt" 3.000 ký tự không phải tóm tắt — nó là bài viết chép lại, và nó
	 * sẽ phá vỡ layout của trang lẫn item DynamoDB.
	 */
	@Test
	void tra_ve_rong_khi_model_tra_qua_dai() {
		assertThat(summarizerTraVe("x".repeat(401)).summarize(ARTICLE)).isEmpty();
	}

	/**
	 * Đảo `.param("title", …)` với `.param("excerpt", …)` cho nhau thì mọi test
	 * khác vẫn xanh: model nhận excerpt ở chỗ tiêu đề và ngược lại, rồi trả về
	 * một tóm tắt trông hợp lệ. Chỉ có đọc prompt thật mới bắt được.
	 */
	@Test
	void title_va_excerpt_vao_dung_cho_trong_prompt() {
		FakeChatModel model = new FakeChatModel("Tóm tắt.");
		new Summarizer(ChatClient.builder(model).build(), Duration.ofSeconds(25), 400)
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
	void tra_ve_rong_khi_model_nem() {
		FakeChatModel model = new FakeChatModel("",
				new IllegalStateException("429 rate limit"));
		Summarizer summarizer = new Summarizer(
				ChatClient.builder(model).build(), Duration.ofSeconds(25), 400);

		assertThat(summarizer.summarize(ARTICLE)).isEmpty();
		assertThat(model.calls).isEqualTo(1);
	}
}
