package dev.linhvu.news_aggregator.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@Lazy
class SsmGeminiKeyProvider implements GeminiKeyProvider {

	private static final Logger log = LoggerFactory.getLogger(SsmGeminiKeyProvider.class);

	private final SsmClient ssm;

	private final String fromProperty;

	private final String parameterName;

	// Đọc đúng MỘT lần mỗi execution environment. Một batch 10 article là 10 lần
	// cần key; đọc SSM 10 lần là 10 lần trả tiền KMS decrypt cho cùng một chuỗi.
	private volatile String cached;

	SsmGeminiKeyProvider(SsmClient ssm,
			@Value("${news.summarization.api-key}") String fromProperty,
			@Value("${news.summarization.key-parameter}") String parameterName) {
		this.ssm = ssm;
		this.fromProperty = fromProperty;
		this.parameterName = parameterName;
	}

	@Override
	public String apiKey() {
		// Escape hatch cho local: property thắng và SSM không bị chạm. Nhờ nó,
		// `bootRun` chạy được với GOOGLE_API_KEY mà không phải seed Floci.
		if (fromProperty != null && !fromProperty.isBlank()) {
			return fromProperty;
		}
		String value = cached;
		if (value == null) {
			synchronized (this) {
				value = cached;
				if (value == null) {
					value = readFromSsm();
					cached = value;
				}
			}
		}
		return value;
	}

	private String readFromSsm() {
		log.info("đọc API key từ SSM parameter {}", parameterName);
		// `withDecryption(true)` BẮT BUỘC với SecureString. Thiếu nó thì SSM trả
		// về CIPHERTEXT chứ không lỗi, và ciphertext trông y hệt một key hợp lệ
		// cho tới khi Gemini trả 400 — một lỗi trỏ sai hướng hoàn toàn.
		return ssm.getParameter(GetParameterRequest.builder()
						.name(parameterName)
						.withDecryption(true)
						.build())
				.parameter().value();
	}
}
