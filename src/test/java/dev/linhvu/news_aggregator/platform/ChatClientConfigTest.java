package dev.linhvu.news_aggregator.platform;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import software.amazon.awssdk.services.ssm.SsmClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `@Lazy` che ĐÚNG cái chốt chặn duy nhất mà DI có: bean không bao giờ được
 * dựng thì một dependency KHÔNG TỒN TẠI cũng không làm context chết. Cả
 * `./gradlew test` lẫn `bootBuildImage` đều xanh, và `SsmClient` thiếu chỉ lộ ra
 * ở lượt invoke Lambda đầu tiên — đúng con bug `SqsClient` của Task 3, chỉ khác
 * chỗ nó nổ muộn hơn nhiều.
 *
 * Test này ép dựng đầu chuỗi (`ChatClient`) để kéo theo cả bốn bean và
 * `SsmClient` do `ParameterStoreAutoConfiguration` cấp.
 *
 * `api-key` đặt sẵn nên `apiKey()` KHÔNG chạm SSM — cùng escape hatch mà
 * `bootRun` dùng. Không có lời gọi mạng nào ở đây: `Client.builder()` chỉ dựng
 * đối tượng, và không test nào gọi model.
 */
@SpringBootTest(properties = "news.summarization.api-key=fake-key-for-test")
// Cả chuỗi bean này nay là `@Profile(SUMMARIZE)` — chỉ function `summarize`
// có đường tới model.
@ActiveProfiles(RoleProfiles.SUMMARIZE)
class ChatClientConfigTest {

	@Autowired
	ApplicationContext context;

	@Test
	void builds_the_whole_chain_of_lazy_beans() {
		assertThat(context.getBean(ChatClient.class)).isNotNull();
	}

	@Test
	void a_real_ssm_client_exists_on_the_context() {
		assertThat(context.getBean(SsmClient.class)).isNotNull();
	}

	@Test
	void a_direct_property_means_ssm_is_never_touched() {
		assertThat(context.getBean(GeminiKeyProvider.class).apiKey())
				.isEqualTo("fake-key-for-test");
	}
}
