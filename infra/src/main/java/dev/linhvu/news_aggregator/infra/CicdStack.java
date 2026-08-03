package dev.linhvu.news_aggregator.infra;

import java.util.List;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudfront.IDistribution;
import software.amazon.awscdk.services.iam.ArnPrincipal;
import software.amazon.awscdk.services.iam.IPrincipal;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.s3.IBucket;
import software.constructs.Construct;

public class CicdStack extends Stack {

	public CicdStack(final Construct scope, final String id, final EnvConfig cfg,
			final IFunction function, final IBucket bucket,
			final IDistribution distribution) {
		super(scope, id, StackProps.builder().env(cfg.awsEnvironment()).build());

		IPrincipal hub = new ArnPrincipal("arn:aws:iam::" + EnvConfig.TOOLING_ACCOUNT
				+ ":role/GhaHubRole-" + cfg.name());

		Role appDeploy = Role.Builder.create(this, "AppDeployRole")
				.roleName("AppDeployRole")
				.assumedBy(hub)
				.build();
		appDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("lambda:UpdateFunctionCode", "lambda:GetFunction"))
				.resources(List.of(function.getFunctionArn()))
				.build());
		appDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("ssm:PutParameter", "ssm:GetParameter"))
				.resources(List.of("arn:aws:ssm:" + cfg.region() + ":" + cfg.account()
						+ ":parameter/news/" + cfg.tagPrefix() + "/image-digest"))
				.build());

		Role webDeploy = Role.Builder.create(this, "WebDeployRole")
				.roleName("WebDeployRole")
				.assumedBy(hub)
				.build();
		// KHÔNG dùng `bucket.grantReadWrite()`: nó cấp action dạng wildcard
		// (`s3:GetObject*`, `s3:List*`, `s3:DeleteObject*`…) và kéo theo cả
		// `s3:PutObjectVersionAcl`. Đây là đúng bộ action mà `aws s3 sync
		// --delete` cần, liệt kê tường minh — cdk-nag AwsSolutions-IAM5 bắt
		// wildcard action, và ở đây nó bắt đúng.
		webDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("s3:ListBucket"))
				.resources(List.of(bucket.getBucketArn()))
				.build());
		webDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("s3:GetObject", "s3:PutObject", "s3:DeleteObject",
						"s3:AbortMultipartUpload"))
				.resources(List.of(bucket.getBucketArn() + "/*"))
				.build());
		webDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("cloudfront:CreateInvalidation"))
				.resources(List.of("arn:aws:cloudfront::" + cfg.account()
						+ ":distribution/" + distribution.getDistributionId()))
				.build());
	}
}
