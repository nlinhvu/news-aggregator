package dev.linhvu.news_aggregator.identity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ánh xạ nhóm là chỗ DUY NHẤT trong hệ quyết định ai vào được mặt phẳng vận
 * hành, và nó không có triệu chứng khi sai — mọi lỗi ở đây hiện ra là một 403
 * trông y hệt "người này thật sự không thuộc nhóm".
 *
 * <p>Test này KHÔNG thay được vế đầu-cuối ở {@code AdminConsoleIT}: ở đó token
 * do IdP thật phát, nên nó chứng minh luôn rằng tên claim ta đọc đúng là tên
 * claim Cognito phát. Ở đây kiểm các nhánh mà một luồng đăng nhập không dựng
 * được — claim vắng, nhóm khác, nhiều nhóm.
 */
class OpsAuthoritiesMapperTest {

	private final OpsAuthoritiesMapper mapper = new OpsAuthoritiesMapper();

	@Test
	void the_ops_group_becomes_an_authority_with_the_ROLE_prefix() {
		assertThat(mapGroups(List.of("ops")))
				.extracting(GrantedAuthority::getAuthority)
				.contains("ROLE_ops");
	}

	@Test
	void keeps_the_authorities_that_are_already_there() {
		// Vứt `SCOPE_*`/`OIDC_USER` đi không có triệu chứng hôm nay, rồi sai một
		// cách không giải thích được vào ngày ai đó viết `hasAuthority("SCOPE_email")`.
		assertThat(mapper.mapAuthorities(List.of(new SimpleGrantedAuthority("SCOPE_email"))))
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("SCOPE_email");
	}

	@Test
	void no_claim_produces_no_role_at_all() {
		// Người dùng thường KHÔNG thuộc nhóm nào, và Cognito bỏ hẳn claim thay vì
		// phát mảng rỗng. Ném NPE ở đây nghĩa là mọi lượt đăng nhập bình thường
		// đều chết.
		OidcIdToken token = OidcIdToken.withTokenValue("t")
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
				.subject("reader").build();

		assertThat(mapper.mapAuthorities(List.of(new OidcUserAuthority(token))))
				.extracting(GrantedAuthority::getAuthority)
				.noneMatch(a -> a.startsWith(OpsAuthoritiesMapper.ROLE_PREFIX));
	}

	@Test
	void other_groups_do_not_turn_into_ops() {
		// Vế phủ định đáng giá nhất: một ánh xạ "có nhóm nào cũng thành ops" sẽ
		// xanh ở test đầu tiên và mở cửa cho mọi người có nhóm bất kỳ.
		assertThat(mapGroups(List.of("readers", "beta")))
				.extracting(GrantedAuthority::getAuthority)
				.contains("ROLE_readers", "ROLE_beta")
				.doesNotContain("ROLE_ops");
	}

	private java.util.Collection<? extends GrantedAuthority> mapGroups(List<String> groups) {
		OidcIdToken token = OidcIdToken.withTokenValue("t")
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
				.subject("operator")
				.claim(OpsAuthoritiesMapper.GROUPS_CLAIM, groups)
				.build();
		return mapper.mapAuthorities(List.of(new OidcUserAuthority(token)));
	}

	/** Cognito phát claim này trong ID TOKEN; userinfo của nó không có. */
	@Test
	void reads_from_the_id_token_not_from_userinfo() {
		OidcIdToken idToken = OidcIdToken.withTokenValue("t")
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
				.subject("operator")
				.claim(OpsAuthoritiesMapper.GROUPS_CLAIM, List.of("ops"))
				.build();
		// userinfo CÓ mặt nhưng KHÔNG mang claim nhóm — đúng hình dạng của Cognito.
		var userInfo = new org.springframework.security.oauth2.core.oidc.OidcUserInfo(
				Map.of("sub", "operator"));

		assertThat(mapper.mapAuthorities(List.of(new OidcUserAuthority(idToken, userInfo))))
				.extracting(GrantedAuthority::getAuthority)
				.contains("ROLE_ops");
	}
}
