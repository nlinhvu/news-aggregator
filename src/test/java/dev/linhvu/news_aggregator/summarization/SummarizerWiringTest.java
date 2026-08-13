package dev.linhvu.news_aggregator.summarization;

import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `SummarizerTest` gọi thẳng constructor `(ChatClient, Duration, int)` nên nó
 * KHÔNG bao giờ đi qua Spring — và constructor kia, cái mang `@Value`, thì
 * `@Lazy` cả hai đầu: không ai hỏi tới `Summarizer` lúc dựng context. Hệ quả là
 * gõ sai `news.summarization.call-timeout` vẫn xanh cả suite và chỉ chết ở lượt
 * summarize đầu tiên trên Lambda.
 *
 * Test này ép dựng bean để placeholder được resolve thật. Cùng lý do và cùng
 * property với `ChatClientConfigTest` — dùng đúng chuỗi property đó để hai lớp
 * chia sẻ một Spring context thay vì dựng thêm một cái nữa.
 */
@SpringBootTest(properties = "news.summarization.api-key=key-gia-cho-test")
// `Summarizer` phụ thuộc `ChatClient`, nay `@Profile(SUMMARIZE)`.
@ActiveProfiles(RoleProfiles.SUMMARIZE)
class SummarizerWiringTest {

	@Autowired
	ApplicationContext context;

	@Autowired
	Environment env;

	@Test
	void dung_duoc_summarizer_tu_context() {
		assertThat(context.getBean(Summarizer.class)).isNotNull();
	}

	/**
	 * Trần độ dài CẤU HÌNH phải là 500, và test này là chốt chặn DUY NHẤT của nó.
	 *
	 * Nợ Phase 3 §20B #6, đo thật trên `dev` 2026-08-11: model trả **410** ký tự
	 * trên trần **400**, `Summarizer` vứt cả lời gọi, lần retry cho ra bản 337 ký
	 * tự và được nhận — trả tiền hai lần cho một bản hợp lệ vì lố 2,5%. Prompt xin
	 * "tối đa 60 từ", mà 60 từ tiếng Việt vượt 400 ký tự một cách bình thường, nên
	 * 400 không phải trần an toàn — nó nằm ngay giữa vùng đầu ra hợp lệ.
	 *
	 * VÌ SAO Ở ĐÂY CHỨ KHÔNG Ở `SummarizerTest`: mọi test bên đó truyền trần
	 * THẲNG vào constructor, nên không test nào trong số chúng đọc `application.yaml`.
	 * Đổi dòng yaml về 400 và cả suite vẫn xanh — chuỗi `max-summary-chars` xuất
	 * hiện ở đúng MỘT chỗ trong toàn repo, chính dòng yaml đó. Không có test này
	 * thì món nợ được trả rồi lặng lẽ phát sinh lại.
	 *
	 * Ghim CHÍNH XÁC 500 chứ không `>= 500`: chỉnh trần là quyết định có hệ quả về
	 * chi phí và về layout trang, nên nó phải là một hành vi có ý thức kèm đọc lại
	 * lý do trên, không phải một con số ai đó vặn nhẹ.
	 */
	@Test
	void tran_do_dai_cau_hinh_la_500() {
		assertThat(env.getProperty("news.summarization.max-summary-chars", Integer.class))
				.as("nợ Phase 3 §20B #6 — 400 vứt mất bản tóm tắt 410 ký tự hợp lệ")
				.isEqualTo(500);
	}
}
