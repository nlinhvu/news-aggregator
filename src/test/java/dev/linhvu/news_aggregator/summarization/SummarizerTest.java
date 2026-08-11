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
	 * `SummarizerWiringTest#tran_do_dai_cau_hinh_la_500`; ở đây chỉ kiểm HÀNH VI
	 * quanh một trần cho trước.
	 */
	private static final int TRAN = 500;

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
	void batLog() {
		logs.start();
		((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Summarizer.class))
				.addAppender(logs);
	}

	@AfterEach
	void thaLog() {
		((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Summarizer.class))
				.detachAppender(logs);
		logs.stop();
	}

	private List<String> logEvents() {
		return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private Summarizer summarizerTraVe(String modelOutput) {
		ChatClient client = ChatClient.builder(
				new FakeChatModel(modelOutput)).build();
		return new Summarizer(client, Duration.ofSeconds(25), TRAN);
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
		assertThat(summarizerTraVe("x".repeat(TRAN + 1)).summarize(ARTICLE)).isEmpty();
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
	void ban_tom_tat_lo_tran_cu_khong_con_bi_vut() {
		String bon_tram_muoi = "x".repeat(410);

		assertThat(summarizerTraVe(bon_tram_muoi).summarize(ARTICLE))
				.as("410 ký tự từng bị vứt khi trần là 400 — xem nợ §20B #6")
				.contains(bon_tram_muoi);
	}

	/**
	 * Đảo `.param("title", …)` với `.param("excerpt", …)` cho nhau thì mọi test
	 * khác vẫn xanh: model nhận excerpt ở chỗ tiêu đề và ngược lại, rồi trả về
	 * một tóm tắt trông hợp lệ. Chỉ có đọc prompt thật mới bắt được.
	 */
	@Test
	void title_va_excerpt_vao_dung_cho_trong_prompt() {
		FakeChatModel model = new FakeChatModel("Tóm tắt.");
		new Summarizer(ChatClient.builder(model).build(), Duration.ofSeconds(25), TRAN)
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
				ChatClient.builder(model).build(), Duration.ofSeconds(25), TRAN);

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
	void loi_429_ghi_log_khac_loi_model_khac() {
		FakeChatModel model = new FakeChatModel("",
				new IllegalStateException("429 RESOURCE_EXHAUSTED"));

		assertThat(new Summarizer(ChatClient.builder(model).build(),
				Duration.ofSeconds(25), TRAN).summarize(ARTICLE)).isEmpty();
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
	void loi_model_khac_khong_bi_gan_nhan_quota() {
		FakeChatModel model = new FakeChatModel("",
				new IllegalStateException("500 INTERNAL"));

		assertThat(new Summarizer(ChatClient.builder(model).build(),
				Duration.ofSeconds(25), TRAN).summarize(ARTICLE)).isEmpty();
		assertThat(logEvents()).noneMatch(m -> m.contains("quota"));
	}
}
