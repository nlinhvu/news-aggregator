package dev.linhvu.news_aggregator.platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.Compressor;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.MessageWriter;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;

/**
 * Exporter OTLP chuẩn KHÔNG ký gì cả, mà X-Ray OTLP endpoint là endpoint AWS nên
 * đòi SigV4 trên mọi request. Đây là chỗ ghép hai thứ đó lại.
 *
 * Đường vào là SPI `HttpSenderProvider` của OTel: `OtlpHttpSpanExporterBuilder`
 * hỏi một `ComponentLoader` để tìm provider, và Boot 4.1 cho ta chạm vào builder
 * đó qua bean `OtlpHttpSpanExporterBuilderCustomizer` (xem `ObservabilityConfig`).
 * CỐ Ý không dùng file `META-INF/services`: khi có hai provider trên classpath —
 * và `opentelemetry-exporter-sender-okhttp` LUÔN có mặt vì starter kéo nó theo —
 * OTel chọn theo thứ tự không xác định rồi chỉ ghi một dòng log. Nạp tường minh
 * thì exporter chỉ nhìn thấy đúng một provider.
 *
 * ADR-0016 Option A. Đường còn lại là ADOT Java agent, thứ cộng thẳng vào cửa
 * chặn cold start của Task 17 — trong khi ở đây không có dependency runtime nào
 * mới: `software.amazon.awssdk:http-auth-aws` đã nằm sẵn trên classpath theo
 * spring-cloud-aws, và HTTP client là bản có sẵn của JDK.
 */
class SigV4HttpSenderProvider implements HttpSenderProvider {

	private final AwsCredentialsProvider credentials;

	private final AwsRegionProvider regions;

	SigV4HttpSenderProvider(AwsCredentialsProvider credentials, AwsRegionProvider regions) {
		this.credentials = credentials;
		this.regions = regions;
	}

	@Override
	public HttpSender createSender(HttpSenderConfig config) {
		return new SigV4HttpSender(config, this.credentials, this.regions.getRegion().id());
	}

	/**
	 * `ComponentLoader` chỉ trả về provider NÀY cho `HttpSenderProvider`, còn mọi
	 * SPI khác thì uỷ quyền cho class loader như mặc định — nếu nuốt luôn phần còn
	 * lại thì exporter mất những thứ nó nạp qua cùng cơ chế, và triệu chứng sẽ
	 * không liên quan gì tới ký SigV4.
	 */
	ComponentLoader componentLoader() {
		ComponentLoader defaultLoader = ComponentLoader.forClassLoader(getClass().getClassLoader());
		return new ComponentLoader() {
			@Override
			@SuppressWarnings("unchecked")
			public <T> Iterable<T> load(Class<T> spi) {
				return spi == HttpSenderProvider.class
						? (Iterable<T>) List.of(SigV4HttpSenderProvider.this)
						: defaultLoader.load(spi);
			}
		};
	}

	/**
	 * Ký hay không quyết định bằng HOST của endpoint, không bằng profile hay biến
	 * môi trường: local trỏ `otel-lgtm` nên không có gì để ký (và cũng không có
	 * credential để ký), prod trỏ `xray.<region>.amazonaws.com` nên phải ký. Cùng
	 * một lớp code chạy ở cả hai nơi — đúng chữ master §8.2 *"cùng bộ
	 * instrumentation, khác exporter"*.
	 */
	static final class SigV4HttpSender implements HttpSender {

		private static final String AWS_HOST_SUFFIX = ".amazonaws.com";

		/** Tên service để ký. X-Ray OTLP endpoint nằm dưới `xray`, không phải `logs`. */
		private static final String SIGNING_NAME = "xray";

		/**
		 * JDK `HttpClient` TỪ CHỐI mấy header này bằng `IllegalArgumentException`, mà
		 * signer thì luôn thêm `Host` vào bản đã ký. Bỏ chúng ra là AN TOÀN chứ không
		 * phải mẹo: JDK tự sinh `Host` và `Content-Length` với đúng giá trị mà chữ ký
		 * đã bao gồm, nên chữ ký vẫn khớp.
		 */
		private static final Set<String> JDK_SET_BY_US = Set.of(
				"host", "content-length", "connection", "expect", "upgrade");

		private final HttpSenderConfig config;

		private final AwsCredentialsProvider credentials;

		private final String region;

		private final boolean signing;

		private final HttpClient http;

