package dev.linhvu.news_aggregator.infra;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.iam.CfnOIDCProvider;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.WebIdentityPrincipal;
import software.constructs.Construct;

public class OidcHubStack extends Stack {

	private static final String REPO = "nlinhvu/news-aggregator";
	private static final String OIDC_HOST = "token.actions.githubusercontent.com";

	public OidcHubStack(final Construct scope, final String id) {
		this(scope, id, null);
	}

	public OidcHubStack(final Construct scope, final String id, final StackProps props) {
		super(scope, id, props);

		CfnOIDCProvider provider = CfnOIDCProvider.Builder
				.create(this, "GithubOidcProvider")
				.url("https://" + OIDC_HOST)
				.clientIdList(List.of("sts.amazonaws.com"))
				.build();

		Role buildRole = githubRole(provider, "GhaBuildRole",
				"repo:" + REPO + ":ref:refs/heads/main");

		IRepository repo = Repository.fromRepositoryName(
				this, "AppRepository", EnvConfig.ECR_REPOSITORY_NAME);
		repo.grantPush(buildRole);
		// `grantPush` dừng lại ở các action GHI. Nhưng `app-deploy.yml` còn phải
		// ĐỌC lại image vừa push — lấy digest để ba môi trường promote đúng một
		// image, và kiểm tra tag đã tồn tại chưa để re-run không đâm vào tag
		// IMMUTABLE. Không grant helper nào của CDK gộp đủ hai nhu cầu đó, nên
		// action này phải thêm tường minh.
		buildRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("ecr:DescribeImages"))
				.resources(List.of(repo.getRepositoryArn()))
				.build());

		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			String env = cfg.tagPrefix();
			Role hub = githubRole(provider, "GhaHubRole-" + cfg.name(),
					"repo:" + REPO + ":environment:" + env);

			hub.addToPolicy(PolicyStatement.Builder.create()
					.actions(List.of("sts:AssumeRole", "sts:TagSession"))
					.resources(List.of(
							"arn:aws:iam::" + cfg.account() + ":role/cdk-*",
							"arn:aws:iam::" + cfg.account() + ":role/AppDeployRole",
							"arn:aws:iam::" + cfg.account() + ":role/WebDeployRole"))
					.build());

			CfnOutput.Builder.create(this, "HubRoleArn" + cfg.name())
					.value(hub.getRoleArn()).build();
		}

		CfnOutput.Builder.create(this, "BuildRoleArn")
				.value(buildRole.getRoleArn()).build();
	}

	private Role githubRole(CfnOIDCProvider provider, String id, String subPrefix) {
		String[] parts = subPrefix.split(":", 3);
		String repoPart = parts[1];
		String suffix = parts[2];
		String owner = repoPart.split("/")[0];
		String repo = repoPart.split("/")[1];
		String immutableSub = "repo:" + owner + "@*/" + repo + "@*:" + suffix;

		Map<String, Object> conditions = Map.of(
				"StringEquals", Map.of(OIDC_HOST + ":aud", "sts.amazonaws.com"),
				"StringLike", Map.of(OIDC_HOST + ":sub", List.of(subPrefix, immutableSub)));

		return Role.Builder.create(this, id)
				.roleName(id)
				.maxSessionDuration(software.amazon.awscdk.Duration.hours(1))
				.assumedBy(new WebIdentityPrincipal(provider.getAttrArn(), conditions))
				.build();
	}
}
