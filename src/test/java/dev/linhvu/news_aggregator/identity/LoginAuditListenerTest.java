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

	/**
	 * Dòng audit, lọc theo nội dung chứ không phải theo vị trí. Listener có thể
	 * ghi nhiều dòng cho một lượt đăng nhập, và `singleElement()` biến việc thêm
	 * một dòng bất kỳ thành ba test đỏ ở chỗ không liên quan.
	 */
	private String auditLine() {
		return logEvents().stream()
				.filter(line -> line.startsWith("đăng nhập thành công"))
				.reduce((a, b) -> {
					throw new AssertionError("phải có ĐÚNG MỘT dòng audit mỗi lượt"
							+ " đăng nhập, thực tế: " + logEvents());
				})
				.orElseThrow(() -> new AssertionError(
						"không có dòng audit nào, thực tế: " + logEvents()));
	}

	@Test
	void dang_nhap_bang_email_otp_ghi_provider_cognito() {
		listener.onLoginSuccess(new AuthenticationSuccessEvent(
				token(Map.of("sub", "848814a8-4041-7031-3fff-9de5cdcb5e6c"))));

		assertThat(auditLine())
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

		assertThat(auditLine()).contains("provider=Google");
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

		// MỌI dòng, không chỉ dòng audit: listener ghi bao nhiêu dòng cũng được,
		// nhưng không dòng nào được mang email. Bản trước dùng `singleElement()`
		// nên nó chỉ canh đúng một dòng — thêm dòng thứ hai là lời hứa này hết
		// hiệu lực mà không có gì đỏ.
		assertThat(logEvents()).isNotEmpty()
				.allSatisfy(line -> assertThat(line)
						.doesNotContain("nguoi-doc@example.com").doesNotContain("@"));
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

	/**
	 * Chế độ hỏng ĐANG CÓ TRÊN DEV, và fixture cũ mù với nó: mọi fixture ở trên
	 * chỉ có MỘT identity, nên `identities.get(0)` luôn đúng một cách tình cờ.
	 *
	 * Sau Task 18B, profile gốc mang HAI identity và thứ tự mảng là thứ tự LIÊN
	 * KẾT, không phải thứ tự đăng nhập. Đo trên dev 2026-08-20: lượt Facebook ghi
	 * `provider=Google`, và lượt email OTP cũng vậy.
	 *
	 * Test này ĐỎ cho tới khi `providerOf(...)` được sửa. Nó ở đây để cái sai
	 * không im lặng nữa; bản đúng chờ kết quả đo claim thật.
	 */
	@Test
	@org.junit.jupiter.api.Disabled("đỏ có chủ ý — chờ số đo claim, xem plan Task 18B Step 5")
	void user_lien_ket_hai_identity_phai_ghi_dung_provider_cua_luot_nay() {
		listener.onLoginSuccess(new AuthenticationSuccessEvent(token(Map.of(
				"sub", "44f894c8-90a1-70d4-db21-e4b74f44aff3",
				"identities", List.of(
						Map.of("userId", "104556631362906539049",
								"providerName", "Google",
								"providerType", "Google",
								"primary", "false"),
						Map.of("userId", "122135814387161914",
								"providerName", "Facebook",
								"providerType", "Facebook",
								"primary", "false"))))));

		assertThat(auditLine()).contains("provider=Facebook");
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
