package dev.linhvu.news_aggregator.identity;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.linhvu.news_aggregator.platform.RoleProfiles;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.stereotype.Component;

/**
 * Biến claim `cognito:groups` thành authority `ROLE_<tên nhóm>`.
 *
 * <p><b>Không có bean này, `hasAuthority("ROLE_ops")` KHÔNG BAO GIỜ đúng — kể
 * cả khi token mang đúng claim.</b> Spring Security dựng authority của một
 * người dùng OIDC từ scope (`SCOPE_openid`, `SCOPE_email`…) và `OIDC_USER`; nó
 * không đọc claim tuỳ biến nào, vì không có claim nào là chuẩn cho "nhóm".
 *
 * <p>Đây là chế độ hỏng IM LẶNG: không lỗi nào nổ ra, không log nào bất thường,
 * chỉ là người vận hành luôn nhận 403 ở console. Và mọi chỗ đi tìm đều SAI mà
 * vẫn hợp lý — quyền IAM của function, thành viên nhóm trong Cognito, cấu hình
 * pool — trước khi ai đó nghĩ tới đoạn ánh xạ này.
 *
 * <p><b>Tiền tố `ROLE_` là bắt buộc và nó KHÔNG do ta chọn.</b> Cognito đặt tên
 * nhóm là `ops`; `hasRole("ops")` của Spring Security tự thêm `ROLE_` trước khi
 * so, còn `hasAuthority` thì so nguyên văn. Ánh xạ ở đây thêm tiền tố một lần,
 * tại một chỗ, để hai cách viết đó không còn là hai câu trả lời khác nhau.
 *
 * <p><b>Giữ NGUYÊN authority cũ.</b> Vứt `SCOPE_*` và `OIDC_USER` đi sẽ không
 * có triệu chứng hôm nay — không chỗ nào trong hệ này kiểm chúng — rồi đúng lúc
 * ai đó viết `hasAuthority("SCOPE_email")` thì nó sai một cách không giải thích
 * được.
 */
@Component
@Profile(RoleProfiles.HTTP)
class OpsAuthoritiesMapper implements GrantedAuthoritiesMapper {

	/**
	 * Cognito phát tên claim đúng như thế này, kèm dấu hai chấm. Nó KHÔNG phải
	 * claim chuẩn của OIDC, nên không hằng số nào của Spring Security có nó.
	 */
	static final String GROUPS_CLAIM = "cognito:groups";

	static final String ROLE_PREFIX = "ROLE_";

	@Override
	public Collection<? extends GrantedAuthority> mapAuthorities(
			Collection<? extends GrantedAuthority> authorities) {
		Set<GrantedAuthority> mapped = new LinkedHashSet<>(authorities);
		for (GrantedAuthority authority : authorities) {
			if (authority instanceof OidcUserAuthority oidc) {
				groupsOf(oidc).forEach(group ->
						mapped.add(new SimpleGrantedAuthority(ROLE_PREFIX + group)));
			}
		}
		return mapped;
	}

	/**
	 * Đọc từ ID token, KHÔNG từ userinfo.
	 *
	 * <p>`OidcUserAuthority` mang cả hai, và `getUserInfo()` là `null` khi
	 * provider không có userinfo endpoint hoặc client không gọi tới. Cognito CÓ
	 * userinfo, nhưng response của nó KHÔNG chứa `cognito:groups` — chỉ ID token
	 * chứa. Đọc nhầm nguồn cho ra một danh sách rỗng, tức đúng cái 403 im lặng
	 * mà class này tồn tại để chặn.
	 *
	 * <p>Claim vắng mặt là chuyện BÌNH THƯỜNG, không phải lỗi: người dùng thường
	 * không thuộc nhóm nào và Cognito bỏ hẳn claim thay vì phát mảng rỗng.
	 */
	private static List<String> groupsOf(OidcUserAuthority authority) {
		List<String> groups = authority.getIdToken().getClaimAsStringList(GROUPS_CLAIM);
		return groups == null ? List.of() : groups;
	}
}
