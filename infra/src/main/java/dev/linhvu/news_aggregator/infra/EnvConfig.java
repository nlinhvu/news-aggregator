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
		String tagPrefix,

		/**
		 * ARN của web ACL do CloudFront TỰ TẠO khi account bật pricing plan.
		 *
		 * `null` nghĩa là account chưa bật plan nào — khi đó không khai báo
		 * `WebACLId` và CloudFront không đòi.
		 *
		 * Giá trị này KHÔNG biết trước lần create đầu tiên: CloudFront chỉ tạo
		 * web ACL khi distribution ra đời. Quy trình cho môi trường mới là để
		 * `null`, deploy lần đầu, rồi lấy ARN bằng
		 * `aws wafv2 list-web-acls --scope CLOUDFRONT` và điền vào đây. Bỏ bước
		 * đó thì lần deploy EdgeStack THỨ HAI sẽ fail, không phải lần đầu.
		 */
		String wafWebAclArn
) {

	public static final EnvConfig DEV = new EnvConfig(
			"Dev", "440783445107", "us-east-1",
			"na-dev.linhvu.dev", "news.na-dev.linhvu.dev",
			RemovalPolicy.DESTROY, false, "dev",
			"arn:aws:wafv2:us-east-1:440783445107:global/webacl/"
					+ "CreatedByCloudFront-7ba3b475/82fc786b-fdcc-412e-9c40-053693386dc1");

	public static final EnvConfig QA = new EnvConfig(
			"Qa", "517353742264", "us-east-1",
			"na-qa.linhvu.dev", "news.na-qa.linhvu.dev",
			RemovalPolicy.DESTROY, false, "qa", null);

	public static final EnvConfig PROD = new EnvConfig(
			"Prod", "778799435139", "us-east-1",
			"news.linhvu.dev", "news.linhvu.dev",
			RemovalPolicy.RETAIN, true, "prod", null);


	public static final String TOOLING_ACCOUNT = "237076104209";
	public static final String TOOLING_REGION = "us-east-1";
	public static final String ECR_REPOSITORY_NAME = "news-aggregator";

	public Environment awsEnvironment() {
		return Environment.builder().account(account).region(region).build();
	}
}
