package dev.linhvu.news_aggregator.identity;

import java.util.List;
import java.util.Map;

import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Một dòng log cho mỗi lượt đăng nhập thành công — `sub` và provider, không gì
 * khác (TDD §14.2).
 *
 * Đây là chỗ DUY NHẤT biết được đường nào đang được dùng. Cognito chỉ ghi log
 * hoạt động đăng nhập ở tier trả tiền, bảng `sessions` khoá theo `sub` và không
 * lưu provider, còn `registrationId` phía Spring là `cognito` cho cả ba đường vì
 * ứng dụng chỉ nói chuyện với user pool. Tên provider thật nằm trong claim
 * `identities` của ID token, và chỉ user liên kết từ IdP ngoài mới có claim đó.
 *
 * Đếm bằng CloudWatch Logs Insights; metric `identity.login.total{provider}` của
 * TDD §14.2 phải đợi một `MeterRegistry`, mà bean đó đi kèm actuator — thứ TDD
 * §17 #6 cố ý không lấy.
 */
@Component
@Profile(RoleProfiles.HTTP)
class LoginAuditListener {

	private static final Logger log = LoggerFactory.getLogger(LoginAuditListener.class);

	/** Người dùng của chính user pool: đăng nhập bằng email OTP hoặc passkey. */
	private static final String NATIVE_PROVIDER = "cognito";

	/**
	 * `AuthenticationSuccessEvent` bắn cho MỌI kiểu xác thực, nên `instanceof`
	 * là chốt chặn thật chứ không phải phòng thủ thừa: một `ClassCastException`
	 * ở đây ném vào giữa luồng đăng nhập và người dùng mất phiên vì một dòng log.
	 */
	@EventListener
	void onLoginSuccess(AuthenticationSuccessEvent event) {
		if (!(event.getAuthentication().getPrincipal() instanceof OidcUser user)) {
			return;
		}
		// KHÔNG log email: nó nằm cùng danh sách cấm với token và client secret
		// (TDD §14.2), và module này cố ý không sở hữu email.
		log.info("đăng nhập thành công sub={} provider={}", user.getSubject(), providerOf(user));
	}

	private static String providerOf(OidcUser user) {
		if (user.getClaim("identities") instanceof List<?> identities
				&& !identities.isEmpty()
				&& identities.get(0) instanceof Map<?, ?> primary
				&& primary.get("providerName") instanceof String providerName) {
			return providerName;
		}
		return NATIVE_PROVIDER;
	}
}
