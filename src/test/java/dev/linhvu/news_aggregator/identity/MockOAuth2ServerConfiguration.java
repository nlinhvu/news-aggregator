package dev.linhvu.news_aggregator.identity;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * IdP local thật cho test đăng nhập. **Floci không thay được nó**: Cognito của
 * Floci không có `/oauth2/authorize`, và `/oauth2/token` của nó chỉ nhận
 * `client_credentials`.
 *
 * <p><b>Image và `JSON_CONFIG` phải KHỚP `compose.yaml`.</b> Lệch nhau là local
 * và CI kiểm hai thứ khác nhau, mà cái sai sẽ lộ ra ở nơi khó truy nhất.
 *
 * <p><b>Container là `static` và KHÔNG dùng `@Container`</b> — nó khởi động một
 * lần cho cả suite rồi sống tới khi JVM tắt (Testcontainers dọn bằng Ryuk).
 * Mỗi Spring context lấy một container riêng sẽ trả giá ~3 giây mỗi context,
 * và ở đây có ít nhất hai context dùng tới nó.
 *
 * <p>`issuer` mà mock server tự khai lấy theo `Host` của request, nên nó tự mô
 * tả đúng cổng ngẫu nhiên do Testcontainers ánh xạ — điều kiện để
 * `ClientRegistrations.fromIssuerLocation` không từ chối vì issuer lệch.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockOAuth2ServerConfiguration {

	/** Khớp `compose.yaml`. Đổi ở đây thì đổi cả bên kia. */
	private static final String IMAGE = "ghcr.io/navikt/mock-oauth2-server:4.0.1";

	/**
	 * `interactiveLogin` bật màn hình đăng nhập thật, nên luồng ở test là CÙNG
	 * MỘT `oauth2Login()` với prod — chỉ khác issuer. Claim `cognito:groups`
	 * phải có, nếu không cổng `ops` của slice 5 không kiểm được ở local và ta
	 * chỉ phát hiện nó hỏng trên `dev`.
	 */
	private static final String JSON_CONFIG = """
			{"interactiveLogin":true,"tokenCallbacks":[{"issuerId":"cognito",\
			"tokenExpiry":3600,"requestMappings":[{"requestParam":"scope","match":"*",\
			"claims":{"cognito:groups":["ops"],"email":"dev@local"}}]}]}""";

	private static final GenericContainer<?> MOCK_IDP =
			new GenericContainer<>(IMAGE)
					.withExposedPorts(8080)
					.withEnv("JSON_CONFIG", JSON_CONFIG)
					.waitingFor(Wait.forHttp("/cognito/.well-known/openid-configuration")
							.forPort(8080));

	static {
		MOCK_IDP.start();
	}

	/** `http://localhost:<cổng ánh xạ>/cognito`. */
	public static String issuerUri() {
		return "http://" + MOCK_IDP.getHost() + ":" + MOCK_IDP.getMappedPort(8080)
				+ "/cognito";
	}

	/**
	 * Client secret trong SSM của Floci. Có nó thì `SsmClientRegistrationRepository`
	 * đi qua ĐÚNG đường của prod — `SsmClient` thật, `withDecryption(true)` thật —
	 * chứ không phải một mock bỏ qua nửa logic.
	 *
	 * Bean này CỐ Ý không nằm ở `FlociTestConfiguration`: đặt ở đó thì mọi context
	 * đều gọi `SsmClient` lúc khởi động, và `AnonymousReadTest` — thứ khẳng định
	 * đường đọc ẩn danh KHÔNG chạm SSM — sẽ đỏ vì fixture chứ không vì code.
	 */
	@Bean
	InitializingBean cognitoSecretParameter(SsmClient ssm) {
		return () -> ssm.putParameter(PutParameterRequest.builder()
				.name("/news/local/cognito-client-secret")
				.value("local-secret-khong-duoc-kiem")
				.type(ParameterType.SECURE_STRING)
				.overwrite(true)
				.build());
	}

	/**
	 * `DynamicPropertyRegistrar` (một BEAN) chứ KHÔNG `@DynamicPropertySource`
	 * (một method static).
	 *
	 * Đã trả giá để biết: Spring chỉ quét `@DynamicPropertySource` trên chính
	 * CLASS TEST và cây thừa kế của nó — class `@Import` thì không. Bản đầu đặt
	 * annotation đó ở đây, và hệ quả là app lặng lẽ dùng giá trị mặc định
	 * `http://localhost:8090/cognito` trong `application.yaml`, rồi chết ở tận
	 * bước authorize bằng `Unable to resolve Configuration with the provided
	 * Issuer` — một lỗi không hề nhắc tới property nào.
	 */
	@Bean
	DynamicPropertyRegistrar cognitoProperties() {
		return registry -> {
			registry.add("news.identity.cognito.issuer-uri",
					MockOAuth2ServerConfiguration::issuerUri);
			registry.add("news.identity.cognito.client-id", () -> "news-local");
			// Mock server không kiểm secret; giá trị chỉ cần khác rỗng. `SsmClient`
			// vẫn được gọi thật qua Floci — xem `cognitoSecretParameter`.
			registry.add("news.identity.cognito.secret-parameter",
					() -> "/news/local/cognito-client-secret");
			registry.add("news.identity.cognito.logout-uri",
					() -> MockOAuth2ServerConfiguration.issuerUri() + "/endsession");
		};
	}
}
