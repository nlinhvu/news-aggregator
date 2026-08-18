package dev.linhvu.news_aggregator.identity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Đường đăng nhập nào đang được dùng là câu hỏi KHÔNG trả lời được bằng hạ tầng:
 * Cognito chỉ ghi log hoạt động đăng nhập ở tier trả tiền, và bảng `sessions`
 * không lưu provider. Nếu ứng dụng không log thì không ai đếm được, và walkthrough
 * slice 3 có đúng một ô cho việc này (TDD §14.2).
 */
class LoginAuditListenerTest {

	private final LoginAuditListener listener = new LoginAuditListener();

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void batLog() {
		logs.start();
		((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LoginAuditListener.class))
				.addAppender(logs);
	}

	@AfterEach
	void thaLog() {
		((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LoginAuditListener.class))
				.detachAppender(logs);
		logs.stop();
	}

	private List<String> logEvents() {
		return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Test
	void dang_nhap_bang_email_otp_ghi_provider_cognito() {
		listener.onLoginSuccess(new AuthenticationSuccessEvent(
				token(Map.of("sub", "848814a8-4041-7031-3fff-9de5cdcb5e6c"))));

		assertThat(logEvents()).singleElement().asString()
				.contains("848814a8-4041-7031-3fff-9de5cdcb5e6c")
				.contains("provider=cognito");
	}

	/**
	 * `identities` chỉ có ở user liên kết từ IdP ngoài, và tên provider nằm
	 * TRONG claim đó — suy từ `registrationId` là ra `cognito` cho cả ba đường,
	 * tức đếm được tổng số lượt đăng nhập mà không biết đường nào.
	 */
	@Test
	void dang_nhap_bang_social_ghi_ten_provider_lay_tu_claim_identities() {
		listener.onLoginSuccess(new AuthenticationSuccessEvent(token(Map.of(
				"sub", "c4d8f4a8-1234-70e5-afc4-0beffd64149d",
				"identities", List.of(Map.of(
						"userId", "117…",
						"providerName", "Google",
						"providerType", "Google",
						"primary", "true"))))));

		assertThat(logEvents()).singleElement().asString().contains("provider=Google");
	}

	/**
	 * TDD §14.2 liệt kê email vào danh sách KHÔNG BAO GIỜ log, cùng chỗ với token
	 * và client secret. Module này cố tình không sở hữu email (package-info), nên
	 * một dòng log là đường rò duy nhất còn lại.
	 */
	@Test
	void khong_bao_gio_log_email() {
		listener.onLoginSuccess(new AuthenticationSuccessEvent(token(Map.of(
				"sub", "848814a8-4041-7031-3fff-9de5cdcb5e6c",
				"email", "nguoi-doc@example.com"))));

		assertThat(logEvents()).singleElement().asString()
				.doesNotContain("nguoi-doc@example.com").doesNotContain("@");
	}

	/**
	 * `AuthenticationSuccessEvent` bắn cho MỌI kiểu xác thực. Ném exception ở
	 * listener là ném vào giữa luồng đăng nhập — người dùng mất phiên vì một dòng
	 * log.
	 */
	@Test
	void xac_thuc_khong_phai_oidc_thi_im_lang_chu_khong_no() {
		listener.onLoginSuccess(new AuthenticationSuccessEvent(
				new AnonymousAuthenticationToken("key", "anonymous",
						AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));

		assertThat(logEvents()).isEmpty();
	}

	private static OAuth2AuthenticationToken token(Map<String, Object> claims) {
		OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(),
				Instant.now().plusSeconds(3600), claims);
		return new OAuth2AuthenticationToken(
				new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken),
				AuthorityUtils.NO_AUTHORITIES,
				SsmClientRegistrationRepository.REGISTRATION_ID);
	}
}
