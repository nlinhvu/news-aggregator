package dev.linhvu.news_aggregator.infra;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.RemovalPolicy;

public record EnvConfig(
		String name,
		String account,
		String region,
		String zoneName,
		String appDomain,
		RemovalPolicy removalPolicy,
		boolean terminationProtection,
		String tagPrefix
) {

	public static final EnvConfig DEV = new EnvConfig(
			"Dev", "440783445107", "us-east-1",
			"na-dev.linhvu.dev", "news.na-dev.linhvu.dev",
			RemovalPolicy.DESTROY, false, "dev");

	public static final EnvConfig QA = new EnvConfig(
			"Qa", "517353742264", "us-east-1",
			"na-qa.linhvu.dev", "news.na-qa.linhvu.dev",
			RemovalPolicy.DESTROY, false, "qa");

	public static final EnvConfig PROD = new EnvConfig(
			"Prod", "778799435139", "us-east-1",
			"news.linhvu.dev", "news.linhvu.dev",
			RemovalPolicy.RETAIN, true, "prod");


	public static final String TOOLING_ACCOUNT = "237076104209";
	public static final String TOOLING_REGION = "us-east-1";
	public static final String ECR_REPOSITORY_NAME = "news-aggregator";

	public Environment awsEnvironment() {
		return Environment.builder().account(account).region(region).build();
	}
}
