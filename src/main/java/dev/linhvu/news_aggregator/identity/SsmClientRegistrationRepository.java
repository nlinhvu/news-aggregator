package dev.linhvu.news_aggregator.identity;

import java.util.Set;

import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

/**
 * `ClientRegistrationRepository` nạp client secret từ SSM SecureString **lười**.
 *
 * Interface này có đúng MỘT method, `findByRegistrationId`, và Spring Security
 * chỉ gọi nó trong luồng đăng nhập — không bao giờ trên một request ẩn danh.
 * Đó là chỗ để hoãn lời gọi SSM tới đúng lúc cần.
 *
 * Vì sao không dùng `spring.security.oauth2.client.registration.*` như tài liệu:
 * cấu hình đó dựng registration từ property, mà property duy nhất chứa secret
 * chỉ đến được bằng `spring.config.import=aws-parameterstore:` — thứ chạy ở
 * tầng ConfigData tại MỌI cold start. Đây đúng là lý do Phase 3 tự dựng
 * `GeminiKeyProvider` thay vì dùng starter của Spring AI, và class này soi
 * gương `SsmGeminiKeyProvider` từ khoá `volatile` tới `withDecryption`.
 *
 * `@Profile(HTTP)` chứ không `@Component` trần: chỉ `web`/`admin` có bề mặt
 * đăng nhập, và chỉ role của chúng được `AppStack` cấp `ssm:GetParameter` trên
 * `/news/<env>/cognito-client-secret`. Ở `ingest`/`summarize`, một lời gọi nhầm
 * phải chết lúc TRA BEAN chứ không lúc gọi SSM rồi nhận AccessDenied — cùng lý
 * lẽ với `chatClient` ở `RoleProfileContextTest`.
 */
@Component
@Profile(RoleProfiles.HTTP)
class SsmClientRegistrationRepository implements ClientRegistrationRepository {

	static final String REGISTRATION_ID = "cognito";

	private static final Logger log =
			LoggerFactory.getLogger(SsmClientRegistrationRepository.class);

	private final SsmClient ssm;
	private final String secretParameterName;
	private final String issuerUri;
	private final String clientId;
	private final String publicBaseUrl;
	private volatile ClientRegistration cached;

	SsmClientRegistrationRepository(SsmClient ssm,
			@Value("${news.identity.cognito.secret-parameter}") String secretParameterName,
			@Value("${news.identity.cognito.issuer-uri}") String issuerUri,
			@Value("${news.identity.cognito.client-id}") String clientId,
			@Value("${news.identity.public-base-url}") String publicBaseUrl) {
		this.ssm = ssm;
		this.secretParameterName = secretParameterName;
		this.issuerUri = issuerUri;
		this.clientId = clientId;
		this.publicBaseUrl = publicBaseUrl;
	}

	@Override
	public ClientRegistration findByRegistrationId(String registrationId) {
		if (!REGISTRATION_ID.equals(registrationId)) {
			return null;
		}
		ClientRegistration current = cached;
		if (current == null) {
			synchronized (this) {
				current = cached;
				if (current == null) {
					current = build();
					// Gán SAU khi `build()` trả về, nên lần THẤT BẠI không được
					// nhớ: SSM hỏng thì exception bay lên và lần đăng nhập sau
					// thử lại. Cùng khuôn "nhớ thành công, quên thất bại" với
					// `FailClosedDynamoDbStateRepository` của Phase 1.
					cached = current;
				}
			}
		}
		return current;
	}

	private ClientRegistration build() {
		// DISCOVERY, không hardcode. Bản Task 10 tự dựng bốn URI từ issuer với lý
		// do "chúng suy ra được theo quy tắc cố định của Cognito" — TIỀN ĐỀ ĐÓ
		// SAI, và đã kiểm chứng bằng chính discovery document của pool dev:
		//
		//   issuer      https://cognito-idp.us-east-1.amazonaws.com/us-east-1_PdskR1W0U
		//   authorize   https://na-dev-auth.auth.us-east-1.amazoncognito.com/oauth2/authorize
		//   token       https://na-dev-auth.auth.us-east-1.amazoncognito.com/oauth2/token
		//   userInfo    https://na-dev-auth.auth.us-east-1.amazoncognito.com/oauth2/userInfo
		//   jwks        https://cognito-idp.us-east-1.amazonaws.com/…/.well-known/jwks.json
		//
		// BA trong bốn endpoint nằm trên MANAGED LOGIN DOMAIN, không trên host
		// của issuer; chỉ `jwks_uri` là đoán đúng. Đăng nhập vì thế hỏng ngay ở
		// bước redirect, và không log nào của ta nói ra.
		//
		// Giá phải trả: một GET tới `/.well-known/openid-configuration` ở lần
		// đăng nhập ĐẦU TIÊN của mỗi execution environment — nằm trong `build()`
		// nên vẫn LƯỜI, đường đọc ẩn danh không chạm tới, và kết quả được cache
		// cùng `ClientRegistration`. So với chuỗi authorize → token → userinfo
		// ngay sau đó thì nó là nhiễu.
		//
		// Lợi ích thứ hai, và nó mới là lợi ích lớn: local dùng CÙNG một cơ chế.
		// `mock-oauth2-server` đặt endpoint ở `/cognito/authorize`, không phải
		// `/cognito/oauth2/authorize`, nên với bản hardcode thì `LoginFlowIT`
		// không thể chạy — tức đường đăng nhập vĩnh viễn không có test nào.
		return ClientRegistrations.fromIssuerLocation(issuerUri)
				.registrationId(REGISTRATION_ID)
				.clientId(clientId)
				.clientSecret(readSecret())
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				// TUYỆT ĐỐI, KHÔNG dùng placeholder `{baseUrl}`. Spring Security
				// giãn `{baseUrl}` từ host của REQUEST, mà sau CloudFront →
				// Lambda Function URL host đó là `*.lambda-url.us-east-1.on.aws`
				// (EdgeStack dùng `ALL_VIEWER_EXCEPT_HOST_HEADER`, `Host` của
				// viewer bị strip vì SigV4 đòi Host của Function URL). Cognito
				// khi đó từ chối vì `redirect_uri` lệch `callbackUrls` — đã ĐO
				// trên dev 2026-08-13.
				//
				// `news.identity.public-base-url` đến từ `AppStack` và dựng bằng
				// CHÍNH `cfg.appDomain()` mà `IdentityStack` dùng cho
				// `callbackUrls`: một nguồn sự thật, nên hai bên không lệch được.
				.redirectUri(publicBaseUrl + "/api/auth/callback/" + REGISTRATION_ID)
				.scope(Set.of("openid", "email"))
				// `issuerUri`, `authorizationUri`, `tokenUri`, `jwkSetUri` và
				// `userInfoUri` KHÔNG khai ở đây — discovery đã điền đúng cả năm.
				// Khai đè lại là quay về đúng chỗ vừa sai.
				.userNameAttributeName("sub")
				.clientName("News Aggregator")
				.build();
	}

	private String readSecret() {
		log.info("đọc client secret của Cognito từ SSM parameter {}", secretParameterName);
		// `withDecryption(true)` BẮT BUỘC với SecureString. Thiếu nó thì SSM trả
		// về CIPHERTEXT chứ không lỗi, và secret sai chỉ lộ ra ở bước đổi code
		// lấy token — một lỗi OAuth tối nghĩa, cách xa chỗ gây ra nó.
		return ssm.getParameter(GetParameterRequest.builder()
						.name(secretParameterName)
						.withDecryption(true)
						.build())
				.parameter().value();
	}
}
