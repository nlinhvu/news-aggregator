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

	AuthController(@Value("${news.identity.cognito.logout-uri}") String logoutUri) {
		this.logoutUri = logoutUri;
	}

	/**
	 * Spring Security đặt entry point thật ở `/api/auth/login/{registrationId}`.
	 * Endpoint này tồn tại để SPA không phải biết `registrationId` là gì — và để
	 * bề mặt API khớp đúng thứ TDD §7 công bố.
	 */
	@GetMapping("/api/auth/login")
	RedirectView login() {
		return new RedirectView("/api/auth/login/"
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
	@PostMapping("/api/auth/logout")
	RedirectView logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			// Với Spring Session, `invalidate()` gọi thẳng
			// `SessionRepository.deleteById` — tức DeleteItem trên bảng
			// `sessions`, đúng cái quyền `webRole` được cấp và là lý do nó có
			// `dynamodb:DeleteItem`.
			session.invalidate();
		}
		return new RedirectView(logoutUri);
	}

	record CurrentUserDto(String sub, String email, List<String> groups) {
	}
}
