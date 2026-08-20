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
 * Một dòng log cho mỗi lượt đăng nhập thành công — `sub`, và danh sách IdP đã
 * liên kết vào tài khoản đó (TDD §14.2).
 *
 * <h2>Vì sao KHÔNG có `provider` của lượt đăng nhập này</h2>
 *
 * Vì ID token không mang thông tin đó. Đo trên dev 2026-08-20 với ba lượt đăng
 * nhập Google, Facebook và email OTP của CÙNG một tài khoản đã liên kết:
 *
 * <pre>
 *   amr               → không tồn tại ở cả ba lượt
 *   identities        → [Google, Facebook] ở CẢ BA
 *   cognito:username  → cùng một UUID ở cả ba
 *   khác biệt duy nhất → `event_id` chỉ có ở lượt native
 * </pre>
 *
 * Lý do có thật: liên kết tài khoản làm cả ba đường cùng đăng nhập vào MỘT
 * profile, và token mô tả PROFILE chứ không mô tả lượt đăng nhập. Claim
 * `identities` là attribute của profile — nó trả lời *"tài khoản này đã nối với
 * những IdP nào"*, không phải *"lần này vào bằng đường nào"*.
 *
 * Bản trước đọc `identities.get(0)` và gọi nó là provider. Đúng một cách tình cờ
 * khi mỗi profile có đúng một identity; từ khi liên kết tài khoản được bật
 * (ADR-0021), profile gốc mang nhiều identity xếp theo thứ tự LIÊN KẾT, nên cả
 * ba đường đều bị ghi thành `provider=Google`.
 *
 * <h2>Provider của một lượt đăng nhập đọc ở đâu</h2>
 *
 * Ở log của hàm `account-linking` (log group `Dev-IdentityStack-AccountLinking…`):
 * nó chạy đồng bộ trong MỌI lượt federated sign-in và biết chắc `providerName`.
 * `userName` nó ghi CHÍNH LÀ `sub` ở đây, nên hai log group nối được bằng
 * `sub` + mốc thời gian:
 *
 * <pre>
 *   có dòng của account-linking  → provider là giá trị trong dòng đó
 *   KHÔNG có dòng nào            → đăng nhập native (email OTP hoặc passkey)
 * </pre>
 *
 * Đếm bằng CloudWatch Logs Insights; metric `identity.login.total{provider}` của
 * TDD §14.2 phải đợi một `MeterRegistry`, mà bean đó đi kèm actuator — thứ TDD
 * §17 #6 cố ý không lấy.
 */
@Component
@Profile(RoleProfiles.HTTP)
class LoginAuditListener {

	private static final Logger log = LoggerFactory.getLogger(LoginAuditListener.class);

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
		log.info("đăng nhập thành công sub={} linkedIdps={}",
				user.getSubject(), linkedIdps(user));
	}

	/**
	 * Tên các IdP đã nối vào tài khoản này, rỗng với tài khoản chưa nối IdP nào.
	 *
	 * Tên biến và tên field trong log CỐ Ý dài: `provider` là cái tên đã làm bản
	 * trước sai suốt, vì nó đọc như *"đường đăng nhập lần này"*.
	 */
	private static List<String> linkedIdps(OidcUser user) {
		if (!(user.getClaim("identities") instanceof List<?> identities)) {
			return List.of();
		}
		return identities.stream()
				.filter(Map.class::isInstance)
				.map(identity -> ((Map<?, ?>) identity).get("providerName"))
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.toList();
	}
}
