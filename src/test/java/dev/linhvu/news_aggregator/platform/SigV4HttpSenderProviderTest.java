package dev.linhvu.news_aggregator.platform;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import io.opentelemetry.sdk.common.export.Compressor;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.ProxyOptions;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chế độ hỏng mà file này canh: exporter gửi thẳng, không ký, X-Ray trả 403, và
 * `BatchSpanProcessor` chỉ ghi một dòng WARN rồi vứt lô span. Triệu chứng ở phía
 * người đọc là **X-Ray rỗng** — không lỗi deploy, không alarm, không gì cả.
 */
class SigV4HttpSenderProviderTest {

	private static final byte[] BODY = "not-real-protobuf".getBytes();

	@Test
	void signs_with_sigv4_when_the_endpoint_is_xray() {
		Map<String, List<String>> headers = headersFor(
				"https://xray.us-east-1.amazonaws.com/v1/traces");

		assertThat(headers).containsKey("Authorization");
		String authorization = headers.get("Authorization").getFirst();
		assertThat(authorization)
				.as("SigV4 chứ không phải một scheme nào khác")
				.startsWith("AWS4-HMAC-SHA256 ")
				// Credential scope mang TÊN SERVICE. Ký nhầm service — `logs` chẳng
				// hạn, endpoint OTLP của log nằm ở đó — vẫn ra một chữ ký hợp lệ về
				// hình thức và vẫn bị X-Ray từ chối.
				.contains("/us-east-1/xray/aws4_request")
				.contains("SignedHeaders=")
				.contains("Signature=");
		assertThat(headers).containsKey("X-Amz-Date");
	}

	/**
	 * Local trỏ `otel-lgtm`, và ở đó KHÔNG có gì để ký — quan trọng hơn: không có
	 * credential nào để ký. Nếu lớp này ký vô điều kiện thì `./gradlew bootRun`
	 * trên máy không cấu hình AWS sẽ chết ở mỗi lượt export, và cái chết đó nằm
	 * trong thread của batch processor nên không ai thấy.
	 */
	@Test
	void does_not_sign_when_the_endpoint_is_local() {
		Map<String, List<String>> headers = headersFor("http://localhost:4318/v1/traces");

		assertThat(headers).doesNotContainKey("Authorization");
		assertThat(headers).containsEntry("Content-Type", List.of("application/x-protobuf"));
	}

	/**
	 * Trên Lambda credential LUÔN là tạm thời, nên thiếu `X-Amz-Security-Token` là
	 * 403 ở prod trong khi mọi test dùng credential tĩnh vẫn xanh.
	 */
	@Test
	void temporary_credentials_carry_the_security_token() {
		var sender = new SigV4HttpSenderProvider(
				StaticCredentialsProvider.create(AwsSessionCredentials.create(
						"AKIDTEST", "secret", "lambda-token")),
				() -> Region.US_EAST_1)
				.createSender(config("https://xray.us-east-1.amazonaws.com/v1/traces"));

		Map<String, List<String>> headers =
				((SigV4HttpSenderProvider.SigV4HttpSender) sender).headers(BODY);

		assertThat(headers).containsEntry("X-Amz-Security-Token",
				List.of("lambda-token"));
	}

	private Map<String, List<String>> headersFor(String endpoint) {
		var sender = new SigV4HttpSenderProvider(
				StaticCredentialsProvider.create(
						AwsBasicCredentials.create("AKIDTEST", "secret")),
				() -> Region.US_EAST_1)
				.createSender(config(endpoint));
		return ((SigV4HttpSenderProvider.SigV4HttpSender) sender).headers(BODY);
	}

	/**
	 * Dựng tay thay vì dùng `ImmutableHttpSenderConfig` của OTel: class đó nằm
	 * trong gói `internal`, tức hợp đồng có thể đổi giữa hai bản vá.
	 */
	private HttpSenderConfig config(String endpoint) {
		return new HttpSenderConfig() {

			@Override
			public URI getEndpoint() {
				return URI.create(endpoint);
			}

			@Override
			public String getContentType() {
				return "application/x-protobuf";
			}

			@Override
			public Compressor getCompressor() {
				return null;
			}

			@Override
			public Duration getTimeout() {
				return Duration.ofSeconds(10);
			}

			@Override
			public Duration getConnectTimeout() {
				return Duration.ofSeconds(10);
			}

			@Override
			public Supplier<Map<String, List<String>>> getHeadersSupplier() {
				return Map::of;
			}

			@Override
			public ProxyOptions getProxyOptions() {
				return null;
			}

			@Override
			public RetryPolicy getRetryPolicy() {
				return null;
			}

			@Override
			public SSLContext getSslContext() {
				return null;
			}

			@Override
			public X509TrustManager getTrustManager() {
				return null;
			}

			@Override
			public ExecutorService getExecutorService() {
				return null;
			}
		};
	}
}
