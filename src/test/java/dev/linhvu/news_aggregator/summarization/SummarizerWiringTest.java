package dev.linhvu.news_aggregator.summarization;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

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
class SummarizerWiringTest {

	@Autowired
	ApplicationContext context;

	@Test
	void dung_duoc_summarizer_tu_context() {
		assertThat(context.getBean(Summarizer.class)).isNotNull();
	}
}
