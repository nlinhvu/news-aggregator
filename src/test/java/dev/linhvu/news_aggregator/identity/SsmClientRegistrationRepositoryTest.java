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
	void khong_goi_ssm_cho_toi_khi_co_nguoi_dang_nhap() {
		// Đây là toàn bộ lý do class này tồn tại. `ClientRegistrationRepository`
		// là bean EAGER — nó được dựng lúc tạo security filter chain, tức là ở
		// MỌI cold start, kể cả invoke của người đọc ẩn danh. Nếu nó đọc SSM
		// trong constructor, mọi người đọc ẩn danh trả tiền cho một lời gọi
		// mạng mà họ không dùng, và cold start dày thêm mà không ai truy ra lý do.
		SsmClient ssm = mock(SsmClient.class);

		new SsmClientRegistrationRepository(ssm, "/news/test/cognito-client-secret",
				"https://issuer.example/pool", "client-abc");

		verifyNoInteractions(ssm);
	}

	@Test
	void goi_ssm_dung_mot_lan_du_tim_nhieu_lan() {
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s3cr3t").build())
						.build());

		SsmClientRegistrationRepository repo = new SsmClientRegistrationRepository(
				ssm, "/news/test/cognito-client-secret",
				"https://issuer.example/pool", "client-abc");

		ClientRegistration first = repo.findByRegistrationId("cognito");
		ClientRegistration second = repo.findByRegistrationId("cognito");

		assertThat(first.getClientSecret()).isEqualTo("s3cr3t");
		assertThat(second).isSameAs(first);
		verify(ssm, times(1)).getParameter(any(GetParameterRequest.class));
	}

	@Test
	void giai_ma_secure_string_chu_khong_doc_tho() {
		// Thiếu `withDecryption(true)` thì SSM trả về BẢN MÃ, và client secret
		// sai thì Cognito từ chối ở bước đổi code lấy token — một lỗi OAuth
		// tối nghĩa, xa chỗ gây ra nó.
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s").build()).build());

		new SsmClientRegistrationRepository(ssm, "/p", "https://issuer.example/pool",
				"client-abc").findByRegistrationId("cognito");

		verify(ssm).getParameter(GetParameterRequest.builder()
				.name("/p").withDecryption(true).build());
	}

	@Test
	void registration_id_la_cognito_va_redirect_uri_nam_trong_api() {
		// Đường callback PHẢI nằm trong /api/* — CloudFront route `/*` sang S3,
		// nên đường mặc định `/login/oauth2/code/*` của Spring Security sẽ rơi
		// vào bucket SPA và trả 404 (TDD §7).
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s").build()).build());

		SsmClientRegistrationRepository repo = new SsmClientRegistrationRepository(
				ssm, "/p", "https://issuer.example/pool", "client-abc");
		ClientRegistration reg = repo.findByRegistrationId("cognito");

		assertThat(reg.getRedirectUri())
				.isEqualTo("{baseUrl}/api/auth/callback/{registrationId}");
		assertThat(reg.getScopes()).containsExactlyInAnyOrder("openid", "email");
		assertThat(repo.findByRegistrationId("khong-ton-tai")).isNull();
	}

	@Test
	void khong_nho_lan_that_bai() {
		// Cùng khuôn với `FailClosedDynamoDbStateRepository` của Phase 1: nhớ
		// thành công, QUÊN thất bại. Cache lần hỏng nghĩa là một lượt SSM lỗi
		// làm đăng nhập chết vĩnh viễn cho tới lần deploy sau.
		SsmClient ssm = mock(SsmClient.class);
		when(ssm.getParameter(any(GetParameterRequest.class)))
				.thenThrow(new RuntimeException("SSM tạm thời hỏng"))
				.thenReturn(GetParameterResponse.builder()
						.parameter(Parameter.builder().value("s3cr3t").build()).build());

		SsmClientRegistrationRepository repo = new SsmClientRegistrationRepository(
				ssm, "/p", "https://issuer.example/pool", "client-abc");

		assertThatThrownBy(() -> repo.findByRegistrationId("cognito"))
				.isInstanceOf(RuntimeException.class);
		assertThat(repo.findByRegistrationId("cognito").getClientSecret())
				.isEqualTo("s3cr3t");
	}
}
