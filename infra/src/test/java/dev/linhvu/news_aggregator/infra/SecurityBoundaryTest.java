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

	private Template registry() {
		App app = new App();
		return Template.fromStack(new RegistryStack(app, "RegistryStack"));
	}

	private Template appStack() {
		App app = new App();
		AppStage stage = new AppStage(app, EnvConfig.DEV);
		return Template.fromStack((software.amazon.awscdk.Stack)
				stage.getNode().findChild("AppStack"));
	}

	private Template edgeStack() {
		App app = new App();
		AppStage stage = new AppStage(app, EnvConfig.DEV);
		return Template.fromStack((software.amazon.awscdk.Stack)
				stage.getNode().findChild("EdgeStack"));
	}

	private Template cicdStack() {
		App app = new App();
		AppStage stage = new AppStage(app, EnvConfig.DEV);
		return Template.fromStack((software.amazon.awscdk.Stack)
				stage.getNode().findChild("CicdStack"));
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
	 *
	 * `objectEquals` chứ không phải `objectLike` là CỐ Ý. Hai dạng `sub` phải nằm
	 * chung một key `StringLike` để OR với nhau; tách dạng cũ sang `StringEquals`
	 * thì hai condition block AND lại và trust policy thành BẤT KHẢ THI — không
	 * token nào assume được, mà `objectLike` vẫn xanh vì nó chỉ kiểm tra sự tồn
	 * tại. Assert chặt ở đây là thứ duy nhất bắt được lỗi đó.
	 */
	@Test
	void trust_policy_cua_prod_ghim_theo_environment_prod() {
		oidcHub().hasResourceProperties("AWS::IAM::Role", Match.objectLike(Map.of(
				"AssumeRolePolicyDocument", Match.objectLike(Map.of(
						"Statement", Match.arrayWith(List.of(
								Match.objectLike(Map.of(
										"Action", "sts:AssumeRoleWithWebIdentity",
										"Condition", Match.objectEquals(Map.of(
												"StringEquals", Map.of(
														"token.actions.githubusercontent.com:aud",
														"sts.amazonaws.com"),
												"StringLike", Map.of(
														"token.actions.githubusercontent.com:sub",
														List.of(
																"repo:nlinhvu/news-aggregator:environment:prod",
																"repo:nlinhvu@*/news-aggregator@*:environment:prod"))
										))
								))
						))
				))
		)));
	}

	/**
	 * `app-deploy.yml` assume `GhaBuildRole` rồi `docker push` THẲNG, không chain
	 * qua role nào nữa. Nếu quyền push không nằm ở chính role này thì `docker
	 * login` vẫn qua (nhờ `ecr:GetAuthorizationToken`) và `docker push` mới chết ở
	 * `InitiateLayerUpload` — lỗi hiện ra cách xa nguyên nhân.
	 */
	@Test
	void build_role_tu_no_push_duoc_vao_ecr() {
		oidcHub().hasResourceProperties("AWS::IAM::Policy", Match.objectLike(Map.of(
				"PolicyDocument", Match.objectLike(Map.of(
						"Statement", Match.arrayWith(List.of(
								Match.objectLike(Map.of(
										"Action", Match.arrayWith(List.of(
												"ecr:InitiateLayerUpload", "ecr:PutImage")),
										"Resource", Match.objectLike(Map.of(
												"Fn::Join", Match.arrayWith(List.of(
														Match.arrayWith(List.of(
																":repository/news-aggregator"))))
										))
								))
						))
				))
		)));
	}

	/**
	 * Hệ quả của test trên: registry không cần role trung gian nào. Thêm một role
	 * chỉ để cấp quyền push trong CÙNG account không tạo thêm ranh giới, chỉ thêm
	 * một hop nữa để quên.
	 */
	@Test
	void registry_khong_tao_role_nao() {
		registry().resourceCountIs("AWS::IAM::Role", 0);
	}

	/**
	 * Repo policy cần ĐÚNG HAI statement, không phải một.
	 *
	 * `CrossAccountPermission` cho principal là ba account môi trường là cái
	 * ai cũng nhớ. Cái hay quên là `LambdaECRImageCrossAccountRetrievalPolicy`
	 * cho principal `lambda.amazonaws.com` — Lambda tự fetch lại image để
	 * re-optimize khi function nằm không quá lâu và chuyển sang Inactive.
	 *
	 * Thiếu statement thứ hai thì MỌI THỨ CHẠY BÌNH THƯỜNG LÚC DEPLOY, và
	 * hỏng sau nhiều tuần khi function không reactivate được. Failure mode
	 * chậm và rất khó truy — nên nó phải bị bắt ở đây.
	 */
	@Test
	void ecr_repo_policy_co_du_hai_statement() {
		registry().hasResourceProperties("AWS::ECR::Repository", Match.objectLike(Map.of(
				"RepositoryPolicyText", Match.objectLike(Map.of(
						"Statement", Match.arrayWith(List.of(
								Match.objectLike(Map.of("Sid", "CrossAccountPermission")),
								Match.objectLike(Map.of(
										"Sid", "LambdaECRImageCrossAccountRetrievalPolicy"))
						))
				))
		)));
	}

	/** Tag IMMUTABLE toàn bộ — master §8.1. Deploy tham chiếu bằng digest. */
	@Test
	void ecr_tag_immutable() {
		registry().hasResourceProperties("AWS::ECR::Repository", Match.objectLike(Map.of(
				"ImageTagMutability", "IMMUTABLE"
		)));
	}

	/**
	 * Lifecycle rule phải đếm RIÊNG theo tiền tố từng môi trường.
	 * Với một registry dùng chung, đếm gộp toàn repo thì một tuần push nhiều
	 * ở `dev` đủ đẩy digest mà PROD ĐANG CHẠY ra khỏi cửa sổ — và AWS ghi rõ
	 * image bị xoá khiến function chuyển sang trạng thái Failed.
	 */
	@Test
	void lifecycle_rule_scope_theo_tag_prefix_tung_moi_truong() {
		Template registry = registry();

		// Mỗi prefix phải là thành viên DUY NHẤT của tagPrefixList trong rule
		// của nó. Gộp cả ba vào một list — ["prod-","qa-","dev-"] — chính là
		// cái đếm gộp mà javadoc trên cảnh báo, nên assert phải bắt được nó.
		for (String prefix : List.of("prod-", "qa-", "dev-")) {
			registry.hasResourceProperties("AWS::ECR::Repository", Match.objectLike(Map.of(
					"LifecyclePolicy", Match.objectLike(Map.of(
							"LifecyclePolicyText", Match.stringLikeRegexp(
									".*\"tagPrefixList\":\\[\"" + prefix + "\"\\].*")
					))
			)));
		}
	}

	/**
	 * Function URL PHẢI để AWS_IAM, không bao giờ NONE.
	 *
	 * Đây là loại lỗi "hỏng mà vẫn chạy được": để NONE thì trang web vẫn
	 * hoạt động hoàn hảo, chỉ là Function URL trở thành public và master
	 * §8.1 bị vi phạm mà không có triệu chứng nào nhìn thấy được.
	 */
	@Test
	void function_url_dung_auth_type_aws_iam() {
		appStack().hasResourceProperties("AWS::Lambda::Url", Match.objectLike(Map.of(
				"AuthType", "AWS_IAM"
		)));
	}

	/** Lambda phải arm64 và ngoài VPC (ADR-0001). */
	@Test
	void lambda_arm64_va_ngoai_vpc() {
		Template t = appStack();
		t.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
				"Architectures", List.of("arm64"),
				"PackageType", "Image"
		)));
		t.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
				"VpcConfig", Match.absent()
		)));
	}

	/** Log retention tối đa 14 ngày ở MỌI môi trường (master §8.2). */
	@Test
	void log_retention_toi_da_14_ngay() {
		appStack().hasResourceProperties("AWS::Logs::LogGroup", Match.objectLike(Map.of(
				"RetentionInDays", 14
		)));
	}

	/** S3 bucket phải chặn TOÀN BỘ public access (master §8.1). */
	@Test
	void s3_bucket_chan_toan_bo_public_access() {
		edgeStack().hasResourceProperties("AWS::S3::Bucket", Match.objectLike(Map.of(
				"PublicAccessBlockConfiguration", Map.of(
						"BlockPublicAcls", true,
						"BlockPublicPolicy", true,
						"IgnorePublicAcls", true,
						"RestrictPublicBuckets", true)
		)));
	}

	/**
	 * `/api/*` KHÔNG ĐƯỢC cache. Đây là chế độ hỏng khó nhận ra nhất của
	 * phương án một-distribution-hai-origin: cache nhầm thì API trả dữ liệu
	 * cũ mà không có lỗi nào xuất hiện, và commit sha ở /api/health sẽ không
	 * đổi sau khi deploy (ADR-0005 §7).
	 *
	 * CachePolicy CACHING_DISABLED có id cố định do AWS quản.
	 */
	@Test
	void api_khong_duoc_cache() {
		edgeStack().hasResourceProperties("AWS::CloudFront::Distribution",
				Match.objectLike(Map.of(
						"DistributionConfig", Match.objectLike(Map.of(
								"CacheBehaviors", Match.arrayWith(List.of(
										Match.objectLike(Map.of(
												"PathPattern", "/api/*",
												"CachePolicyId",
												"4135ea2d-6df8-44a3-9df3-4b5a84be39ad"))
								))
						))
				)));
	}

	/** Deep link của SPA phải trả index.html với status 200, không phải 403/404 của S3. */
	@Test
	void spa_deep_link_duoc_anh_xa_ve_index_html() {
		edgeStack().hasResourceProperties("AWS::CloudFront::Distribution",
				Match.objectLike(Map.of(
						"DistributionConfig", Match.objectLike(Map.of(
								"CustomErrorResponses", Match.arrayWith(List.of(
										Match.objectLike(Map.of(
												"ErrorCode", 403,
												"ResponseCode", 200,
												"ResponsePagePath", "/index.html")),
										Match.objectLike(Map.of(
												"ErrorCode", 404,
												"ResponseCode", 200,
												"ResponsePagePath", "/index.html"))
								))
						))
				)));
	}

	/**
	 * Pipeline ứng dụng KHÔNG ĐƯỢC có quyền CloudFormation nào (master §8.1).
	 * Ranh giới giữa hai pipeline do IAM cưỡng chế, không do quy ước.
	 */
	@Test
	void deploy_role_khong_co_quyen_cloudformation() {
		String json = cicdStack().toJSON().toString();
		org.junit.jupiter.api.Assertions.assertFalse(
				json.contains("cloudformation:"),
				"CicdStack không được cấp bất kỳ action cloudformation:* nào");
	}
}
