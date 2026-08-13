package dev.linhvu.news_aggregator.identity;

import java.util.List;

import dev.linhvu.news_aggregator.platform.RoleProfiles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;


@RestController
@Profile(RoleProfiles.HTTP)
class AuthController {

	private final String logoutUri;
	private final String publicBaseUrl;

	AuthController(@Value("${news.identity.cognito.logout-uri}") String logoutUri,
			@Value("${news.identity.public-base-url}") String publicBaseUrl) {
		this.logoutUri = logoutUri;
		this.publicBaseUrl = publicBaseUrl;
	}

	/**
	 * Spring Security đặt entry point thật ở `/api/auth/login/{registrationId}`.
	 * Endpoint này tồn tại để SPA không phải biết `registrationId` là gì — và để
	 * bề mặt API khớp đúng thứ TDD §7 công bố.
	 *
	 * URL TUYỆT ĐỐI dựng từ `public-base-url`, không phải đường dẫn tương đối.
	 * Đường dẫn tương đối được servlet container biến thành tuyệt đối bằng host
	 * của REQUEST, mà sau CloudFront host đó là Function URL với
	 * `AuthType=AWS_IAM` — trình duyệt không ký SigV4 được nên nhận 403. Đã ĐO
	 * trên dev 2026-08-13; đó cũng là lý do không cậy vào
	 * `server.tomcat.use-relative-redirects`: hành vi ấy là của container và
	 * MockMvc không đi qua container nên test sẽ không bao giờ nói thật về nó.
	 */
	@GetMapping("/api/auth/login")
	RedirectView login() {
		return new RedirectView(publicBaseUrl + "/api/auth/login/"
				+ SsmClientRegistrationRepository.REGISTRATION_ID);
	}

	@GetMapping("/api/me")
	ResponseEntity<CurrentUserDto> me(@AuthenticationPrincipal OidcUser user) {
		if (user == null) {
			return ResponseEntity.status(401).build();
		}
		List<String> groups = user.getClaimAsStringList("cognito:groups");
		return ResponseEntity.ok(new CurrentUserDto(
				user.getSubject(),
				user.getEmail(),
				groups == null ? List.of() : groups));
	}

	/**
	 * Hai vế, và bỏ vế nào cũng làm nút đăng xuất nói dối:
	 *   1. Xoá phiên phía ta ⇒ cookie thành vô nghĩa ngay lập tức.
	 *   2. Redirect sang `/logout` của Cognito ⇒ phiên phía Cognito cũng chết.
	 * Thiếu vế 2 thì lần "Đăng nhập" kế tiếp vào thẳng, không hỏi gì — trông y
	 * hệt một nút đăng xuất hỏng.
	 */
	/**
	 * Trả URL đăng xuất của Cognito dưới dạng JSON, KHÔNG redirect.
	 *
	 * Bản đầu trả `RedirectView` và SPA gọi bằng form POST để trình duyệt đi
	 * theo redirect. Cách đó CHẾT trên AWS, và chết trước khi tới ứng dụng:
	 *
	 * <pre>
	 *   POST có body    → {"message":"The request signature we calculated does
	 *                      not match the signature you provided…"}
	 *   POST không body → tới được ứng dụng
	 * </pre>
	 *
	 * CloudFront OAC KHÔNG ký request body khi origin là Lambda Function URL,
	 * nên mọi request mang body đều trượt chữ ký SigV4. Form POST luôn có body
	 * (field `_csrf`), `fetch` không body thì không.
	 *
	 * Vì `fetch` không đọc được header `Location` của một redirect (response là
	 * opaque), URL phải đi trong THÂN response. Đây cũng là hình dạng đúng hơn
	 * cho một API mà người gọi là SPA: một redirect HTML nhét vào chỗ chờ JSON
	 * chưa bao giờ là câu trả lời tốt.
	 */
	@PostMapping("/api/auth/logout")
	LogoutResponse logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			// Với Spring Session, `invalidate()` gọi thẳng
			// `SessionRepository.deleteById` — tức DeleteItem trên bảng
			// `sessions`, đúng cái quyền `webRole` được cấp và là lý do nó có
			// `dynamodb:DeleteItem`.
			session.invalidate();
		}
		return new LogoutResponse(logoutUri);
	}

	record CurrentUserDto(String sub, String email, List<String> groups) {
	}

	record LogoutResponse(String logoutUrl) {
	}
}
