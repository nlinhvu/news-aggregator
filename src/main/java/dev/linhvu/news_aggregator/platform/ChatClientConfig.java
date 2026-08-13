package dev.linhvu.news_aggregator.platform;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.retry.RetryTemplate;

/**
 * Dựng tay thay vì để autoconfiguration làm, vì key không phải property — nó
 * được lấy lười từ SSM (TDD §17 #3). Master §4 nguyên tắc 6 vẫn đứng: logic
 * nghiệp vụ chỉ biết `ChatClient`, còn chỗ này là plumbing của `platform`.
 *
 * Bean ở `platform` chứ không `summarization` vì Phase 8 (dịch) và Phase 9
 * (chatbot) đều sẽ cần nó; master §5 xếp `platform` đúng vai "AWS client bean",
 * và một chat client cùng loại (TDD §17 #15).
 */
@Configuration(proxyBeanMethods = false)
// CHỈ `summarize` gọi model. Ba bean dưới đã `@Lazy` nên chúng không được dựng
// lúc khởi động ở đâu cả; thứ `@Profile` mua thêm là ĐỊNH NGHĨA bean biến mất
// hẳn khỏi ba context kia, nên một `getBean(ChatClient.class)` nhầm chỗ chết
// bằng NoSuchBeanDefinitionException thay vì lặng lẽ đi gọi SSM.
@Profile(RoleProfiles.SUMMARIZE)
public class ChatClientConfig {

	@Bean
	@Lazy
	Client genAiClient(GeminiKeyProvider keys) {
		// TUYỆT ĐỐI KHÔNG gọi .project() / .location() / .vertexAI(true): bất kỳ
		// cái nào cũng chuyển client sang chế độ Vertex AI, và khi đó API key của
		// Developer API bị từ chối bằng lỗi 400 auth — trông y hệt "key sai".
		return Client.builder().apiKey(keys.apiKey()).build();
	}

	@Bean
	@Lazy
	GoogleGenAiChatModel chatModel(Client genAiClient,
			@Value("${news.summarization.model}") String model,
			@Value("${news.summarization.temperature}") Double temperature) {
		return GoogleGenAiChatModel.builder()
				.genAiClient(genAiClient)
				.options(GoogleGenAiChatOptions.builder()
						.model(model)
						.temperature(temperature)
						.build())
				// Retry để RetryTemplate mặc định; tầng thử lại thật của phase này
				// là SQS (maxReceiveCount=3) và sweep. Xem TDD §17 #8.
				.retryTemplate(new RetryTemplate())
				.observationRegistry(ObservationRegistry.NOOP)
				.build();
	}

	@Bean
	@Lazy
	ChatClient chatClient(GoogleGenAiChatModel chatModel) {
		return ChatClient.builder(chatModel).build();
	}
}
