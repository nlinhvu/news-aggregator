package dev.linhvu.news_aggregator.platform;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GeminiKeyProviderTest {

	private final SsmClient ssm = mock(SsmClient.class);

	private SsmGeminiKeyProvider provider(String propertyValue) {
		return new SsmGeminiKeyProvider(ssm, propertyValue, "/news/test/gemini-api-key");
	}

	/**
	 * Escape hatch cho local: property có giá trị thì nó THẮNG và SSM không bị
	 * chạm. Đây là thứ khiến `./gradlew bootRun` chạy được với biến môi trường
	 * `GOOGLE_API_KEY` mà không phải seed key vào Floci sau mỗi lần restart.
	 */
	@Test
	void a_direct_property_never_touches_ssm() {
		assertThat(provider("key-from-property").apiKey()).isEqualTo("key-from-property");

		verifyNoInteractions(ssm);
	}

	@Test
	void reads_ssm_when_the_property_is_empty() {
		given(ssm.getParameter(any(GetParameterRequest.class)))
				.willReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("key-from-ssm").build())
						.build());

		assertThat(provider("").apiKey()).isEqualTo("key-from-ssm");
	}

	/**
	 * Cache trong execution environment. Lambda dùng lại environment giữa các
	 * invoke, và một batch 10 article là 10 lần cần key — đọc SSM 10 lần là 10
	 * lần trả tiền KMS decrypt và 10 lần độ trễ, để đổi lấy cùng một chuỗi.
	 */
	@Test
	void reads_ssm_only_once() {
		AtomicInteger calls = new AtomicInteger();
		given(ssm.getParameter(any(GetParameterRequest.class)))
				.willAnswer(inv -> {
					calls.incrementAndGet();
					return GetParameterResponse.builder()
							.parameter(Parameter.builder().value("key").build())
							.build();
				});

		SsmGeminiKeyProvider provider = provider("");
		provider.apiKey();
		provider.apiKey();
		provider.apiKey();

		assertThat(calls.get()).isEqualTo(1);
	}

	/**
	 * `withDecryption(true)` là bắt buộc với SecureString. Thiếu nó thì SSM trả
	 * về CIPHERTEXT chứ không lỗi — và ciphertext trông y hệt một API key hợp lệ
	 * cho tới khi Gemini trả 400. Đây là chế độ hỏng "trông như key sai".
	 */
	@Test
	void always_asks_for_decryption() {
		given(ssm.getParameter(any(GetParameterRequest.class)))
				.willReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("key").build())
						.build());

		provider("").apiKey();

		ArgumentCaptor<GetParameterRequest> captor =
				ArgumentCaptor.forClass(GetParameterRequest.class);
		verify(ssm).getParameter(captor.capture());
		assertThat(captor.getValue().withDecryption()).isTrue();
		assertThat(captor.getValue().name()).isEqualTo("/news/test/gemini-api-key");
	}
}
