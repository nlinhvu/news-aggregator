package dev.linhvu.news_aggregator.identity;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import org.springframework.security.oauth2.client.registration.ClientRegistration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SsmClientRegistrationRepositoryTest {

	@Test
	void does_not_call_ssm_until_someone_logs_in() {
		// Đây là toàn bộ lý do class này tồn tại. `ClientRegistrationRepository`
		// là bean EAGER — nó được dựng lúc tạo security filter chain, tức là ở
		// MỌI cold start, kể cả invoke của người đọc ẩn danh. Nếu nó đọc SSM
		// trong constructor, mọi người đọc ẩn danh trả tiền cho một lời gọi
		// mạng mà họ không dùng, và cold start dày thêm mà không ai truy ra lý do.
		SsmClient ssm = mock(SsmClient.class);

		new SsmClientRegistrationRepository(ssm, "/news/test/cognito-client-secret",
				MockOAuth2ServerConfiguration.issuerUri(), "client-abc", "https://news.example");

		verifyNoInteractions(ssm);
	}

	@Test
	void calls_ssm_exactly_once_across_many_lookups() {
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s3cr3t").build())
						.build());

		SsmClientRegistrationRepository repo = new SsmClientRegistrationRepository(
				ssm, "/news/test/cognito-client-secret",
				MockOAuth2ServerConfiguration.issuerUri(), "client-abc", "https://news.example");

		ClientRegistration first = repo.findByRegistrationId("cognito");
		ClientRegistration second = repo.findByRegistrationId("cognito");

		assertThat(first.getClientSecret()).isEqualTo("s3cr3t");
		assertThat(second).isSameAs(first);
		verify(ssm, times(1)).getParameter(any(GetParameterRequest.class));
	}

	@Test
	void decrypts_the_secure_string_instead_of_reading_it_raw() {
		// Thiếu `withDecryption(true)` thì SSM trả về BẢN MÃ, và client secret
		// sai thì Cognito từ chối ở bước đổi code lấy token — một lỗi OAuth
		// tối nghĩa, xa chỗ gây ra nó.
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s").build()).build());

		new SsmClientRegistrationRepository(ssm, "/p", MockOAuth2ServerConfiguration.issuerUri(),
				"client-abc", "https://news.example").findByRegistrationId("cognito");

		verify(ssm).getParameter(GetParameterRequest.builder()
				.name("/p").withDecryption(true).build());
	}

	@Test
	void the_registration_id_is_cognito_and_the_redirect_uri_lives_under_api() {
		// Đường callback PHẢI nằm trong /api/* — CloudFront route `/*` sang S3,
		// nên đường mặc định `/login/oauth2/code/*` của Spring Security sẽ rơi
		// vào bucket SPA và trả 404 (TDD §7).
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s").build()).build());

		SsmClientRegistrationRepository repo = new SsmClientRegistrationRepository(
				ssm, "/p", MockOAuth2ServerConfiguration.issuerUri(), "client-abc",
				"https://news.example");
		ClientRegistration reg = repo.findByRegistrationId("cognito");

		assertThat(reg.getRedirectUri())
				.isEqualTo("https://news.example/api/auth/callback/cognito");
		assertThat(reg.getScopes()).containsExactlyInAnyOrder("openid", "email");
		assertThat(repo.findByRegistrationId("does-not-exist")).isNull();
	}

	@Test
	void the_redirect_uri_is_absolute_and_uses_no_baseUrl_placeholder() {
		// Ghim chính cái bug đã ĐO trên dev 2026-08-13. `{baseUrl}` được Spring
		// Security giãn ra từ HOST CỦA REQUEST, mà sau CloudFront →
		// Lambda Function URL host đó là `*.lambda-url.us-east-1.on.aws`:
		// EdgeStack dùng `ALL_VIEWER_EXCEPT_HOST_HEADER` nên `Host` của viewer bị
		// strip (SigV4 đòi Host của Function URL). Hệ quả: `redirect_uri` gửi lên
		// Cognito lệch `callbackUrls` ⇒ Cognito từ chối, và không log nào của ta
		// nói ra điều đó.
		//
		// Test local KHÔNG bao giờ tự bắt được: ở local `Host` là `localhost`,
		// tức host ĐÚNG, nên `{baseUrl}` giãn ra chuẩn và mọi thứ xanh.
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s").build()).build());

		ClientRegistration reg = new SsmClientRegistrationRepository(
				ssm, "/p", MockOAuth2ServerConfiguration.issuerUri(), "client-abc",
				"https://news.example").findByRegistrationId("cognito");

		assertThat(reg.getRedirectUri())
				.as("mọi placeholder ở đây đều được giãn từ host của request — "
						+ "thứ SAI sau CloudFront")
				.doesNotContain("{").doesNotContain("}")
				.startsWith("https://news.example/");
	}

	@Test
	void all_four_endpoints_come_FROM_DISCOVERY_not_from_string_concatenation() {
		// Chốt chặn cho lỗi nặng nhất của Phase 7, tìm ra ở Task 12.
		//
		// Bản Task 10 tự dựng `issuer + "/oauth2/authorize"` với lý do "quy tắc
		// cố định của Cognito". Discovery document THẬT của pool dev nói ngược:
		//   issuer     https://cognito-idp.us-east-1.amazonaws.com/us-east-1_PdskR1W0U
		//   authorize  https://na-dev-auth.auth.us-east-1.amazoncognito.com/oauth2/authorize
		// BA trong bốn endpoint nằm trên managed login domain; chỉ `jwks_uri` là
		// đoán đúng. Đăng nhập vì thế hỏng ngay bước redirect.
		//
		// Mock server đặt endpoint ở `<issuer>/authorize`, KHÔNG phải
		// `<issuer>/oauth2/authorize`. Nên assertion này đỏ ngay nếu ai đó quay
		// về nối chuỗi — bất kể họ nối theo quy ước nào.
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s").build()).build());
		String issuer = MockOAuth2ServerConfiguration.issuerUri();

		ClientRegistration reg = new SsmClientRegistrationRepository(
				ssm, "/p", issuer, "client-abc", "https://news.example")
				.findByRegistrationId("cognito");

		assertThat(reg.getProviderDetails().getAuthorizationUri())
				.as("phải lấy từ discovery document, không phải issuer + hằng chuỗi")
				.isEqualTo(issuer + "/authorize");
		assertThat(reg.getProviderDetails().getTokenUri()).isEqualTo(issuer + "/token");
		assertThat(reg.getProviderDetails().getUserInfoEndpoint().getUri())
				.isEqualTo(issuer + "/userinfo");
		assertThat(reg.getProviderDetails().getJwkSetUri()).isEqualTo(issuer + "/jwks");
	}

	@Test
	void does_not_cache_a_failed_attempt() {
		// Cùng khuôn với `FailClosedDynamoDbStateRepository` của Phase 1: nhớ
		// thành công, QUÊN thất bại. Cache lần hỏng nghĩa là một lượt SSM lỗi
		// làm đăng nhập chết vĩnh viễn cho tới lần deploy sau.
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenThrow(new RuntimeException("SSM tạm thời hỏng"))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s3cr3t").build()).build());

		SsmClientRegistrationRepository repo = new SsmClientRegistrationRepository(
				ssm, "/p", MockOAuth2ServerConfiguration.issuerUri(), "client-abc",
				"https://news.example");

		assertThatThrownBy(() -> repo.findByRegistrationId("cognito"))
				.isInstanceOf(RuntimeException.class);
		assertThat(repo.findByRegistrationId("cognito").getClientSecret())
				.isEqualTo("s3cr3t");
	}
}
