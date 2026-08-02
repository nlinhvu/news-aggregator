package dev.linhvu.news_aggregator.infra;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.LifecycleRule;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.amazon.awscdk.services.ecr.TagStatus;
import software.amazon.awscdk.services.iam.ArnPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.IPrincipal;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class RegistryStack extends Stack {

	public RegistryStack(final Construct scope, final String id) {
		this(scope, id, null);
	}

	public RegistryStack(final Construct scope, final String id, final StackProps props) {
		super(scope, id, props);

		List<EnvConfig> envs = List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD);
		List<String> accountArns = envs.stream()
				.map(c -> "arn:aws:iam::" + c.account() + ":root").toList();
		List<String> functionArns = envs.stream()
				.map(c -> "arn:aws:lambda:" + c.region() + ":" + c.account() + ":function:*")
				.toList();

		Repository repo = Repository.Builder.create(this, "Repository")
				.repositoryName(EnvConfig.ECR_REPOSITORY_NAME)
				.imageTagMutability(TagMutability.IMMUTABLE)
				.removalPolicy(RemovalPolicy.RETAIN)
				.lifecycleRules(List.of(
						LifecycleRule.builder().rulePriority(1)
								.tagPrefixList(List.of("prod-"))
								.maxImageCount(5).build(),
						LifecycleRule.builder().rulePriority(2)
								.tagPrefixList(List.of("qa-"))
								.maxImageCount(10).build(),
						LifecycleRule.builder().rulePriority(3)
								.tagPrefixList(List.of("dev-"))
								.maxImageCount(20).build(),
						LifecycleRule.builder().rulePriority(4)
								.tagStatus(TagStatus.UNTAGGED)
								.maxImageAge(Duration.days(1)).build()))
				.build();

		repo.addToResourcePolicy(PolicyStatement.Builder.create()
				.sid("CrossAccountPermission")
				.effect(Effect.ALLOW)
				.actions(List.of("ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"))
				.principals(accountArns.stream()
						.map(a -> (IPrincipal) new ArnPrincipal(a)).toList())
				.build());

		repo.addToResourcePolicy(PolicyStatement.Builder.create()
				.sid("LambdaECRImageCrossAccountRetrievalPolicy")
				.effect(Effect.ALLOW)
				.actions(List.of("ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"))
				.principals(List.of(new ServicePrincipal("lambda.amazonaws.com")))
				.conditions(Map.of("ArnLike", Map.of("aws:sourceArn", functionArns)))
				.build());

		CfnOutput.Builder.create(this, "RepositoryUri")
				.value(repo.getRepositoryUri()).build();
	}
}
