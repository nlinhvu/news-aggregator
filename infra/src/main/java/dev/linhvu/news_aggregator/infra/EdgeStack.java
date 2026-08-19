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
			final FunctionUrl functionUrl, final FunctionUrl adminUrl) {
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
				// `LinkedHashMap` chứ TUYỆT ĐỐI KHÔNG `Map.of`, và đây không phải
				// thẩm mỹ: `Map.of` trả về `ImmutableCollections.MapN`, thứ có thứ
				// tự lặp phụ thuộc `SALT` — một giá trị ngẫu nhiên hoá MỖI LẦN JVM
				// khởi động. CDK đặt tên origin theo THỨ TỰ LẶP
				// (`DistributionOrigin2`, `…Origin3`), nên với hai behavior trở lên
				// thì cùng một dòng code sinh ra hai template khác nhau.
				//
				// ĐÃ ĐO, không phải suy đoán: chạy `cdk synth 'Dev/EdgeStack'` năm
				// lần liên tiếp cho ra `Origin2 = admin` bốn lần và `Origin2 = web`
				// một lần.
				//
				// Hậu quả nếu để `Map.of`: mỗi lượt deploy rơi vào thứ tự khác lần
				// trước sẽ ĐỔI CHỦ của `Origin2`/`Origin3`, tức đổi `TargetOriginId`
				// của `/api/*`, đổi luôn hai OAC và hai `CfnPermission` mà CDK tự
				// sinh theo tên origin. CloudFront bị cập nhật lại toàn bộ vì một
				// thay đổi KHÔNG CÓ trong code, và giữa chừng có cửa sổ mà permission
				// trỏ nhầm function. `cdk diff` sẽ báo thay đổi ở một lượt deploy
				// mà không ai sửa gì — triệu chứng dễ bị bỏ qua nhất.
				.additionalBehaviors(behaviors(
						"/api/*", BehaviorOptions.builder()
								.origin(FunctionUrlOrigin.withOriginAccessControl(functionUrl))
								.viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
								.cachePolicy(CachePolicy.CACHING_DISABLED)
								.originRequestPolicy(
										OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER)
								.allowedMethods(AllowedMethods.ALLOW_ALL)
								.build(),
						// Origin THỨ BA. ADR-0005 ghi "một distribution, hai origin";
						// Phase 7 nâng lên ba. Quyết định LÕI của ADR đó — một
						// distribution, same-origin, KHÔNG CORS — không đổi, và đó
						// chính là thứ khiến cookie phiên do `web` phát ra đi tới được
						// `admin` mà không cần cấu hình gì thêm: hai function nằm sau
						// cùng một tên miền.
						//
						// `CACHING_DISABLED` bắt buộc: console lật flag rồi tải lại
						// trang, và một response được cache biến "đã tắt" thành "vẫn
						// hiện là bật" — người vận hành sẽ lật lại lần nữa và tin rằng
						// console hỏng.
						"/admin/*", BehaviorOptions.builder()
								.origin(FunctionUrlOrigin.withOriginAccessControl(adminUrl))
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

		// Origin thứ ba cần CHÍNH XÁC cùng cặp permission — `withOriginAccessControl`
		// sinh `lambda:InvokeFunctionUrl` cho nó, cái thứ hai vẫn phải tự thêm.
		// Quên dòng này thì `/admin/*` trả 403 AccessDeniedException, và triệu chứng
		// đó trùng khít với 403 mà Spring trả cho người không thuộc nhóm `ops` —
		// tức là sẽ đi sửa nhầm sang Cognito group. Phân biệt bằng THÂN response:
		// 403 của AWS có body JSON/XML, 403 của Spring có thân rỗng.
		CfnPermission.Builder.create(this, "AllowCloudFrontInvokeAdminFunction")
				.action("lambda:InvokeFunction")
				.functionName(adminUrl.getFunctionArn())
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

	/**
	 * Giữ THỨ TỰ CHÈN của các behavior, vì thứ tự đó quyết định tên origin mà
	 * CDK sinh ra — xem comment dài ở chỗ gọi.
	 *
	 * `/api/*` phải đứng TRƯỚC `/admin/*`: nó đã chiếm `DistributionOrigin2`
	 * trong distribution đang chạy, và đảo lại là đổi `TargetOriginId` của một
	 * behavior đang phục vụ traffic thật. Behavior thêm về sau cũng nối vào
	 * CUỐI, cùng lý do.
	 */
	private static java.util.Map<String, BehaviorOptions> behaviors(
			String pattern1, BehaviorOptions options1,
			String pattern2, BehaviorOptions options2) {
		java.util.Map<String, BehaviorOptions> ordered = new java.util.LinkedHashMap<>();
		ordered.put(pattern1, options1);
		ordered.put(pattern2, options2);
		return ordered;
	}

	public Bucket getBucket() {
		return bucket;
	}

	public Distribution getDistribution() {
		return distribution;
	}
}
