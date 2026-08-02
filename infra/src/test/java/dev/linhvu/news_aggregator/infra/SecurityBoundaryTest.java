package dev.linhvu.news_aggregator.infra;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class SecurityBoundaryTest {

	private Template oidcHub() {
		App app = new App();
		return Template.fromStack(new OidcHubStack(app, "OidcHubStack"));
	}

	/**
	 * Bốn role, KHÔNG phải một. Với một hub role duy nhất, trust policy buộc
	 * phải chấp nhận cả ba giá trị `environment`; và vì claim `environment`
	 * KHÔNG được mang theo qua bước STS role chaining, một job chạy cho `dev`
	 * sẽ chain sang spoke của prod được. Environment scoping bốc hơi đúng ở
	 * bước thứ hai. Xem ADR-0003 §7.
	 */
	@Test
	void co_dung_bon_role_huong_github() {
		oidcHub().resourceCountIs("AWS::IAM::Role", 4);
	}

	/**
	 * Trust policy phải ghim theo `environment:<env>`, và phải viết theo
	 * IMMUTABLE SUBJECT CLAIM — repo tạo sau 15/07/2026 nên GitHub tự động
	 * phát `sub` dạng repo:owner@<id>/repo@<id>:… (master §8.1).
	 */
	@Test
	void trust_policy_cua_prod_ghim_theo_environment_prod() {
		oidcHub().hasResourceProperties("AWS::IAM::Role", Match.objectLike(Map.of(
				"AssumeRolePolicyDocument", Match.objectLike(Map.of(
						"Statement", Match.arrayWith(List.of(
								Match.objectLike(Map.of(
										"Condition", Match.objectLike(Map.of(
												"StringEquals", Match.objectLike(Map.of(
														"token.actions.githubusercontent.com:sub",
														Match.stringLikeRegexp(".*:environment:prod$")
												))
										))
								))
						))
				))
		)));
	}
}
