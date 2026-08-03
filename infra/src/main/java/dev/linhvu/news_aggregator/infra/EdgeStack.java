package dev.linhvu.news_aggregator.infra;

import java.util.List;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.cloudfront.AllowedMethods;
import software.amazon.awscdk.services.cloudfront.BehaviorOptions;
import software.amazon.awscdk.services.cloudfront.CachePolicy;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.ErrorResponse;
import software.amazon.awscdk.services.cloudfront.OriginRequestPolicy;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.origins.FunctionUrlOrigin;
import software.amazon.awscdk.services.cloudfront.origins.S3BucketOrigin;
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

		this.distribution = Distribution.Builder.create(this, "Distribution")
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
				.errorResponses(List.of(
						ErrorResponse.builder().httpStatus(403)
								.responseHttpStatus(200).responsePagePath("/index.html")
								.ttl(Duration.minutes(5)).build(),
						ErrorResponse.builder().httpStatus(404)
								.responseHttpStatus(200).responsePagePath("/index.html")
								.ttl(Duration.minutes(5)).build()))
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
