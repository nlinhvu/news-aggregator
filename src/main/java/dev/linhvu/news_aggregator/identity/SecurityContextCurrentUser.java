package dev.linhvu.news_aggregator.identity;

import java.util.Optional;

import dev.linhvu.news_aggregator.identity.api.CurrentUser;
import dev.linhvu.news_aggregator.platform.RoleProfiles;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Đọc `sub` từ `SecurityContextHolder`, KHÔNG từ tham số request.
 *
 * Đây là chốt chặn IDOR của cả phase: nếu một endpoint nhận `userId` từ query
 * param hay body, người dùng A đọc được lựa chọn của người dùng B. Không có
 * cách nào để điều đó xảy ra khi nguồn duy nhất của `sub` là phiên phía server.
 */
@Component
@Profile(RoleProfiles.HTTP)
class SecurityContextCurrentUser implements CurrentUser {

	@Override
	public Optional<String> sub() {
		var auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof OidcUser user)) {
			return Optional.empty();   // ẩn danh — KHÔNG ném exception
		}
		return Optional.of(user.getSubject());
	}
}
