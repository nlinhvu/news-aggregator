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
	void property_thang_va_khong_cham_ssm() {
		assertThat(provider("key-tu-property").apiKey()).isEqualTo("key-tu-property");

		verifyNoInteractions(ssm);
	}

	@Test
	void doc_ssm_khi_property_rong() {
		given(ssm.getParameter(any(GetParameterRequest.class)))
				.willReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("key-tu-ssm").build())
						.build());

		assertThat(provider("").apiKey()).isEqualTo("key-tu-ssm");
	}

	/**
	 * Cache trong execution environment. Lambda dùng lại environment giữa các
	 * invoke, và một batch 10 article là 10 lần cần key — đọc SSM 10 lần là 10
	 * lần trả tiền KMS decrypt và 10 lần độ trễ, để đổi lấy cùng một chuỗi.
	 */
	@Test
	void chi_doc_ssm_mot_lan() {
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
	void luon_yeu_cau_giai_ma() {
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
