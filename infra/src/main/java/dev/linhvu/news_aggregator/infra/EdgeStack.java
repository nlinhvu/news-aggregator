package dev.linhvu.news_aggregator.infra;

import java.util.List;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.cloudfront.AllowedMethods;
import software.amazon.awscdk.services.cloudfront.BehaviorOptions;
import software.amazon.awscdk.services.cloudfront.CachePolicy;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.OriginRequestPolicy;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.origins.FunctionUrlOrigin;
import software.amazon.awscdk.services.cloudfront.origins.S3BucketOrigin;
import software.amazon.awscdk.services.lambda.CfnPermission;
import software.amazon.awscdk.services.lambda.FunctionUrl;
import software.amazon.awscdk.services.route53.ARecord;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.amazon.awscdk.services.route53.RecordTarget;
import software.amazon.awscdk.services.route53.targets.CloudFrontTarget;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.constructs.Construct;

public class EdgeStack extends Stack {

	private final Bucket bucket;
	private final Distribution distribution;

	public EdgeStack(final Construct scope, final String id, final EnvConfig cfg,
			final IHostedZone zone, final ICertificate certificate,
			final FunctionUrl functionUrl) {
		super(scope, id, StackProps.builder().env(cfg.awsEnvironment()).build());

		this.bucket = Bucket.Builder.create(this, "SpaBucket")
				.blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
				.encryption(BucketEncryption.S3_MANAGED)
				.enforceSsl(true)
				.removalPolicy(cfg.removalPolicy())
				.autoDeleteObjects(cfg.removalPolicy() == RemovalPolicy.DESTROY)
				.build();

		Distribution.Builder distributionBuilder = Distribution.Builder.create(this, "Distribution")
				.domainNames(List.of(cfg.appDomain()))
				.certificate(certificate)
				.defaultRootObject("index.html")
				.defaultBehavior(BehaviorOptions.builder()
						.origin(S3BucketOrigin.withOriginAccessControl(bucket))
						.viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
						.cachePolicy(CachePolicy.CACHING_OPTIMIZED)
						.build())
				.additionalBehaviors(java.util.Map.of(
						"/api/*", BehaviorOptions.builder()
								.origin(FunctionUrlOrigin.withOriginAccessControl(functionUrl))
								.viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
								.cachePolicy(CachePolicy.CACHING_DISABLED)
								.originRequestPolicy(
										OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER)
								.allowedMethods(AllowedMethods.ALLOW_ALL)
								.build()))
				// KHÔNG dùng errorResponses ở đây. Nó là cấu hình cấp distribution,
				// CloudFront không scope được theo cache behavior — nên rule
				// "403/404 → 200 /index.html" cho SPA deep link sẽ nuốt luôn mọi lỗi
				// của `/api/*`, biến backend hỏng thành HTTP 200 kèm HTML.
				//
				// SPA deep link quay lại ở Task 27 bằng CloudFront Function
				// (viewer-request) gắn riêng vào default behavior.
				;

		// Khi account bật CloudFront pricing plan, CloudFront TỰ tạo một web ACL
		// và gắn vào distribution. Template không khai báo nó thì mỗi lần update
		// CloudFormation gửi giá trị rỗng, AWS hiểu là "gỡ web ACL" và chặn:
		// "Distributions with a pricing plan subscription must have a web ACL
		// resource." Khai báo lại chính ARN đó là cách kéo drift về IaC mà không
		// phải tự tạo web ACL mới — cái tự tạo có nguy cơ nằm ngoài phạm vi miễn
		// phí của plan.
		if (cfg.wafWebAclArn() != null) {
			distributionBuilder.webAclId(cfg.wafWebAclArn());
		}

		this.distribution = distributionBuilder.build();

		// OAC tới Function URL cần ĐÚNG HAI permission — tài liệu AWS liệt kê cả
		// `lambda:InvokeFunctionUrl` lẫn `lambda:InvokeFunction`.
		// `FunctionUrlOrigin.withOriginAccessControl()` chỉ sinh cái thứ nhất, nên
		// cái thứ hai phải tự thêm.
		//
		// Thiếu nó thì CloudFront vẫn ký đúng và Lambda vẫn nhận request, chỉ trả
		// 403 AccessDeniedException — triệu chứng trông y hệt sai `SourceArn` hay
		// sai principal, nên rất dễ đi sửa nhầm chỗ.
		CfnPermission.Builder.create(this, "AllowCloudFrontInvokeFunction")
				.action("lambda:InvokeFunction")
				.functionName(functionUrl.getFunctionArn())
				.principal("cloudfront.amazonaws.com")
				.sourceArn("arn:aws:cloudfront::" + cfg.account()
						+ ":distribution/" + this.distribution.getDistributionId())
				.build();

		ARecord.Builder.create(this, "AliasRecord")
				.zone(zone)
				.recordName(cfg.appDomain())
				.target(RecordTarget.fromAlias(new CloudFrontTarget(distribution)))
				.build();

		CfnOutput.Builder.create(this, "SiteUrl")
				.value("https://" + cfg.appDomain()).build();
		CfnOutput.Builder.create(this, "BucketName")
				.value(bucket.getBucketName()).build();
		CfnOutput.Builder.create(this, "DistributionId")
				.value(distribution.getDistributionId()).build();
	}

	public Bucket getBucket() {
		return bucket;
	}

	public Distribution getDistribution() {
		return distribution;
	}
}