		SigV4HttpSender(HttpSenderConfig config, AwsCredentialsProvider credentials,
				String region) {
			this.config = config;
			this.credentials = credentials;
			this.region = region;
			this.signing = config.getEndpoint().getHost().endsWith(AWS_HOST_SUFFIX);
			this.http = HttpClient.newBuilder()
					.connectTimeout(config.getConnectTimeout())
					.build();
		}

		@Override
		public void send(MessageWriter writer, Consumer<HttpResponse> onResponse,
				Consumer<Throwable> onError) {
			try {
				byte[] body = serialize(writer);
				this.http.sendAsync(request(body), BodyHandlers.ofByteArray())
						.whenComplete((response, error) -> {
							if (error != null) {
								onError.accept(error);
							}
							else {
								onResponse.accept(new JdkResponse(response));
							}
						});
			}
			catch (IOException | RuntimeException ex) {
				// Gồm cả lỗi resolve credential. Ném ra ngoài `send` thì
				// BatchSpanProcessor coi là lỗi không bắt được và dừng hẳn vòng
				// export; đưa vào `onError` thì nó chỉ mất lô này.
				onError.accept(ex);
			}
		}

		@Override
		public CompletableResultCode shutdown() {
			this.http.close();
			return CompletableResultCode.ofSuccess();
		}

		private byte[] serialize(MessageWriter writer) throws IOException {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream(writer.getContentLength());
			Compressor compressor = this.config.getCompressor();
			if (compressor == null) {
				writer.writeMessage(buffer);
			}
			else {
				try (OutputStream compressed = compressor.compress(buffer)) {
					writer.writeMessage(compressed);
				}
			}
			return buffer.toByteArray();
		}

		private HttpRequest request(byte[] body) {
			HttpRequest.Builder request = HttpRequest.newBuilder(this.config.getEndpoint())
					.timeout(this.config.getTimeout())
					.POST(BodyPublishers.ofByteArray(body));
			headers(body).forEach((name, value) -> {
				if (!JDK_SET_BY_US.contains(name.toLowerCase(Locale.ROOT))) {
					value.forEach(v -> request.header(name, v));
				}
			});
			return request.build();
		}

		/**
		 * Trả về header CUỐI CÙNG của request — đã ký nếu endpoint là AWS. Tách
		 * riêng khỏi việc gửi để test kiểm được chữ ký mà không cần mạng.
		 */
		Map<String, List<String>> headers(byte[] body) {
			Map<String, List<String>> headers = new LinkedHashMap<>();
			headers.put("Content-Type", List.of(this.config.getContentType()));
			Compressor compressor = this.config.getCompressor();
			if (compressor != null) {
				headers.put("Content-Encoding", List.of(compressor.getEncoding()));
			}
			this.config.getHeadersSupplier().get().forEach(headers::putIfAbsent);
			return this.signing ? signed(body, headers) : headers;
		}

		private Map<String, List<String>> signed(byte[] body,
				Map<String, List<String>> headers) {
			SdkHttpRequest.Builder unsigned = SdkHttpRequest.builder()
					.method(SdkHttpMethod.POST)
					.uri(URI.create(this.config.getEndpoint().toString()));
			headers.forEach(unsigned::putHeader);
			return AwsV4HttpSigner.create()
					.sign(r -> r
							// Giải credential ở MỖI request, không cache: trên Lambda
							// credential là tạm thời và xoay theo execution environment.
							// Provider của SDK tự cache trong hạn, nên đây không phải
							// lời gọi mạng mỗi lần.
							.identity(this.credentials.resolveCredentials())
							.request(unsigned.build())
							.payload(ContentStreamProvider.fromByteArray(body))
							.putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, SIGNING_NAME)
							.putProperty(AwsV4HttpSigner.REGION_NAME, this.region))
					.request()
					.headers();
		}

		/**
		 * `getStatusMessage` trả chuỗi rỗng vì HTTP/2 bỏ hẳn reason phrase và JDK
		 * không cấp nó. Thông điệp lỗi thật của X-Ray nằm trong BODY, và OTel log
		 * body chứ không log reason phrase.
		 */
		private record JdkResponse(java.net.http.HttpResponse<byte[]> raw)
				implements HttpResponse {

			@Override
			public int getStatusCode() {
				return this.raw.statusCode();
			}

			@Override
			public String getStatusMessage() {
				return "";
			}

			@Override
			public byte[] getResponseBody() {
				return this.raw.body();
			}
		}
	}
}
