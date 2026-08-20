package dev.linhvu.news_aggregator.infra;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
		return appStack(EnvConfig.DEV);
	}

	/**
	 * Từ Phase 2, `AppStack` KHÁC NHAU theo môi trường — `qa` cố ý không có
	 * Schedule (TDD §17 #1) và nhịp của dev khác prod. Tham số hoá theo đúng
	 * cách `DataStackTest#dataStack(EnvConfig)` đã làm, thay vì thêm ba helper
	 * `devApp()/qaApp()/prodApp()` song song với helper sẵn có.
	 */
	private Template appStack(EnvConfig cfg) {
		App app = new App();
		AppStage stage = new AppStage(app, cfg);
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

	private Template observabilityStack(EnvConfig cfg) {
		App app = new App();
		AppStage stage = new AppStage(app, cfg);
		return Template.fromStack((software.amazon.awscdk.Stack)
				stage.getNode().findChild("ObservabilityStack"));
	}

	private Template identityStack(EnvConfig cfg) {
		App app = new App();
		AppStage stage = new AppStage(app, cfg);
		return Template.fromStack((software.amazon.awscdk.Stack)
				stage.getNode().findChild("IdentityStack"));
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
	 * Build role còn phải ĐỌC được image vừa push — `grantPush` KHÔNG cấp quyền đó.
	 *
	 * `repo.grantPush()` chỉ cấp đúng năm action ghi cộng `ecr:GetAuthorizationToken`.
	 * Nhưng `app-deploy.yml` gọi `aws ecr describe-images` hai lần: một lần kiểm tra
	 * tag đã tồn tại chưa (idempotency), một lần lấy digest để ba job môi trường
	 * promote. Cả hai cần `ecr:DescribeImages`.
	 *
	 * Chế độ hỏng của nó độc ở chỗ THỨ TỰ: `docker push` thành công rồi mới chết ở
	 * bước đọc digest. Tag đã nằm trong ECR, mà repo đặt IMMUTABLE — nên khi re-run
	 * đúng commit đó, lần describe kiểm tra idempotency (bị `2>&1` nuốt stderr) vẫn
	 * ngã sang nhánh push và chết bằng `ImmutableTagCannotBeUpdated`, một lỗi hoàn
	 * toàn KHÁC che mất nguyên nhân thật. Đã trả giá thật ở Task 19.
	 */
	@Test
	void build_role_doc_duoc_digest_cua_image() {
		assertTrue(resourceForAction(oidcHub(), "GhaBuildRole", "ecr:DescribeImages")
						.contains(":repository/news-aggregator"),
				"GhaBuildRole phải được ecr:DescribeImages trên chính repo app");
	}

	/**
	 * `AppDeployRole` phải TỰ có quyền đọc ECR ở account tooling.
	 *
	 * Truy cập cross-account cần allow ở CẢ HAI phía. Repo policy bên tooling cấp
	 * cho `arn:aws:iam::<env>:root` mới chỉ DELEGATE quyền xuống account; principal
	 * thực sự gọi API vẫn phải được identity-based policy của chính nó cho phép.
	 *
	 * Thiếu vế này, `aws lambda update-function-code` trả về *"Lambda does not have
	 * permission to access the ECR image. Check the ECR permissions."* — câu lỗi
	 * trỏ thẳng vào ECR trong khi thứ thiếu nằm ở role BÊN NÀY, nên phản xạ đầu
	 * tiên là đi sửa repo policy vốn đã đúng.
	 *
	 * `cdk deploy` KHÔNG bao giờ lộ ra lỗi này: nó chạy bằng
	 * `cdk-…-cfn-exec-role` vốn gắn AdministratorAccess, nên vế identity luôn
	 * thoả. Chỉ pipeline ứng dụng — chạy bằng role hẹp — mới đụng phải. Đó là lý
	 * do image bootstrap deploy trót lọt mà `app-deploy.yml` thì chết (Task 19).
	 */
	@Test
	void app_deploy_role_keo_duoc_image_tu_tooling() {
		for (String action : List.of("ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer")) {
			assertEquals("arn:aws:ecr:us-east-1:" + EnvConfig.TOOLING_ACCOUNT
							+ ":repository/news-aggregator",
					resourceForAction(cicdStack(), "AppDeployRole", action),
					"AppDeployRole phải được " + action + " trên repo ở tooling");
		}
	}

	/**
	 * Trả về Resource của statement ĐẦU TIÊN cấp {@code action} trong policy có tên
	 * bắt đầu bằng {@code policyPrefix}, dạng chuỗi — rỗng nếu không statement nào
	 * cấp.
	 *
	 * Chỉ dùng khi câu hỏi là "có được cấp không / cấp ở đâu" và cả stack chỉ có
	 * MỘT statement cấp action đó. Khi cùng một action được cấp trên nhiều bảng —
	 * từ Phase 2 thì `Scan`, `UpdateItem`, `PutItem` đều rơi vào diện này, và
	 * Phase 3 thêm `GetItem` (feature-toggles + articles) cùng `UpdateItem` thứ
	 * hai (articles) — hàm này chỉ thấy statement đầu tiên và sẽ bỏ lọt statement
	 * thứ hai. Dùng {@link #resourcesForAction} cho những trường hợp đó.
	 */
	private String resourceForAction(Template template, String policyPrefix,
			String action) {
		List<String> resources = resourcesForAction(template, policyPrefix, action);
		return resources.isEmpty() ? "" : resources.get(0);
	}

	/**
	 * Trả về Resource của MỌI statement cấp {@code action}, mỗi phần tử là một
	 * statement — rỗng nếu không statement nào cấp.
	 *
	 * Đọc thủ công thay vì dùng `Match` là CỐ Ý. Cùng một policy được CDK render ra
	 * HAI hình dạng khác nhau: `minimizePolicies` gộp action vào chung một mảng khi
	 * các statement trùng resource, mà resource chỉ trùng khi stack có `env` tường
	 * minh (ARN thành chuỗi literal). Stack dựng KHÔNG `env` thì ARN là `Fn::Join`,
	 * hai statement không gộp, và statement một-action lại render thành chuỗi chứ
	 * không phải mảng.
	 *
	 * Nghĩa là assertion bám theo hình dạng sẽ báo đỏ GIẢ ngay khi ai đó thêm `env`
	 * vào stack trong test — dù policy không đổi gì. Chuẩn hoá về danh sách rồi mới
	 * so là cách giữ cho test kiểm tra ĐIỀU KIỆN IAM thật.
	 *
	 * Ba bảng của ta nằm ở `DataStack` nên ARN sang tới đây là `Fn::ImportValue`,
	 * và tên export mang theo logical id (`…ArticlesTable…Arn…`). Đó là lý do các
	 * test dưới nhận diện bảng bằng `contains("ArticlesTable")` — chuỗi đó đến từ
	 * CDK chứ không phải ta bịa ra.
	 */
	@SuppressWarnings("unchecked")
	private List<String> resourcesForAction(Template template, String policyPrefix,
			String action) {
		List<String> resources = new java.util.ArrayList<>();
		for (Map<String, Object> policy
				: template.findResources("AWS::IAM::Policy").values()) {
			Map<String, Object> props = (Map<String, Object>) policy.get("Properties");
			if (!String.valueOf(props.get("PolicyName")).startsWith(policyPrefix)) {
				continue;
			}
			Map<String, Object> doc = (Map<String, Object>) props.get("PolicyDocument");
			for (Map<String, Object> stmt
					: (List<Map<String, Object>>) doc.get("Statement")) {
				Object actions = stmt.get("Action");
				List<Object> list = actions instanceof List
						? (List<Object>) actions : List.of(actions);
				if (list.contains(action)) {
					// Một statement cấp trên NHIỀU resource thì mỗi resource phải là
					// một phần tử RIÊNG, không phải một chuỗi `[r1, r2]` gộp. Gộp lại
					// làm mọi vế `contains` thành đúng-nhờ-hàng-xóm: từ Phase 3,
					// `…/index/gsi-recent` là TIỀN TỐ của `…/index/gsi-recent-v2`, nên
					// chuỗi gộp thoả vế "có v1" ngay cả khi ARN v1 đã bị xoá khỏi
					// `AppStack`. Đã đo — cả suite vẫn xanh.
					Object resource = stmt.get("Resource");
					for (Object one : resource instanceof List
							? (List<Object>) resource : List.of(resource)) {
						resources.add(String.valueOf(one));
					}
				}
			}
		}
		return resources;
	}

	/**
	 * `SmokeRole` là role hẹp nhất trong chương trình: đúng MỘT action trên
	 * đúng MỘT resource. Nó tồn tại vì đường scheduled không kiểm được bằng
	 * `curl` — không có URL public nào dẫn tới nó, theo đúng thiết kế.
	 *
	 * Vế thứ hai mới là vế đáng giá. Job smoke chạy sau MỌI lần deploy, nên gộp
	 * nó vào `AppDeployRole` — role đang có `lambda:UpdateFunctionCode` — nghĩa
	 * là mỗi lượt kiểm tra sức khoẻ đều cầm sẵn quyền thay binary. Thừa quyền
	 * không tạo ra triệu chứng nào, nên test là thứ duy nhất giữ được ranh giới.
	 */
	@Test
	void smoke_role_invoke_duoc_dung_hai_function_scheduled() {
		Template t = cicdStack();

		// `resourcesForAction` (số NHIỀU) chứ không `resourceForAction`: bản trước
		// tên là `..._dung_mot_function` nhưng chỉ hỏi "có không có" trên phần tử
		// đầu, nên nó xanh với BẤT KỲ số function nào. Tên hứa một đằng, assertion
		// canh một nẻo — và nó xanh y hệt nhau ở cả hai bên của việc tách.
		List<String> invokeOn = resourcesForAction(t, "SmokeRoleDefaultPolicy",
				"lambda:InvokeFunction");

		assertEquals(2, invokeOn.size(),
				"SmokeRole invoke đúng hai function có đường scheduled, thực tế: "
						+ invokeOn);
		assertTrue(invokeOn.stream().anyMatch(r -> r.contains("IngestFunction")),
				"thiếu quyền invoke `ingest`, thực tế: " + invokeOn);
		assertTrue(invokeOn.stream().anyMatch(r -> r.contains("SummarizeFunction")),
				"thiếu quyền invoke `summarize`, thực tế: " + invokeOn);
		// `web` KHÔNG nằm trong đó: nó có Function URL nên smoke test chạm bằng
		// `curl`. Chuỗi export của `web` là `…FnGetAttFunction76856677Arn…`, mà hai
		// function kia cũng chứa `Function`, nên phải loại trừ bằng tiền tố riêng.
		assertTrue(invokeOn.stream().noneMatch(
						r -> r.contains("FnGetAttFunction76856677")),
				"SmokeRole KHÔNG được invoke `web` — nó test qua CloudFront: " + invokeOn);

		for (String action : List.of("lambda:UpdateFunctionCode", "lambda:GetFunction",
				"ssm:PutParameter", "s3:PutObject")) {
			assertTrue(resourceForAction(t, "SmokeRoleDefaultPolicy", action).isEmpty(),
					"SmokeRole KHÔNG được cấp " + action + " — nó chỉ để invoke");
		}
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

	@SuppressWarnings("unchecked")
	private String lifecyclePolicyText() {
		Map<String, Object> repo = registry()
				.findResources("AWS::ECR::Repository").values().iterator().next();
		Map<String, Object> props = (Map<String, Object>) repo.get("Properties");
		Map<String, Object> policy = (Map<String, Object>) props.get("LifecyclePolicy");
		return (String) policy.get("LifecyclePolicyText");
	}

	/**
	 * Đúng MỘT rule cho image có tag — không tách theo tiền tố môi trường.
	 *
	 * Bản đầu tách `prod-` 5 / `qa-` 10 / `dev-` 20, đọc như "mỗi môi trường giữ
	 * N bản". Nó không hoạt động như vậy: ECR quy định *"an image is expired by
	 * exactly one or zero rules"* và rule ưu tiên thấp hơn không đụng được image
	 * mà rule ưu tiên cao đã giữ. Vì promotion gắn nhiều tiền tố lên CÙNG một
	 * image, rule ưu tiên 1 khống chế tất cả và hai con số kia trở nên trơ — bộ
	 * rule đó thực tế chỉ giữ 5.
	 *
	 * Test này chặn việc dựng lại cái bẫy đó. Nếu sau này thật sự cần bảo vệ prod
	 * theo kiểu đảm bảo (không phải xác suất), cách đúng là `prod-` ở ưu tiên 1 —
	 * và khi đó phải sửa test này một cách có ý thức, kèm đọc lại ADR-0004.
	 */
	@Test
	void mot_rule_duy_nhat_cho_image_co_tag() {
		String policy = lifecyclePolicyText();
		assertFalse(policy.contains("\"prod-\""), "không tách rule theo prod-: " + policy);
		assertFalse(policy.contains("\"qa-\""), "không tách rule theo qa-: " + policy);
		assertFalse(policy.contains("\"dev-\""), "không tách rule theo dev-: " + policy);
		assertTrue(policy.contains(
						"\"rulePriority\":1,\"selection\":{\"tagStatus\":\"tagged\","
								+ "\"tagPrefixList\":[\"main-\"]"),
				"rule ưu tiên 1 phải là main-, thực tế: " + policy);
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

	/**
	 * Một chuỗi trong env map — không compiler nào bắt được khi nó bị xoá, và
	 * không có triệu chứng nào ở runtime: hệ thống chạy y hệt, chỉ là mọi lỗi
	 * tầng ứng dụng lại trở nên vô hình. Đây là chốt chặn duy nhất.
	 *
	 * `500-599` chứ không rộng hơn: 4xx CỐ Ý nằm ngoài. Bot quét sinh 404 trên
	 * đường public hàng ngày; đưa 4xx vào là biến lưu lượng rác thành "lỗi Lambda"
	 * và alarm `Errors` mất hết giá trị. Xem ADR-0015 §6.
	 */
	@Test
	void lwa_coi_5xx_la_loi_invoke() {
		appStack().hasResourceProperties("AWS::Lambda::Function",
				Match.objectLike(Map.of("Environment", Map.of("Variables",
						Match.objectLike(Map.of(
								"AWS_LWA_ERROR_STATUS_CODES", "500-599"))))));
	}

	/**
	 * Phủ định của test trên. Nếu ai đó "sửa" dải thành `400-599` để bắt thêm lỗi,
	 * alarm sẽ nổ vì mỗi con bot — và một alarm hay báo động giả bị phớt lờ đúng
	 * lúc nó cần được tin nhất.
	 */
	@Test
	void lwa_khong_coi_4xx_la_loi_invoke() {
		String env = appStack().findResources("AWS::Lambda::Function").toString();
		assertFalse(env.contains("400-"),
				"4xx phải nằm NGOÀI AWS_LWA_ERROR_STATUS_CODES — xem ADR-0015 §6");
	}

	/**
	 * Image phải tham chiếu bằng DIGEST (`@sha256:…`), không phải TAG (`:sha256:…`).
	 *
	 * `Code.fromEcrImage(...tagOrDigest(x))` chọn `@` hay `:` bằng cách gọi
	 * `x.startsWith("sha256:")`. Nhưng digest của ta là CloudFormation token do
	 * `valueForStringParameter` sinh ra — lúc synth nó là `${Token[TOKEN.n]}`,
	 * `startsWith` trả false, và CDK lặng lẽ nối bằng `:`. Lambda nhận về một
	 * TAG tên `sha256:f4b2…` không tồn tại.
	 *
	 * Đây là lỗi mà `cdk synth` VÀ `cdk diff` đều không thấy: template hợp lệ,
	 * chỉ sai giá trị sau khi resolve. Nó chỉ chết ở `CREATE_FAILED` lúc deploy,
	 * và đã chết thật một lần ở Task 14. Test này là thứ duy nhất bắt được nó
	 * trước khi tốn một vòng deploy.
	 */
	@Test
	void image_tham_chieu_bang_digest_khong_phai_tag() {
		appStack().hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
				"Code", Match.objectLike(Map.of(
						"ImageUri", Match.objectLike(Map.of(
								"Fn::Join", Match.arrayWith(List.of(
										Match.arrayWith(List.of("/news-aggregator@"))))
						))
				))
		)));
	}

	/**
	 * `memorySize` là núm chỉnh CPU, KHÔNG phải RAM — đừng hạ xuống để "tiết kiệm".
	 *
	 * Đo thật trên dev ở 1024 MB (Task 14): cold start 23,5s, Spring boot 20,9s,
	 * `Max Memory Used` chỉ 204 MB. RAM thừa gấp năm lần, nên nút thắt là CPU;
	 * Lambda cấp CPU tỉ lệ với memory nên nâng memory là cách duy nhất mua thêm
	 * CPU.
	 *
	 * Ở 1024 MB, cold start 23,5s so với `timeout` 30s chỉ còn 6,5s dư — một
	 * image nặng hơn là quay lại 502. Đây là chế độ hỏng NGẪU NHIÊN và chỉ xảy
	 * ra lúc cold, nên rất khó truy; test này ghim lại cả con số lẫn lý do.
	 *
	 * `Timeout` KHÔNG còn khẳng định ở đây: từ Phase 2 nó là 120s và lý do
	 * thuộc về đường ingestion, không phải cold start — xem
	 * {@link #timeout_du_cho_mot_luot_ingestion()}. Hai con số cùng nằm trong
	 * một `Map.of` sẽ khiến bất kỳ ai chỉnh timeout cũng phải đọc lý do của
	 * memory, và ngược lại.
	 */
	@Test
	void lambda_du_cpu_cho_cold_start() {
		appStack().hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
				"MemorySize", 2048
		)));
	}

	/**
	 * `MaximumRetryAttempts` mặc định của EventBridge Scheduler là **185**
	 * (AWS API reference). Không set tường minh nghĩa là một nguồn hỏng kéo dài
	 * sẽ thành 185 lần invoke Lambda — đây là footgun về CHI PHÍ, và nó không có
	 * triệu chứng nào ngoài hoá đơn.
	 */
	@Test
	void schedule_gioi_han_retry_va_co_dlq() {
		appStack().hasResourceProperties("AWS::Scheduler::Schedule", Match.objectLike(Map.of(
				"Target", Match.objectLike(Map.of(
						// Ghim đúng schedule ingest — xem `prod_chay_moi_gio_dev_moi_sau_gio`
						// về lý do payload phải đi kèm từ khi có schedule thứ hai.
						"Input", "{\"job\":\"ingest-feeds\"}",
						"RetryPolicy", Match.objectLike(Map.of(
								"MaximumRetryAttempts", 2)),
						"DeadLetterConfig", Match.anyValue())))));
	}

	/**
	 * `qa` cố ý KHÔNG có schedule (TDD §17 #1): ba môi trường cùng đập vào blog
	 * gốc mỗi giờ là ×3 lượng request từ một org. Deploy qa vẫn phải xanh —
	 * chỉ là không có lượt ingestion nào chạy.
	 */
	@Test
	void qa_khong_co_schedule() {
		appStack(EnvConfig.QA).resourceCountIs("AWS::Scheduler::Schedule", 0);
	}

	/**
	 * `Input` khai cùng `ScheduleExpression` chứ không tách ra: từ Phase 3 có HAI
	 * schedule trỏ vào cùng function, và `hasResourceProperties` chỉ đòi CÓ MỘT
	 * resource khớp. Chỉ khớp nhịp thì "prod chạy mỗi giờ" xanh kể cả khi nhịp
	 * đó thuộc về sweep còn ingest đã bị đổi — ghép nhịp với payload là thứ ghim
	 * lại đúng schedule đang nói tới.
	 */
	@Test
	void prod_chay_moi_gio_dev_moi_sau_gio() {
		appStack(EnvConfig.PROD).hasResourceProperties(
				"AWS::Scheduler::Schedule",
				Match.objectLike(Map.of("ScheduleExpression", "rate(1 hour)",
						"Target", Match.objectLike(Map.of(
								"Input", "{\"job\":\"ingest-feeds\"}")))));
		appStack(EnvConfig.DEV).hasResourceProperties(
				"AWS::Scheduler::Schedule",
				Match.objectLike(Map.of("ScheduleExpression", "rate(6 hours)",
						"Target", Match.objectLike(Map.of(
								"Input", "{\"job\":\"ingest-feeds\"}")))));
	}

	/**
	 * prod và dev có HAI schedule (ingest + sweep); qa có KHÔNG.
	 *
	 * `qa` là chỗ dễ hồi quy nhất: thêm resource mới rất dễ quên nhánh
	 * `if (cfg.sweepRate() != null)`, và hậu quả là qa bắt đầu poll blog gốc và
	 * gọi model — đúng thứ mà quyết định "qa không có Schedule" của Phase 2 loại
	 * bỏ (master §8.4 coi lịch sự với nguồn là ràng buộc).
	 */
	@Test
	void so_luong_schedule_dung_theo_moi_truong() {
		appStack(EnvConfig.PROD).resourceCountIs("AWS::Scheduler::Schedule", 2);
		appStack(EnvConfig.DEV).resourceCountIs("AWS::Scheduler::Schedule", 2);
		appStack(EnvConfig.QA).resourceCountIs("AWS::Scheduler::Schedule", 0);
	}

	/**
	 * Sweep thưa hơn ingest. Bằng nhau nghĩa là ai đó đã sao chép nhịp — và
	 * hậu quả là DLQ ngập message trùng lặp cho cùng một bài hỏng.
	 *
	 * `rate(1 day)` chứ KHÔNG phải `rate(24 hours)`: CDK tự đổi `Duration.hours(24)`
	 * sang đơn vị lớn nhất chia hết. Giá trị ở đây phải là thứ template synth ra
	 * thật, không phải thứ đọc xuôi tai từ `EnvConfig`.
	 */
	@Test
	void sweep_thua_hon_ingest() {
		appStack(EnvConfig.PROD).hasResourceProperties("AWS::Scheduler::Schedule",
				Match.objectLike(Map.of("ScheduleExpression", "rate(6 hours)",
						"Target", Match.objectLike(Map.of(
								"Input", "{\"job\":\"summarize-sweep\"}")))));
		appStack(EnvConfig.DEV).hasResourceProperties("AWS::Scheduler::Schedule",
				Match.objectLike(Map.of("ScheduleExpression", "rate(1 day)",
						"Target", Match.objectLike(Map.of(
								"Input", "{\"job\":\"summarize-sweep\"}")))));
	}

	/**
	 * MỘT DLQ cho cả hai schedule, không phải hai.
	 *
	 * Ba queue ở dev/prod là `SummarizeQueue`, `SummarizeDlq` và `IngestDlq` dùng
	 * chung. Con số 4 nghĩa là ai đó đã thêm `SweepDlq` riêng — không lỗi, không
	 * cảnh báo, chỉ là từ đó người vận hành phải nhớ kiểm hai chỗ cho cùng một
	 * loại sự cố, và cái bị quên luôn là cái mới.
	 *
	 * `qa` có ĐÚNG hai: không schedule nào thì cũng không có DLQ nào để nuôi.
	 * Dựng nó vô điều kiện là để lại một hàng đợi không ai đọc trong một môi
	 * trường vốn dĩ không sinh ra được message nào cho nó.
	 */
	@Test
	void hai_schedule_dung_chung_mot_dlq() {
		appStack(EnvConfig.PROD).resourceCountIs("AWS::SQS::Queue", 3);
		appStack(EnvConfig.DEV).resourceCountIs("AWS::SQS::Queue", 3);
		appStack(EnvConfig.QA).resourceCountIs("AWS::SQS::Queue", 2);
	}

	/**
	 * `maximumRetryAttempts` mặc định là 185. Không set tường minh thì một lỗi
	 * kéo dài thành 185 lần invoke — và với sweep, mỗi invoke là một lượt query
	 * cộng tối đa 25 message. Bài học Phase 2, áp lại cho schedule thứ hai.
	 */
	@Test
	void sweep_schedule_co_retry_va_dlq() {
		appStack(EnvConfig.PROD).hasResourceProperties("AWS::Scheduler::Schedule",
				Match.objectLike(Map.of("Target", Match.objectLike(Map.of(
						"Input", "{\"job\":\"summarize-sweep\"}",
						"RetryPolicy", Match.objectLike(Map.of(
								"MaximumRetryAttempts", 2)),
						"DeadLetterConfig", Match.anyValue())))));
	}

	/**
	 * Path pass-through khai TƯỜNG MINH trong CDK dù nó trùng mặc định của LWA.
	 * Lý do: nó phải grep được và test được, thay vì là hằng số ngầm nằm trong
	 * binary của extension. Giá trị này phải khớp property
	 * `news.platform.pass-through-path` bên repo app, chỗ `EventsController` lấy
	 * path của nó — hai repo không thấy nhau nên compiler không bắt được lệch.
	 *
	 * Và nó phải KHÔNG nằm dưới `/api/*`: CloudFront chỉ route `/api/*` tới
	 * Lambda origin, nên một path như `/api/events` biến đường ingestion thành
	 * public — hỏng mà vẫn chạy được, đúng loại lỗi không có triệu chứng.
	 */
	@Test
	void lwa_pass_through_path_khai_tuong_minh_va_khong_duoi_api() {
		appStack().hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
				"Environment", Match.objectLike(Map.of(
						"Variables", Match.objectLike(Map.of(
								"AWS_LWA_PASS_THROUGH_PATH", "/events")))))));
	}

	/**
	 * Cold start median 15s CHỈ để boot Spring (Phase 1 §16). 30s để lại ~15s
	 * cho việc fetch 4 feed — quá sát. Timeout cao không tốn tiền: Lambda tính
	 * theo duration THẬT.
	 */
	@Test
	void timeout_du_cho_mot_luot_ingestion() {
		appStack().hasResourceProperties(
				"AWS::Lambda::Function", Match.objectLike(Map.of("Timeout", 120)));
	}

	/**
	 * Lambda chỉ được Query ĐÚNG hai index của `articles`, không phải `/index/*`.
	 *
	 * `articlesTable.grantReadData()` — cách viết hiển nhiên, và là cách plan
	 * đề xuất ban đầu — cấp resource `<table>.Arn/index/*` trong khi bảng có
	 * đúng một index, kèm `dynamodb:Scan` cùng `GetRecords`/`GetShardIterator`
	 * của DynamoDB Streams (bảng chưa bật stream). cdk-nag bắt được vế wildcard,
	 * nhưng KHÔNG bắt được `Scan` — vì `Scan` là action tường minh, không phải
	 * wildcard. Nên riêng CdkNagTest là chưa đủ để chặn việc quay lại
	 * `grantReadData`, và đó là lý do test này tồn tại.
	 *
	 * Vế `Scan` KHÔNG còn ở đây. Tới Phase 2 thì `Scan` là quyền HỢP LỆ trên bảng
	 * `sources`, nên khẳng định "Lambda không bao giờ có Scan" trở thành sai; nó
	 * chuyển thành "không bao giờ trên `articles`" và có nhà riêng ở
	 * {@link #khong_bao_gio_scan_bang_articles()}. Để lại một bản sao ở đây thì
	 * lần sửa quy tắc tiếp theo sẽ chỉ sửa một trong hai chỗ.
	 *
	 * ĐÚNG HAI ARN, mỗi index MỘT dòng. Con số này là lịch sử của chương trình chứ
	 * không phải một hằng số tuỳ ý: 1 ở Phase 1, tạm lên 2 trong lúc migrate sang
	 * `gsi-recent-v2` (code còn đọc v1 tới Task 13), về lại 1 khi index cũ bị xoá,
	 * và lên 2 ở Task 19 vì slice 4 thêm `gsi-by-source`. Giữ lại ARN của một index
	 * không còn tồn tại là để execution role mang một quyền trỏ vào hư không: không
	 * lỗi, không triệu chứng, chỉ là quyền thừa mà lần audit sau phải truy nguyên.
	 *
	 * Vế `contains(RECENT_INDEX_V2_NAME)` là an toàn, nhưng vế `contains(v1)` thì
	 * KHÔNG bao giờ được viết trần: `…/index/gsi-recent` là TIỀN TỐ của
	 * `…/index/gsi-recent-v2`, nên nó được chính ARN v2 làm cho xanh vĩnh viễn.
	 * Đã đo cả hai chiều hồi Task 11B. Ở đây `assertEquals(2, size)` là thứ chặn
	 * việc ARN v1 lặng lẽ quay lại.
	 *
	 * Vế "mọi ARN đều có `/index/`" là vế mới của Task 19: `Query` trên ARN BẢNG
	 * trần cấp luôn quyền query mọi thứ trong bảng, và nó synth xanh y hệt.
	 */
	@Test
	void web_chi_query_dung_hai_index_cua_articles() {
		List<String> queryOn = resourcesForAction(appStack(),
				"FunctionRoleDefaultPolicy", "dynamodb:Query");

		assertEquals(2, queryOn.size(),
				"Query phải được cấp trên đúng hai index, thực tế: " + queryOn);
		for (String index : List.of(DataStack.RECENT_INDEX_V2_NAME,
				DataStack.BY_SOURCE_INDEX_NAME)) {
			assertEquals(1, queryOn.stream()
							.filter(resource -> resource.contains("/index/" + index))
							.count(),
					"phải có đúng một ARN trỏ " + index + ", thực tế: " + queryOn);
		}
		for (String resource : queryOn) {
			assertFalse(resource.contains("/index/*"),
					"KHÔNG được cấp wildcard /index/*, thực tế: " + resource);
			assertTrue(resource.contains("/index/"),
					"Query phải trỏ INDEX chứ không phải ARN bảng trần, thực tế: "
							+ resource);
		}
	}

	/**
	 * Lambda chỉ ĐỌC bảng feature-toggles — không bao giờ ghi.
	 *
	 * Bộ action đúng lấy từ bytecode togglz-dynamodb 4.6.2: builder gọi
	 * `describeTable` lúc dựng, `getFeatureState` gọi `getItem`, và `setFeatureState`
	 * gọi `updateItem`. Chỉ hai cái đầu là đường của ứng dụng.
	 *
	 * Hai vế của test này bắt hai lỗi ngược nhau, và cả hai đều IM LẶNG:
	 * - Thiếu `DescribeTable` → bean `@Lazy` ném RuntimeException ở request đầu tiên
	 *   chạm flag. Không lộ lúc khởi động, không lộ trong T2 (Floci không cưỡng chế
	 *   IAM — xem runbook §"Floci KHÔNG cưỡng chế IAM").
	 * - Có `UpdateItem` → ứng dụng ghi đè được trạng thái flag mà không ai để ý, vì
	 *   thừa quyền không tạo ra triệu chứng nào.
	 *
	 * Vế phủ định soi theo BẢNG chứ không còn theo action. Tới Phase 2, cả ba action
	 * ghi đều hợp lệ ở nơi khác — `PutItem` trên `articles`, `UpdateItem` trên
	 * `sources` — nên "Lambda không có UpdateItem" là câu sai. Câu đúng, và cũng là
	 * câu test này vẫn muốn nói từ đầu, là "không có trên bảng NÀY".
	 */
	@Test
	void lambda_chi_doc_bang_feature_toggles() {
		Template t = appStack();

		// `resourcesForAction` chứ KHÔNG phải `resourceForAction`: từ Phase 3,
		// `GetItem` được cấp trên HAI bảng (feature-toggles và articles), nên hỏi
		// "statement đầu tiên là gì" là đọc nhờ vào thứ tự khai báo trong
		// `AppStack` — đảo hai khối policy cho nhau là test đỏ mà không có gì sai.
		for (String action : List.of("dynamodb:DescribeTable", "dynamodb:GetItem")) {
			assertTrue(resourcesForAction(t, "FunctionRoleDefaultPolicy", action).stream()
							.anyMatch(resource -> resource.contains("FeatureTogglesTable")),
					"Lambda phải được " + action + " trên bảng feature-toggles");
		}
		for (String action : List.of("dynamodb:UpdateItem", "dynamodb:PutItem",
				"dynamodb:DeleteItem")) {
			for (String resource
					: resourcesForAction(t, "FunctionRoleDefaultPolicy", action)) {
				assertFalse(resource.contains("FeatureTogglesTable"),
						"Lambda KHÔNG được cấp " + action + " trên bảng feature-toggles — "
								+ "lật flag là việc của người vận hành, thực tế: " + resource);
			}
		}
	}

	/**
	 * Đường GHI mở lần đầu trong chương trình — Phase 1 cố ý chỉ có `Query`.
	 *
	 * Hai vế phụ đáng giá hơn vế "có PutItem":
	 * - Resource phải là ARN của BẢNG, không phải của index. `PutItem` trên
	 *   `…/index/gsi-recent` là thứ synth vẫn xanh, cdk-nag vẫn im, và mọi lượt
	 *   ingestion chết bằng AccessDenied trên môi trường thật. Đây là lỗi copy
	 *   dòng `Query` ngay phía trên rồi đổi mỗi action.
	 * - Đúng MỘT statement. `PutItem` là quyền ghi mạnh nhất Lambda có; nó lan
	 *   sang bảng thứ hai thì phải là một quyết định có ý thức, không phải hệ quả
	 *   phụ của việc ai đó sửa dòng `resources(...)`.
	 */
	@Test
	void ingest_ghi_duoc_vao_articles() {
		List<String> putOn = resourcesForAction(appStack(),
				"IngestFunctionRoleDefaultPolicy", "dynamodb:PutItem");

		assertEquals(1, putOn.size(),
				"PutItem phải được cấp trên đúng một bảng, thực tế: " + putOn);
		assertTrue(putOn.get(0).contains("ArticlesTable"),
				"PutItem phải trỏ vào bảng articles, thực tế: " + putOn.get(0));
		assertFalse(putOn.get(0).contains("/index/"),
				"PutItem phải trỏ vào ARN của BẢNG chứ không phải index, thực tế: "
						+ putOn.get(0));
	}

	/**
	 * AP4 (ghi `summary`) + AP8 (đọc bài cần tóm tắt). Cùng một cặp action còn
	 * được cấp trên hai bảng khác — `GetItem` trên `feature-toggles`,
	 * `UpdateItem` trên `sources` — nên phải lọc theo bảng chứ không hỏi
	 * "statement đầu tiên là gì".
	 *
	 * Vế `/index/` là vế đáng giá: cấp nhầm sang ARN index thì `cdk synth` vẫn
	 * xanh, cdk-nag vẫn im, và mọi lượt summarize chết bằng AccessDenied trên
	 * môi trường thật. Đây là lỗi copy dòng `Query` phía trên rồi đổi mỗi action,
	 * và `lambda_ghi_duoc_vao_articles` đã canh đúng nó cho `PutItem`.
	 */
	@Test
	void summarize_doc_ghi_summary_tren_arn_bang_articles() {
		Template t = appStack();

		for (String action : List.of("dynamodb:GetItem", "dynamodb:UpdateItem")) {
			List<String> on = resourcesForAction(t,
					"SummarizeFunctionRoleDefaultPolicy", action);
			List<String> onArticles = on.stream()
					.filter(resource -> resource.contains("ArticlesTable"))
					.toList();

			assertEquals(1, onArticles.size(),
					action + " phải được cấp trên bảng articles đúng một lần, thực tế: "
							+ on);
			assertFalse(onArticles.get(0).contains("/index/"),
					action + " phải trỏ ARN của BẢNG chứ không phải index, thực tế: "
							+ onArticles.get(0));
		}
	}

	/**
	 * Secret ĐẦU TIÊN của chương trình (master §8.1), nên ranh giới quanh nó
	 * được đặt một lần ở đây.
	 *
	 * `GetParametersByPath` trên `/news/*` đọc được cả image digest và mọi config
	 * tương lai — quyền thừa mà không mua thêm gì, vì ta biết chính xác tên
	 * parameter. `PutParameter` thì tệ hơn: nó cho Lambda tự ghi đè key của chính
	 * mình, trong khi key là thứ người vận hành ghi bằng credential của họ
	 * (TDD §17 #10).
	 */
	@Test
	void summarize_chi_doc_dung_mot_ssm_parameter() {
		Template t = appStack();

		List<String> readOn = resourcesForAction(t,
				"SummarizeFunctionRoleDefaultPolicy", "ssm:GetParameter");
		assertEquals(1, readOn.size(),
				"ssm:GetParameter phải được cấp trên đúng một parameter, thực tế: "
						+ readOn);
		assertTrue(readOn.get(0).contains("gemini-api-key"),
				"resource phải ghim tên parameter, thực tế: " + readOn.get(0));
		assertFalse(readOn.get(0).contains("*"),
				"resource KHÔNG được chứa wildcard, thực tế: " + readOn.get(0));

		for (String action : List.of("ssm:GetParametersByPath", "ssm:PutParameter")) {
			assertTrue(resourcesForAction(t, "SummarizeFunctionRoleDefaultPolicy", action)
							.isEmpty(),
					"summarize KHÔNG được cấp " + action);
		}
	}

	/**
	 * Hai thứ phải nói CÙNG một tên parameter: env var mà ứng dụng đọc
	 * (`news.summarization.key-parameter`) và ARN trong statement
	 * `ssm:GetParameter`. Lệch nhau thì Lambda đi đọc một parameter nó không có
	 * quyền, và triệu chứng là AccessDenied ở lượt summarize ĐẦU TIÊN trên môi
	 * trường thật — hai repo không thấy nhau nên compiler không bắt được.
	 *
	 * Khẳng định chúng khớp NHAU chứ không chép literal vào hai chỗ: chép hai
	 * lần thì sửa một chỗ quên chỗ kia vẫn xanh.
	 *
	 * KHÔNG khẳng định `NEWS_SUMMARIZATION_MODEL` ở đây: giá trị nó đặt trùng
	 * đúng default trong `application.yaml`, nên xoá env var đó không đổi hành vi
	 * gì — một assertion cho nó sẽ chỉ chép lại code chứ không canh gì.
	 */
	@Test
	void ten_parameter_trong_env_khop_arn_duoc_cap_quyen() {
		Template t = appStack();
		String keyParameter = "/news/dev/gemini-api-key";

		t.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
				"Environment", Match.objectLike(Map.of(
						"Variables", Match.objectLike(Map.of(
								"NEWS_GEMINI_KEY_PARAMETER", keyParameter)))))));

		assertTrue(resourceForAction(t, "SummarizeFunctionRoleDefaultPolicy",
						"ssm:GetParameter").endsWith(":parameter" + keyParameter),
				"ARN được cấp quyền phải trỏ đúng parameter mà env var chỉ tới, thực tế: "
						+ resourceForAction(t, "SummarizeFunctionRoleDefaultPolicy",
						"ssm:GetParameter"));
	}

	/**
	 * `kms:Decrypt` là quyền dễ để rộng nhất và khó nhìn thấy nhất khi đã rộng:
	 * trên `Resource: *` thì execution role giải mã được MỌI thứ được mã hoá
	 * trong account, và không có triệu chứng nào cho tới ngày có sự cố.
	 *
	 * SecureString của SSM dùng khoá quản lý `alias/aws/ssm`, nên ghim đúng vào
	 * đó là đủ hẹp mà vẫn chạy.
	 */
	@Test
	void kms_decrypt_ghim_ve_khoa_cua_ssm() {
		List<String> decryptOn = resourcesForAction(appStack(),
				"SummarizeFunctionRoleDefaultPolicy", "kms:Decrypt");

		assertEquals(1, decryptOn.size(),
				"kms:Decrypt phải được cấp đúng một lần, thực tế: " + decryptOn);
		assertTrue(decryptOn.get(0).contains("alias/aws/ssm"),
				"kms:Decrypt phải ghim về khoá quản lý của SSM, thực tế: "
						+ decryptOn.get(0));
	}
	// KHÔNG thêm một vế `assertFalse(resource.equals("*"))` ở đây: `Resource: *`
	// đã làm vế `contains("alias/aws/ssm")` phía trên đỏ rồi, nên vế đó không
	// bao giờ tự nổ — nó là một assertion CHẾT trông y hệt một assertion đang
	// canh. Khác với `ssm` bên trên, nơi `…/gemini-api-key*` lọt qua vế "đúng
	// tên" nên vế wildcard ở đó là vế thật.

	/**
	 * `Scan` xuất hiện lần đầu trong chương trình và CHỈ trên `sources` — bảng bị
	 * chặn trên ~30 dòng bởi master §2. Bảng `articles` tăng vô hạn và `Scan` tính
	 * tiền theo kích thước BẢNG chứ không theo số item trả về (master §4 nguyên
	 * tắc 3), nên `Scan` trên nó phải là KHÔNG THỂ, không phải "không xảy ra".
	 *
	 * Kiểm cả hai vế, và vế "không articles" KHÔNG thừa dù đã có vế "phải là
	 * sources": Resource của một statement được phép là MỘT DANH SÁCH ARN. Một
	 * statement cấp `Scan` trên cả hai bảng vẫn thoả vế thứ nhất trong khi mở đúng
	 * cái cửa mà test này tồn tại để đóng.
	 */
	@Test
	void khong_bao_gio_scan_bang_articles() {
		List<String> scanOn = resourcesForAction(appStack(),
				"IngestFunctionRoleDefaultPolicy", "dynamodb:Scan");

		assertFalse(scanOn.isEmpty(), "AP5 đọc mọi nguồn bằng Scan trên bảng sources");
		for (String resource : scanOn) {
			assertTrue(resource.contains("SourcesTable"),
					"Scan chỉ được cấp trên bảng sources, thực tế: " + resource);
		}

		// Vế phủ định quét CẢ BA role, không riêng `ingest`. Sau khi tách, "Scan
		// trên articles là KHÔNG THỂ" chỉ còn đúng nếu không role nào có nó —
		// hỏi mỗi `ingest` sẽ để `web` hoặc `summarize` mọc thêm `Scan` mà test
		// vẫn xanh.
		for (String role : List.of("FunctionRoleDefaultPolicy",
				"IngestFunctionRoleDefaultPolicy", "SummarizeFunctionRoleDefaultPolicy")) {
			for (String resource
					: resourcesForAction(appStack(), role, "dynamodb:Scan")) {
				assertFalse(resource.contains("ArticlesTable"),
						"Scan KHÔNG bao giờ được chạm bảng articles — " + role
								+ " đang có: " + resource);
			}
		}
	}

	/**
	 * Bảng `sources` là bảng duy nhất Lambda vừa đọc vừa ghi — và ranh giới nằm ở
	 * CHỖ NÀO của item nó được ghi.
	 *
	 * `UpdateItem` là AP6: `SourceRepository.updateFetchState` chỉ đụng ba
	 * attribute trạng thái (`etag`, `lastModified`, `lastFetchedAt`) bằng
	 * UpdateExpression. `PutItem` thì ghi đè cả item — nghĩa là xoá luôn `name`,
	 * `feedUrl`, `enabled`, những thứ do `sourcesSync` sở hữu và người vận hành
	 * chạy bằng credential của chính họ.
	 *
	 * Thừa `PutItem` ở đây không tạo ra triệu chứng nào cho tới ngày một bug trong
	 * ingestion tắt sạch cấu hình nguồn. Đó là lý do vế phủ định phải được canh.
	 */
	@Test
	void lambda_chi_cap_nhat_trang_thai_bang_sources() {
		Template t = appStack();

		// `resourcesForAction` chứ KHÔNG phải `resourceForAction`: từ Phase 3,
		// `UpdateItem` được cấp trên HAI bảng (sources và articles) — xem lý do ở
		// `lambda_chi_doc_bang_feature_toggles`.
		for (String action : List.of("dynamodb:Scan", "dynamodb:UpdateItem")) {
			assertTrue(resourcesForAction(t, "IngestFunctionRoleDefaultPolicy", action)
							.stream()
							.anyMatch(resource -> resource.contains("SourcesTable")),
					"ingest phải được " + action + " trên bảng sources");
		}
		for (String action : List.of("dynamodb:PutItem", "dynamodb:DeleteItem")) {
			for (String resource
					: resourcesForAction(t, "IngestFunctionRoleDefaultPolicy", action)) {
				assertFalse(resource.contains("SourcesTable"),
						"Lambda KHÔNG được cấp " + action + " trên bảng sources — ghi cấu "
								+ "hình là việc của sourcesSync, thực tế: " + resource);
			}
		}
	}

	/**
	 * SNS topic KHÔNG được bật server-side encryption, và đây là ngược trực giác
	 * nên phải có test canh.
	 *
	 * CloudWatch alarm publish vào topic bật SSE bằng `alias/aws/sns` thì **alarm
	 * action THẤT BẠI** — key policy của AWS-managed key không cho CloudWatch gọi
	 * `kms:GenerateDataKey`, và key policy của AWS-managed key thì KHÔNG SỬA ĐƯỢC.
	 * Muốn vừa mã hoá vừa chạy phải dùng customer-managed key: $1/tháng/key × 3 =
	 * $3, cùng họ với Secrets Manager mà master §8.3 cấm theo nguyên tắc.
	 *
	 * Nội dung message: tên alarm, trạng thái, account id, region, tên metric,
	 * timestamp. Không PII, không secret. `enforceSsl` vẫn bật để siết đường
	 * truyền — đó mới là thứ thật sự bảo vệ được gì ở đây.
	 */
	@Test
	void sns_khong_bat_sse_nhung_bat_ssl() {
		Template t = observabilityStack(EnvConfig.DEV);
		t.hasResourceProperties("AWS::SNS::Topic",
				Match.objectLike(Map.of("KmsMasterKeyId", Match.absent())));
		t.resourceCountIs("AWS::SNS::TopicPolicy", 1);
	}

	/**
	 * Topic policy phải có một statement ALLOW cho `cloudwatch.amazonaws.com`.
	 *
	 * ĐÃ HỎNG THẬT TRÊN CẢ dev LẪN prod, 2026-08-11, và hỏng đúng kiểu mà cả slice
	 * 1 sinh ra để loại bỏ: alarm chuyển `ALARM`, `StateReason` trỏ đúng datapoint,
	 * console nhìn hoàn hảo — và KHÔNG mail nào tới. `NumberOfMessagesPublished`
	 * của topic không có lấy một datapoint. `describe-alarm-history
	 * --history-item-type Action` là chỗ duy nhất nói ra sự thật:
	 *
	 *   "actionState": "Failed",
	 *   "error": "CloudWatch Alarms is not authorized to perform: SNS:Publish"
	 *
	 * CƠ CHẾ: `enforceSsl(true)` sinh một `AWS::SNS::TopicPolicy` chứa ĐÚNG MỘT
	 * statement, và nó là `Deny`. Gắn bất kỳ TopicPolicy nào cũng THAY THẾ policy
	 * mặc định của SNS — mà chính policy mặc định đó là nơi CloudWatch lấy quyền
	 * publish. Sau khi thay, topic không cho phép ai publish cả.
	 *
	 * Test cũ `sns_khong_bat_sse_nhung_bat_ssl` không bắt được: nó khẳng định
	 * policy TỒN TẠI (`resourceCountIs(…TopicPolicy, 1)`), không khẳng định nó CHO
	 * PHÉP gì. Một policy toàn Deny thoả nó hoàn hảo.
	 *
	 * Điều kiện `aws:SourceAccount` là vế chặn confused deputy: không có nó thì
	 * CloudWatch của BẤT KỲ account nào biết ARN này đều bơm được vào hộp thư cảnh
	 * báo. Nhưng nó cũng là vế rủi ro — sai condition key thì `Allow` không áp và
	 * ta quay lại đúng chế độ hỏng im lặng này. Chốt chặn là bước ép alarm thật sau
	 * mỗi lần deploy, không phải test này.
	 */
	@Test
	void topic_policy_cho_phep_cloudwatch_publish() {
		// Gồm cả `qa`, dù nó không có alarm thường trực: topic của nó tồn tại để một
		// alarm ad-hoc trong lúc điều tra sự cố dùng được NGAY (khỏi chờ bấm mail xác
		// nhận). Một topic không publish được thì không phục vụ được mục đích đó, và
		// người vận hành sẽ phát hiện ra điều đó đúng lúc tệ nhất.
		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			Map<String, Object> allowCloudWatch = Map.of(
					"Effect", "Allow",
					"Action", "sns:Publish",
					"Principal", Map.of("Service", "cloudwatch.amazonaws.com"),
					"Condition", Map.of("StringEquals",
							Map.of("aws:SourceAccount", cfg.account())));

			observabilityStack(cfg).hasResourceProperties("AWS::SNS::TopicPolicy",
					policyWithStatement(allowCloudWatch));
		}
	}

	/** Khớp một `AWS::SNS::TopicPolicy` có CHỨA statement mô tả bởi {@code statement}. */
	private software.amazon.awscdk.assertions.Matcher policyWithStatement(
			Map<String, Object> statement) {
		return Match.objectLike(Map.of(
				"PolicyDocument", Match.objectLike(Map.of(
						"Statement", Match.arrayWith(List.of(
								Match.objectLike(statement)))))));
	}

	/**
	 * Vế phủ định của test trên: statement `Deny` chặn non-SSL vẫn phải còn.
	 *
	 * Cách sửa "hiển nhiên" cho lỗi 2026-08-11 là bỏ `enforceSsl` — policy mặc định
	 * quay lại và alarm chạy ngay. Nó cũng làm `AwsSolutions-SNS3` đỏ, nhưng ai đó
	 * đang vội có thể thêm suppression rồi đi tiếp. Hai vế phải cùng đúng.
	 */
	@Test
	void topic_policy_van_chan_non_ssl() {
		observabilityStack(EnvConfig.DEV).hasResourceProperties("AWS::SNS::TopicPolicy",
				policyWithStatement(Map.of(
						"Effect", "Deny",
						"Sid", "AllowPublishThroughSSLOnly")));
	}

	/**
	 * Ba môi trường, ba topic độc lập, KHÔNG cross-account. Master §5 chỉ cho
	 * phép một ngoại lệ có tên cho quy tắc "không có đường giữa các account"
	 * (ECR repository policy), và "đỡ phải bấm xác nhận ba lần" không đủ nặng.
	 *
	 * Lý do quyết định lại là bán kính vụ nổ: topic chung phải nằm ở MỘT account,
	 * và đặt ở `dev` — nơi ta cố tình làm hỏng đồ — thì một sai sót ở dev làm câm
	 * cảnh báo của prod.
	 */
	@Test
	void moi_moi_truong_mot_topic_va_mot_subscription() {
		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			Template t = observabilityStack(cfg);
			t.resourceCountIs("AWS::SNS::Topic", 1);
			t.resourceCountIs("AWS::SNS::Subscription", 1);
			t.hasResourceProperties("AWS::SNS::Subscription",
					Match.objectLike(Map.of("Protocol", "email")));
		}
	}

	/**
	 * `Errors` là metric AWS cấp — miễn phí, không ăn vào hạn mức 10 custom
	 * metric của org. `NOT_BREACHING` là bắt buộc: phần lớn thời gian không có
	 * lỗi nào, và "không có dữ liệu" ở đây nghĩa là BÌNH THƯỜNG. Để mặc định
	 * (`missing`) thì alarm treo ở `INSUFFICIENT_DATA` và không bao giờ nổ.
	 */
	@Test
	void alarm_errors_o_prod_va_dev_khong_o_qa() {
		observabilityStack(EnvConfig.PROD).hasResourceProperties("AWS::CloudWatch::Alarm",
				Match.objectLike(Map.of(
						"MetricName", "Errors",
						"Namespace", "AWS/Lambda",
						"TreatMissingData", "notBreaching")));
		observabilityStack(EnvConfig.DEV).hasResourceProperties("AWS::CloudWatch::Alarm",
				Match.objectLike(Map.of("MetricName", "Errors")));
		observabilityStack(EnvConfig.QA).resourceCountIs("AWS::CloudWatch::Alarm", 0);
	}

	/**
	 * Tên alarm phải mang tiền tố môi trường. Ba topic khác nhau nhưng cùng đổ về
	 * MỘT hộp thư, và tiêu đề mail do CloudWatch sinh chỉ chứa tên alarm — không
	 * tiền tố thì người vận hành không biết mail đến từ đâu.
	 */
	@Test
	void ten_alarm_mang_tien_to_moi_truong() {
		observabilityStack(EnvConfig.PROD).hasResourceProperties("AWS::CloudWatch::Alarm",
				Match.objectLike(Map.of("AlarmName", "na-prod-function-errors")));
		observabilityStack(EnvConfig.DEV).hasResourceProperties("AWS::CloudWatch::Alarm",
				Match.objectLike(Map.of("AlarmName", "na-dev-function-errors")));
	}

	/**
	 * DLQ nhận message = một lượt chạy theo lịch đã hỏng hết retry, hoặc một
	 * article hỏng qua cả 3 lần giao lại. Cả hai đều đáng biết ngay.
	 *
	 * `ApproximateNumberOfMessagesVisible` là metric AWS cấp — miễn phí. Ngưỡng
	 * 1, không phải 20: ADR-0014 §8 đặt mốc *"> 20 message/ngày thì state `failed`
	 * mới đáng giá"*, nhưng đó là mốc để đổi THIẾT KẾ, không phải mốc để BÁO.
	 * Tính tới 2026-08-11 `SummarizeDlq` chưa nhận message nào, nên message đầu
	 * tiên là tin đáng đọc.
	 */
	@Test
	void alarm_do_sau_dlq_o_prod_va_dev() {
		Template prod = observabilityStack(EnvConfig.PROD);
		prod.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
				"AlarmName", "na-prod-ingest-dlq-depth",
				"MetricName", "ApproximateNumberOfMessagesVisible",
				"Namespace", "AWS/SQS")));
		prod.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
				"AlarmName", "na-prod-summarize-dlq-depth")));

		observabilityStack(EnvConfig.DEV).hasResourceProperties("AWS::CloudWatch::Alarm",
				Match.objectLike(Map.of("AlarmName", "na-dev-ingest-dlq-depth")));
	}

	/**
	 * `BREACHING` là toàn bộ ý nghĩa của heartbeat: nó nổ vì VẮNG MẶT dữ liệu,
	 * không vì có dữ liệu xấu. Đặt nhầm thành `NOT_BREACHING` thì alarm im lặng
	 * đúng lúc schedule bị xoá — tức mất chính chế độ hỏng duy nhất mà nó sinh ra
	 * để bắt, và mất một cách hoàn toàn không nhìn thấy được.
	 *
	 * Chỉ ở `prod`: lời hứa *"bài mới trong vòng tối đa một giờ"* của master §3.1
	 * là lời hứa của prod.
	 */
	@Test
	void heartbeat_chi_o_prod_va_no_vi_vang_mat() {
		observabilityStack(EnvConfig.PROD).hasResourceProperties("AWS::CloudWatch::Alarm",
				Match.objectLike(Map.of(
						"AlarmName", "na-prod-ingest-heartbeat",
						"TreatMissingData", "breaching",
						"ComparisonOperator", "LessThanThreshold")));

		String dev = observabilityStack(EnvConfig.DEV).toJSON().toString();
		assertFalse(dev.contains("heartbeat"),
				"heartbeat chỉ dựng ở prod — xem TDD §17 #3");
	}

	/**
	 * Pattern của metric filter phải soi `$.message` — tức phải khớp hình dạng
	 * JSON mà ứng dụng thật sự ghi ra, không phải hình dạng ta tưởng.
	 *
	 * `logging.structured.format.console: ecs` (application-aws.yaml) cho ECS
	 * JSON có `message` ở TẦNG GỐC, cạnh `log.level` và `log.logger` lồng trong
	 * `log`. Nhắm nhầm vào `$.log.message` hay soi text trần thì filter không
	 * khớp gì cả — và vì heartbeat là `TreatMissingData.BREACHING`, hậu quả
	 * KHÔNG phải im lặng mà là NỔ VĨNH VIỄN: mỗi 3 giờ một mail báo prod chết
	 * trong khi prod hoàn toàn khoẻ. Alarm hay báo động giả bị phớt lờ đúng lúc
	 * cần tin nhất.
	 *
	 * Vế `*` không thừa: bỏ nó đi thì pattern đòi `message` bằng ĐÚNG
	 * `"ingestion run xong:"`, mà dòng thật luôn có hậu tố `discovered=… added=…`.
	 * Đã đo cả ba vế trên log group prod bằng `aws logs filter-log-events`:
	 * có `*` khớp 5 event, bỏ `*` khớp 0, đổi chuỗi khớp 0.
	 */
	/**
	 * Filter phải gắn vào log group của **`ingest`**, không phải của `web`.
	 *
	 * <p>Test ngay dưới ghim PATTERN của filter, và pattern ấy đúng — nhưng không
	 * ai ghim nó gắn vào ĐÂU, nên bug này đi thẳng lên prod. Cú tách ba function
	 * (ADR-0020) cho `ingest` một log group riêng, deploy lên prod lúc **09:24
	 * UTC 2026-08-13**; metric filter ở lại log group của `web`, nơi từ đó không
	 * bao giờ có dòng `ingestion run xong` nữa. Datapoint cuối cùng của
	 * `IngestRunCompleted` là **09:00 UTC**, và alarm nổ lúc 14:08 sau đúng 3 giờ
	 * trống.
	 *
	 * <p>Tác hại không phải một mail báo động giả: vì `TreatMissingData.BREACHING`,
	 * alarm **kẹt đỏ vĩnh viễn**, nên nếu ingest chết thật thì không còn tín hiệu
	 * nào phân biệt được. Đúng điều `ObservabilityStack` tự cảnh báo — *"một alarm
	 * hay báo động giả bị phớt lờ đúng lúc cần tin nhất"*.
	 *
	 * <p>Khẳng định đi qua `Fn::ImportValue` vì log group nằm ở `AppStack`. Tên
	 * export mang logical id, nên `IngestFunctionLogGroup` trong chuỗi đó là bằng
	 * chứng đủ mạnh: đổi sang log group khác thì chuỗi đổi theo.
	 */
	@Test
	void heartbeat_soi_log_group_cua_INGEST_chu_khong_phai_cua_web() {
		observabilityStack(EnvConfig.PROD).hasResourceProperties("AWS::Logs::MetricFilter",
				Match.objectLike(Map.of("LogGroupName", Match.objectLike(Map.of(
						"Fn::ImportValue",
						Match.stringLikeRegexp(".*IngestFunctionLogGroup.*"))))));
	}

	@Test
	void metric_filter_soi_dung_truong_message_cua_ecs_json() {
		observabilityStack(EnvConfig.PROD).hasResourceProperties("AWS::Logs::MetricFilter",
				Match.objectLike(Map.of(
						"FilterPattern", "{ $.message = \"ingestion run xong:*\" }",
						"MetricTransformations", Match.arrayWith(List.of(
								Match.objectLike(Map.of(
										"MetricNamespace", "NewsAggregator",
										"MetricName", "IngestRunCompleted")))))));
	}

	/**
	 * Tổng kiểm kê: **8** alarm metric trên hạn mức 10 CỦA CẢ ORG (8 account, chia
	 * chung với một project khác). Test này là thứ duy nhất giữ con số đó khỏi
	 * trôi — mỗi phase sau thêm alarm phải sửa đúng chỗ này và nêu chỗ nó lấy từ đâu.
	 *
	 * Phase 7 Task 3 thêm `SummarizeFunctionErrors`, và nó tốn **HAI** slot chứ
	 * không phải một: `ObservabilityStack` dựng một lần mỗi môi trường, nên mọi
	 * alarm nằm ngoài nhánh `cfg == PROD` đều nhân đôi (dev + prod). Plan Task 3
	 * Step 8 viết "6 → 7" là phép tính bỏ qua điều đó; số thật là 6 → 8.
	 *
	 * Vẫn để nó chạy trên CẢ HAI, không gate về prod: `FunctionErrors` — anh em
	 * gần nhất của nó — cũng dev+prod, và master §4 ngoại lệ parity đòi mọi alarm
	 * phải được ép nổ thật trên `dev` trước khi được tin ở `prod`. Một alarm chỉ
	 * tồn tại ở prod là một alarm chưa từng được kiểm chứng.
	 *
	 * KHÔNG thêm alarm cho `ingest`: `IngestHeartbeatAlarm` (metric filter trên
	 * log) và `IngestDlqDepth` đã phủ. Còn 2 slot; phase sau phải trả lời được
	 * "cái gì bị bỏ đi" trước khi tiêu tiếp.
	 */
	@Test
	void tong_so_alarm_dung_ngan_sach_free_tier() {
		observabilityStack(EnvConfig.PROD).resourceCountIs("AWS::CloudWatch::Alarm", 5);
		observabilityStack(EnvConfig.DEV).resourceCountIs("AWS::CloudWatch::Alarm", 3);
		observabilityStack(EnvConfig.QA).resourceCountIs("AWS::CloudWatch::Alarm", 0);
	}

	/**
	 * Custom metric là tài nguyên khan hiếm nhất: 10 cho cả org, $0,30/cái khi
	 * vượt. Phase 4 dùng ĐÚNG MỘT — heartbeat. Năm alarm còn lại chạy trên metric
	 * AWS cấp miễn phí, và đó là quyết định thiết kế (§4 nguyên tắc 7), không
	 * phải may mắn.
	 */
	@Test
	void dung_dung_mot_metric_filter_trong_ca_phase() {
		observabilityStack(EnvConfig.PROD).resourceCountIs("AWS::Logs::MetricFilter", 1);
		observabilityStack(EnvConfig.DEV).resourceCountIs("AWS::Logs::MetricFilter", 0);
		observabilityStack(EnvConfig.QA).resourceCountIs("AWS::Logs::MetricFilter", 0);
	}

	/**
	 * Budget CHỈ THÔNG BÁO, không có `action`. Budget có action tính $0,10/ngày
	 * sau hai cái đầu; budget chỉ thông báo thì MIỄN PHÍ KHÔNG GIỚI HẠN. Đây là
	 * khác biệt dễ nhầm nhất trong bảng giá của Budgets.
	 *
	 * Và nó gửi email THẲNG, không qua SNS — Budgets làm được, CloudWatch alarm
	 * thì không. Bớt được một mắt xích, tức bớt một chỗ có thể im lặng.
	 */
	@Test
	void moi_moi_truong_mot_budget_chi_thong_bao() {
		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			Template t = observabilityStack(cfg);
			t.resourceCountIs("AWS::Budgets::Budget", 1);
			String json = t.toJSON().toString();
			assertFalse(json.contains("ActionType"),
					"[" + cfg.name() + "] budget phải là loại chỉ-thông-báo: budget "
							+ "có action tính $0,10/ngày sau hai cái đầu");
		}
	}

	/**
	 * Cost Anomaly Detection bắt được thứ budget không bắt: DynamoDB nhảy từ
	 * $0,002 lên $0,20 là 100× nhưng tổng vẫn dưới ngưỡng, nên budget im lặng.
	 * Master §8.3: *"vượt trần mà khối lượng chưa tăng là tín hiệu sai kiến
	 * trúc"* — thay đổi HÌNH DẠNG đáng lo hơn thay đổi TỔNG. Miễn phí.
	 */
	@Test
	void moi_moi_truong_co_cost_anomaly_monitor() {
		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			Template t = observabilityStack(cfg);
			t.resourceCountIs("AWS::CE::AnomalyMonitor", 1);
			t.resourceCountIs("AWS::CE::AnomalySubscription", 1);
		}
	}

	/**
	 * ĐÚNG MỘT dashboard, chỉ ở prod. $3,00/dashboard/tháng (AWS Pricing API,
	 * 2026-08-11), free tier 3 cái TÍNH THEO ORG. Một cái mỗi môi trường là tiêu
	 * sạch pool và không chừa gì cho project khác trong org.
	 *
	 * `dev` và `qa` là nơi ta LÀM VIỆC (Logs Insights, console); `prod` là nơi ta
	 * LIẾC NHÌN.
	 */
	@Test
	void dung_mot_dashboard_va_chi_o_prod() {
		observabilityStack(EnvConfig.PROD).resourceCountIs("AWS::CloudWatch::Dashboard", 1);
		observabilityStack(EnvConfig.DEV).resourceCountIs("AWS::CloudWatch::Dashboard", 0);
		observabilityStack(EnvConfig.QA).resourceCountIs("AWS::CloudWatch::Dashboard", 0);
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

	/**
	 * OAC tới Lambda Function URL cần ĐÚNG HAI permission, không phải một.
	 *
	 * Tài liệu AWS yêu cầu cả `lambda:InvokeFunctionUrl` VÀ `lambda:InvokeFunction`.
	 * `FunctionUrlOrigin.withOriginAccessControl()` của CDK chỉ sinh cái thứ nhất,
	 * nên thiếu cái thứ hai là mặc định im lặng.
	 *
	 * Thiếu nó thì CloudFront vẫn ký request đúng, Lambda vẫn nhận, nhưng trả
	 * 403 AccessDeniedException — và vì lỗi nằm ở tầng authorization chứ không
	 * phải chữ ký, mọi cách sửa `SourceArn` hay principal đều vô ích. Đã ngốn
	 * gần trọn buổi debug Task 14.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void oac_co_du_hai_permission_goi_lambda() {
		Template t = edgeStack();
		// HAI origin Function URL × HAI permission mỗi origin.
		t.resourceCountIs("AWS::Lambda::Permission", 4);

		// Gom theo FUNCTION rồi mới kiểm, chứ không hỏi "template có action X
		// không". Vế gộp đó XANH KHI SAI và đã đo bằng mutation: đổi permission
		// của `admin` từ `lambda:InvokeFunction` sang `lambda:InvokeFunctionUrl`
		// vẫn để lại đủ cả hai action trong template — chúng chỉ cùng thuộc về
		// `web`. Đúng cái nửa mà test này tồn tại để canh thì lọt.
		Map<String, Set<String>> actionsPerFunction = new java.util.LinkedHashMap<>();
		for (Map<String, Object> permission
				: t.findResources("AWS::Lambda::Permission").values()) {
			Map<String, Object> props = (Map<String, Object>) permission.get("Properties");
			assertEquals("cloudfront.amazonaws.com", props.get("Principal"),
					"permission này không phải của CloudFront: " + props);
			// `SourceArn` ghim về đúng distribution. Thiếu nó thì MỌI distribution
			// CloudFront trên thế giới gọi được Function URL của ta.
			assertTrue(String.valueOf(props.get("SourceArn")).contains("distribution/"),
					"permission phải ghim SourceArn về distribution, thực tế: " + props);
			actionsPerFunction
					.computeIfAbsent(String.valueOf(props.get("FunctionName")),
							k -> new java.util.TreeSet<>())
					.add(String.valueOf(props.get("Action")));
		}

		assertEquals(2, actionsPerFunction.size(),
				"hai function có Function URL, mỗi cái một nhóm permission, thực tế: "
						+ actionsPerFunction.keySet());
		actionsPerFunction.forEach((function, actions) -> assertEquals(
				Set.of("lambda:InvokeFunction", "lambda:InvokeFunctionUrl"), actions,
				"function `" + function + "` thiếu permission — CloudFront sẽ ký đúng, "
						+ "Lambda vẫn nhận, và trả 403 AccessDeniedException"));
	}

	/**
	 * Distribution phải khai báo TƯỜNG MINH web ACL mà CloudFront đã tự gắn.
	 *
	 * Khi account bật CloudFront pricing plan, CloudFront tự tạo một web ACL và
	 * gắn vào distribution — ngoài tầm CDK. Template không khai báo `WebACLId`
	 * nên mỗi lần update, CloudFormation gửi giá trị rỗng, AWS hiểu là "gỡ web
	 * ACL" và từ chối: *"Distributions with a pricing plan subscription must
	 * have a web ACL resource."*
	 *
	 * Đây là drift out-of-band làm HỎNG MỌI LẦN DEPLOY EdgeStack về sau, không
	 * riêng lần đầu gặp. Ghim ARN vào EnvConfig là cách đưa nó trở lại IaC mà
	 * không phải tự tạo web ACL mới — web ACL tự tạo có nguy cơ nằm ngoài phạm
	 * vi miễn phí của pricing plan, trái master §4 nguyên tắc 3.
	 */
	@Test
	void distribution_giu_web_acl_cua_pricing_plan() {
		edgeStack().hasResourceProperties("AWS::CloudFront::Distribution",
				Match.objectLike(Map.of(
						"DistributionConfig", Match.objectLike(Map.of(
								"WebACLId", EnvConfig.DEV.wafWebAclArn())
						))
				));
	}

	/**
	 * Distribution KHÔNG ĐƯỢC có `CustomErrorResponses`.
	 *
	 * `CustomErrorResponses` là cấu hình cấp DISTRIBUTION, CloudFront không cho
	 * scope theo cache behavior. Nên rule "403/404 → 200 /index.html" dựng cho
	 * SPA deep link sẽ áp luôn cho `/api/*`: mọi lỗi 403/404 của API trở thành
	 * HTTP 200 kèm HTML.
	 *
	 * Hậu quả nặng hơn là mất khả năng chẩn đoán — `curl /api/health` trả 200 và
	 * trang HTML dù backend hỏng hoàn toàn, và smoke test 403 ở Task 14 Step 5
	 * không bao giờ thấy 403. Đã trả giá thật một lần khi debug Task 14.
	 *
	 * SPA deep link quay lại ở Task 27 dưới dạng CloudFront Function
	 * (viewer-request) gắn RIÊNG vào default behavior — thứ mà error response
	 * không làm được.
	 */
	@Test
	void loi_cua_api_khong_bi_nguy_trang_thanh_200() {
		edgeStack().hasResourceProperties("AWS::CloudFront::Distribution",
				Match.objectLike(Map.of(
						"DistributionConfig", Match.objectLike(Map.of(
								"CustomErrorResponses", Match.absent())
						))
				));
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

	/**
	 * `visibilityTimeout` phải ≥ 6 × function timeout + batch window.
	 *
	 * Nhỏ hơn function timeout thì Lambda TỪ CHỐI tạo event source mapping —
	 * lỗi lộ ngay lúc deploy, dễ. Nguy hiểm là khoảng ở giữa: đủ để tạo ESM
	 * nhưng không đủ 6×, khi đó message tái hiện TRONG LÚC invoke vẫn đang xử lý
	 * nó, một invoke thứ hai nhận cùng message, và cùng một article bị gọi model
	 * hai lần. Triệu chứng duy nhất là hoá đơn.
	 */
	@Test
	void queue_summarize_co_visibility_timeout_du_lon() {
		appStack().hasResourceProperties("AWS::SQS::Queue", Match.objectLike(Map.of(
				"VisibilityTimeout", 780)));
	}

	/**
	 * `maxReceiveCount = 3`. Không có redrive policy thì message hỏng quay lại
	 * queue vô hạn — và với sweep đẩy lại mỗi 6 giờ, đó là một vòng lặp không
	 * có điểm dừng nào ngoài cửa sổ 48h.
	 */
	@Test
	void queue_summarize_co_dlq_voi_max_receive_count_3() {
		appStack().hasResourceProperties("AWS::SQS::Queue", Match.objectLike(Map.of(
				"RedrivePolicy", Match.objectLike(Map.of("maxReceiveCount", 3)))));
	}

	/**
	 * `FunctionResponseTypes: [ReportBatchItemFailures]`.
	 *
	 * Đây là mục quan trọng nhất trong ba mục SQS. Thiếu nó thì Lambda BỎ QUA
	 * response `batchItemFailures` mà `SummarizeHandler` trả về — không lỗi,
	 * không cảnh báo — và coi cả batch là thành công. Message hỏng bị xoá khỏi
	 * queue, DLQ không bao giờ nhận gì, và một article hỏng biến mất im lặng.
	 */
	@Test
	void esm_bat_report_batch_item_failures() {
		appStack().hasResourceProperties("AWS::Lambda::EventSourceMapping",
				Match.objectLike(Map.of(
						"BatchSize", 10,
						"MaximumBatchingWindowInSeconds", 60,
						"FunctionResponseTypes", List.of("ReportBatchItemFailures"))));
	}

	/**
	 * KHÔNG có `ScalingConfig`. Ngược trực giác và có tài liệu: đặt
	 * `MaximumConcurrency` tắt mất tối ưu hoá của Lambda khi queue rỗng
	 * ("can optimize to as few as 2 concurrent invokes to reduce the Amazon SQS
	 * calls… this optimization is not available when you enable the maximum
	 * concurrency setting"), tức làm ESM gọi SQS nhiều hơn 24/7 — chi phí CỐ
	 * ĐỊNH, thứ master §4 nguyên tắc 3 loại theo nguyên tắc. Van thật là hạn
	 * mức ở `SummarizationQueue.enqueue()`.
	 */
	@Test
	void esm_khong_dat_max_concurrency() {
		appStack().hasResourceProperties("AWS::Lambda::EventSourceMapping",
				Match.objectLike(Map.of("ScalingConfig", Match.absent())));
	}

	/**
	 * Tầng ② của ADR-0015. `retryAttempts` + Scheduler DLQ canh tầng ① (giao
	 * việc); khi Lambda đã trả `202 Accepted` thì Scheduler coi như XONG và không
	 * bao giờ biết hàm chạy ra sao. Thứ giữ lại sự kiện hỏng ở tầng ② là
	 * `onFailure` destination của FUNCTION.
	 *
	 * Trỏ về chính `IngestDlq` đang có: một hàng đợi, hai nguồn. Người vận hành
	 * chỉ phải nhìn một chỗ cho cùng một loại sự cố — "một lượt chạy theo lịch
	 * không hoàn thành".
	 */
	@Test
	void function_co_on_failure_destination_tro_ve_ingest_dlq() {
		// HAI EventInvokeConfig từ Task 3: `ingest` và `summarize` chạy bất đồng bộ
		// độc lập nhau, nên mỗi cái phải tự khai tầng ② của mình. Đếm thay vì
		// `hasResourceProperties`: cái sau xanh khi chỉ MỘT trong hai có destination.
		appStack().resourceCountIs("AWS::Lambda::EventInvokeConfig", 2);
		appStack().hasResourceProperties("AWS::Lambda::EventInvokeConfig",
				Match.objectLike(Map.of("DestinationConfig",
						Match.objectLike(Map.of("OnFailure", Match.anyValue())))));

		// Cả hai role phải có `sqs:SendMessage` trên `IngestDlq` — do
		// `SqsDestination.bind()` tự cấp. Thiếu ở một trong hai nghĩa là sự kiện
		// hỏng của function đó rơi mất, chỉ còn metric `AsyncEventsDropped`.
		for (String role : List.of("IngestFunctionRoleDefaultPolicy",
				"SummarizeFunctionRoleDefaultPolicy")) {
			List<String> sendOn = resourcesForAction(appStack(), role, "sqs:SendMessage");
			assertTrue(sendOn.stream().anyMatch(r -> r.contains("IngestDlq")),
					"onFailure destination của " + role
							+ " cần sqs:SendMessage trên IngestDlq, thực tế: " + sendOn);
		}
	}

	/**
	 * `qa` không có Schedule nào nên không có `IngestDlq` — và vì thế cũng không
	 * được có `EventInvokeConfig` trỏ vào hư vô.
	 */
	@Test
	void qa_khong_co_on_failure_destination() {
		appStack(EnvConfig.QA).resourceCountIs("AWS::Lambda::EventInvokeConfig", 0);
	}

	/**
	 * Execution role được gửi và nhận trên queue summarize, nhưng KHÔNG có
	 * quyền nào trên DLQ — chỉ dịch vụ SQS ghi vào đó. Cấp quyền thừa lên DLQ
	 * nghĩa là một bug trong code có thể dọn sạch bằng chứng của chính nó.
	 *
	 * Chỉ soi resource IAM, KHÔNG soi cả stack: `RedrivePolicy` của queue
	 * summarize trỏ tới DLQ một cách hoàn toàn hợp lệ, nên tìm trên `toJSON()`
	 * của cả stack thì luôn dương tính giả.
	 *
	 * So bằng TIỀN TỐ logical ID. CDK nối hash 8 ký tự vào sau construct id
	 * ("SummarizeDlq" → "SummarizeDlq3F7A21C9"), nên bất kỳ chuỗi nào gõ tay kèm
	 * ký tự ĐỨNG SAU tên — `SummarizeDlq","Arn"` chẳng hạn — sẽ không bao giờ
	 * khớp, và một `assertFalse` dựa trên nó thành xanh vĩnh viễn mà không kiểm
	 * gì cả.
	 */
	@Test
	void lambda_khong_co_quyen_nao_tren_dlq() {
		Template template = appStack();
		String iam = template.findResources("AWS::IAM::Policy")
				+ template.findResources("AWS::IAM::Role").toString();

		assertFalse(iam.contains("SummarizeDlq"),
				"execution role không được có quyền nào trên DLQ");
	}

	/**
	 * `xray:PutTraceSegments` — action mà OTLP endpoint
	 * (`https://xray.<region>.amazonaws.com/v1/traces`) THẬT SỰ authorize, đo bằng
	 * một lượt export thật chứ không suy từ tài liệu.
	 *
	 * Bản đầu của test này ghim `xray:PutSpans` và ghim rất tự tin: service
	 * authorization reference mô tả `PutSpans` đúng chữ *"upload OpenTelemetry spans
	 * to AWS X-Ray"*, không câu nào khớp hơn thế. Nó vẫn sai. Trang collector-less
	 * ADOT SDK — đúng kịch bản của ta — bảo gắn `AWSXrayWriteOnlyPolicy`, thứ chỉ
	 * chứa `PutTraceSegments`, và endpoint trả 403 nêu đích danh action đó.
	 *
	 * Bài học phải giữ: cả TEST NÀY lẫn `cdk synth` lẫn cdk-nag đều XANH với action
	 * sai. Không tầng tĩnh nào phân biệt được hai chuỗi ấy — chỉ AWS phân biệt được,
	 * và nó chỉ nói khi có span thật đi qua dây. Một test ghim hằng số chỉ chứng
	 * minh hằng số không đổi, không chứng minh hằng số ĐÚNG.
	 *
	 * X-Ray không hỗ trợ resource-level permission cho action ghi trace, nên
	 * `Resource: "*"` là bắt buộc chứ không phải cẩu thả — và chính vì thế entry
	 * cdk-nag phải CÓ THAM SỐ, không được để trống.
	 */
	@Test
	void execution_role_ghi_duoc_trace_len_xray() {
		List<String> on = resourcesForAction(appStack(), "FunctionRoleDefaultPolicy",
				"xray:PutTraceSegments");
		assertEquals(List.of("*"), on,
				"X-Ray không có resource-level permission cho action ghi trace");
	}

	/**
	 * Endpoint và service name đi qua env var chứ không hằng số trong code: local
	 * trỏ `otel-lgtm`, prod trỏ X-Ray. CÙNG bộ instrumentation, khác exporter —
	 * đúng chữ master §8.2.
	 *
	 * Tên biến là `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` — bản có SIGNAL. Biến dạng
	 * chung `OTEL_EXPORTER_OTLP_ENDPOINT` được Boot 4.1 map vào cả ba endpoint
	 * trace/metric/log cùng lúc, mà X-Ray chỉ nhận trace; đường metric và log khi
	 * đó chỉ im lặng nhờ hai dòng `enabled: false` trong `application.yaml`, tức
	 * một sửa đổi vô hại bên repo app cũng đủ làm nó gửi rác lên X-Ray.
	 */
	@Test
	void env_var_otlp_tro_ve_xray() {
		// Tên service MANG HẬU TỐ PROFILE từ Task 3. Ba process cùng tên service
		// làm service map của X-Ray gộp chúng lại và câu hỏi "lượt hỏng này thuộc
		// function nào" mất câu trả lời. Kiểm cả ba, không chỉ một: một function
		// bị quên hậu tố sẽ lẫn vào đúng cái đống ta vừa tách ra.
		for (String profile : List.of("web", "ingest", "summarize")) {
			appStack().hasResourceProperties("AWS::Lambda::Function",
					Match.objectLike(Map.of("Environment", Map.of("Variables",
							Match.objectLike(Map.of(
									"OTEL_SERVICE_NAME", "news-aggregator-" + profile,
									"OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
									"https://xray.us-east-1.amazonaws.com/v1/traces"))))));
		}
	}

	/**
	 * Phủ định của test trên, và nó canh một chế độ hỏng KHÔNG có triệu chứng: biến
	 * dạng chung vẫn làm trace chạy đúng, nên không ai phát hiện ra cho tới lúc đọc
	 * hoá đơn hoặc tới lúc ai đó gỡ `enabled: false` bên app.
	 */
	@Test
	void khong_dung_bien_otlp_dang_chung() {
		String env = appStack().findResources("AWS::Lambda::Function").toString();
		assertFalse(env.contains("OTEL_EXPORTER_OTLP_ENDPOINT"),
				"dùng OTEL_EXPORTER_OTLP_TRACES_ENDPOINT — bản có signal");
	}

	/**
	 * TẬP ĐÓNG — trả nợ Phase 3 §20B #2, nay nhân ba theo số execution role.
	 *
	 * Mọi test khác trong file này hỏi *"quyền X có bị khoá đúng phạm vi không?"*.
	 * Không cái nào hỏi *"ở đây có quyền nào chưa ai viết test cho nó không?"* —
	 * nên bộ quyền là TẬP MỞ, và một dòng `addToPolicy` thêm ở Phase 8 lọt qua cả
	 * suite còn lại.
	 *
	 * Món nợ thật không phải "role có N nhóm quyền" mà là **"role lớn lên mà không
	 * ai phải quyết định gì"**. Ba test này không làm role nào nhỏ đi; chúng biến
	 * việc role lớn lên thành một HÀNH VI CÓ Ý THỨC.
	 *
	 * Phase 7 thay MỘT tập đóng bằng BA. Bản một-role cũ hỏi `FunctionRoleDefault‐
	 * Policy` với danh sách 17 action gộp của cả ba vai; sau khi tách, danh sách đó
	 * vừa quá rộng cho `web` (nó chỉ còn 6) vừa mù với hai role mới. Và nó là phép
	 * kiểm TẬP CON, nên một quyền BIẾN MẤT vẫn xanh — chính xác cái đã xảy ra khi
	 * `web` mất `PutItem`.
	 *
	 * ⚠️ KHI MỘT TRONG BA TEST NÀY ĐỎ: thêm action vào đúng danh sách của nó **kèm
	 * một dòng nói ai dùng nó và ở đường code nào**. TUYỆT ĐỐI KHÔNG gộp ba danh
	 * sách lại, không đổi sang phép kiểm tập con, không thêm wildcard. Nới nó là
	 * vứt đúng thứ nó sinh ra để giữ.
	 */
	private static final Set<String> WEB_ACTIONS = Set.of(
			"logs:CreateLogStream",   // Lambda runtime ghi log
			"logs:PutLogEvents",      // Lambda runtime ghi log
			"xray:PutTraceSegments",  // exporter OTLP gửi span tới X-Ray endpoint
			// findRecent (gsi-recent-v2) VÀ findRecentBySources (gsi-by-source) —
			// hai index, hai statement, cùng một action.
			"dynamodb:Query",
			"dynamodb:DescribeTable", // togglz-dynamodb dựng repository
			// Togglz đọc flag (feature-toggles), phân giải cookie → phiên (sessions,
			// AP12), và từ Task 19 đọc lựa chọn nguồn (user-preferences, AP13). Cùng
			// một action trên ba bảng: phạm vi theo BẢNG được canh ở
			// `web_khong_ghi_duoc_articles_khong_goi_duoc_gemini`.
			"dynamodb:GetItem",
			// PutItem trên `sessions` (phiên mới, VÀ trượt TTL — `save()` ghi đè cả
			// item) và trên `user-preferences` (lưu lựa chọn nguồn — xem
			// `web_doc_ghi_user_preferences_nhung_khong_xoa`).
			"dynamodb:PutItem",
			"dynamodb:DeleteItem",    // đăng xuất — xoá phiên, không chỉ xoá cookie
			// KHÔNG có `dynamodb:UpdateItem`, và sự vắng mặt đó là CÓ Ý. Bản trước
			// khai nó với chú thích "trượt TTL `expiresAt`" — mô tả sai:
			// `DynamoDbSessionRepository` không bao giờ gọi `updateItem`, TTL trượt
			// bằng `PutItem` ghi đè cả item. Gỡ 2026-08-19, sau khi lộ ra lúc cấp
			// quyền cho `admin` (Task 26). Nếu nó quay lại, test này đỏ với
			// "có quyền KHÔNG khai báo" — đó là toàn bộ lý do tập đóng tồn tại.
			// `Scan` CHỈ trên bảng `sources` (Task 19): `GET /api/sources` dựng hàng
			// chip cho slice 4. Lần đầu function phục vụ Internet đọc bảng đó —
			// chấp nhận được vì bảng bị chặn trên ~30 dòng bởi master §2 và endpoint
			// là công khai. Phạm vi được canh ở `web_scan_duoc_sources_va_chi_sources`.
			"dynamodb:Scan",
			// Hai action dưới là secret THỨ HAI của chương trình (Task 8): client
			// secret của Cognito, đọc LƯỜI ở Task 10 — chỉ khi có người đăng nhập.
			"ssm:GetParameter",       // SsmClientRegistrationRepository
			"kms:Decrypt");           // giải mã SecureString bằng alias/aws/ssm

	private static final Set<String> INGEST_ACTIONS = Set.of(
			"logs:CreateLogStream",
			"logs:PutLogEvents",
			"xray:PutTraceSegments",
			"dynamodb:PutItem",       // catalog ghi article mới (articles)
			"dynamodb:Scan",          // SourceRepository liệt kê nguồn (CHỈ sources)
			"dynamodb:UpdateItem",    // SourceRepository.updateFetchState (sources)
			"dynamodb:DescribeTable", // togglz
			"dynamodb:GetItem",       // togglz
			"sqs:SendMessage",        // enqueue summarize; và onFailure ghi IngestDlq
			// Hai action dưới do `SqsDestination.bind()` TỰ cấp cho onFailure
			// destination — không dòng code nào trong AppStack viết ra chúng. Có mặt
			// ở đây là ghi nhận có ý thức, không phải bỏ sót.
			"sqs:GetQueueAttributes",
			"sqs:GetQueueUrl");

	private static final Set<String> SUMMARIZE_ACTIONS = Set.of(
			"logs:CreateLogStream",
			"logs:PutLogEvents",
			"xray:PutTraceSegments",
			"dynamodb:GetItem",       // AP8 đọc bài cần tóm tắt; và togglz
			"dynamodb:UpdateItem",    // AP4 gắn summary vào article
			"dynamodb:Query",         // AP9 sweep trên gsi-recent-v2
			"dynamodb:DescribeTable", // togglz
			"sqs:SendMessage",        // sweep là PRODUCER; và onFailure ghi IngestDlq
			"sqs:GetQueueAttributes", // ESM + SqsDestination
			"sqs:GetQueueUrl",        // ESM + SqsDestination
			// Ba action dưới do `addEventSource` tự cấp cho consumer.
			"sqs:ReceiveMessage",
			"sqs:DeleteMessage",
			"sqs:ChangeMessageVisibility",
			"ssm:GetParameter",       // GeminiKeyProvider đọc SecureString
			"kms:Decrypt");           // giải mã SecureString bằng alias/aws/ssm

	/**
	 * Tập đóng THỨ TƯ, và là tập duy nhất có đường GHI vào bảng flag.
	 *
	 * Bộ action này đọc ra từ MÃ NGUỒN chứ không từ hình dung về việc console
	 * làm gì — hai chỗ dễ đoán sai nhất:
	 *
	 * <ul>
	 * <li><b>KHÔNG có `dynamodb:Scan`.</b> Danh sách flag mà console hiển thị
	 * đến từ `EnumBasedFeatureProvider` (enum `NewsFeature`), không từ bảng;
	 * `DynamoDBStateRepository` 4.6.2 chỉ gọi `describeTable`, `getItem` và
	 * `updateItem` — không có một lời gọi `scan` nào trong toàn class. Cấp
	 * `Scan` ở đây là cấp một quyền không đường code nào dùng.</li>
	 * <li><b>`PutItem` + `DeleteItem` trên `sessions`, KHÔNG phải `UpdateItem`.</b>
	 * TTL trượt của phiên được `DynamoDbSessionRepository.save()` thực hiện bằng
	 * `PutItem` ghi đè cả item, và `SessionRepositoryFilter` gọi `save()` ở CUỐI
	 * mọi request có chạm session — tức mọi request đã đăng nhập vào console.
	 * `DeleteItem` là nhánh `findById` thấy phiên quá hạn rồi tự dọn. Cấp
	 * `UpdateItem` thay cho hai cái đó thì console hoạt động đúng một lần rồi
	 * chết bằng AccessDenied ở request thứ hai — và chỉ trên môi trường thật,
	 * vì Floci không cưỡng chế IAM.</li>
	 * </ul>
	 */
	private static final Set<String> ADMIN_ACTIONS = Set.of(
			"logs:CreateLogStream",   // Lambda runtime ghi log
			"logs:PutLogEvents",      // Lambda runtime ghi log
			"xray:PutTraceSegments",  // exporter OTLP gửi span tới X-Ray endpoint
			"dynamodb:DescribeTable", // togglz-dynamodb dựng repository
			// togglz đọc flag (feature-toggles) VÀ phân giải cookie → phiên
			// (sessions). Phạm vi theo BẢNG được canh ở
			// `admin_chi_cham_feature_toggles_va_sessions`.
			"dynamodb:GetItem",
			// LÝ DO TỒN TẠI của function này: `DynamoDBStateRepository
			// .setFeatureState` lật flag bằng `UpdateItem`. Đây là đường ghi
			// DUY NHẤT vào bảng flag trong toàn hệ thống.
			"dynamodb:UpdateItem",
			"dynamodb:PutItem",       // `save()` của phiên — TTL trượt
			"dynamodb:DeleteItem",    // `findById` dọn phiên đã quá hạn
			"ssm:GetParameter",       // SsmClientRegistrationRepository
			"kms:Decrypt");           // giải mã SecureString bằng alias/aws/ssm

	@Test
	void tap_dong_quyen_cua_web() {
		assertClosedActionSet("Function", WEB_ACTIONS);
	}

	@Test
	void tap_dong_quyen_cua_ingest() {
		assertClosedActionSet("IngestFunction", INGEST_ACTIONS);
	}

	@Test
	void tap_dong_quyen_cua_summarize() {
		assertClosedActionSet("SummarizeFunction", SUMMARIZE_ACTIONS);
	}

	@Test
	void tap_dong_quyen_cua_admin() {
		assertClosedActionSet("AdminFunction", ADMIN_ACTIONS);
	}

	/**
	 * Đây là lý do function thứ tư tồn tại. Nếu test này xanh mà ba role kia
	 * cũng ghi được, cả slice 5 là công cốc.
	 *
	 * Vế phủ định quét CẢ BA role còn lại, không riêng `web`: câu mà `AppStack`
	 * khẳng định là *"đường GHI DUY NHẤT vào bảng flag trong toàn hệ thống"*, và
	 * hỏi mỗi `web` sẽ để `ingest` hoặc `summarize` mọc thêm `UpdateItem` mà test
	 * vẫn xanh.
	 */
	@Test
	void chi_admin_ghi_duoc_feature_toggles() {
		Template template = appStack(EnvConfig.DEV);

		for (String role : List.of("Function", "IngestFunction", "SummarizeFunction")) {
			List<Map<String, Object>> statements = statementsOfRole(template, role);
			assertFalse(statements.isEmpty(),
					"không đọc được statement nào của role `" + role + "` — prefix tra sai?");
			assertTrue(statements.stream()
							.noneMatch(s -> actionsOf(s).contains("dynamodb:UpdateItem")
									&& resourcesOf(s).stream()
											.anyMatch(r -> r.contains("FeatureTogglesTable"))),
					"`" + role + "` chỉ ĐỌC feature-toggles — lật flag là việc của `admin`");
		}

		assertTrue(statementsOfRole(template, "AdminFunction").stream()
						.anyMatch(s -> actionsOf(s).contains("dynamodb:UpdateItem")
								&& resourcesOf(s).stream()
										.anyMatch(r -> r.contains("FeatureTogglesTable"))),
				"`admin` phải GHI được feature-toggles — không có nó thì console chỉ là "
						+ "một trang đọc");
	}

	/**
	 * Hai bảng, và ĐÚNG hai bảng. `admin` không có việc gì với `articles`,
	 * `sources` hay `user-preferences`, và một dòng `addToPolicy` chép nhầm từ
	 * khối `web` ngay bên trên sẽ không tạo ra triệu chứng nào.
	 */
	@Test
	void admin_chi_cham_feature_toggles_va_sessions() {
		List<Map<String, Object>> statements =
				statementsOfRole(appStack(EnvConfig.DEV), "AdminFunction");
		assertFalse(statements.isEmpty(),
				"không đọc được statement nào của role `admin` — prefix tra sai?");

		for (Map<String, Object> statement : statements) {
			if (actionsOf(statement).stream().noneMatch(a -> a.startsWith("dynamodb:"))) {
				continue;
			}
			for (String resource : resourcesOf(statement)) {
				assertTrue(resource.contains("FeatureTogglesTable")
								|| resource.contains("SessionsTable"),
						"`admin` chỉ chạm feature-toggles và sessions, thực tế: " + resource);
			}
		}

		// Vế ngược: `sessions` KHÔNG được nhận `UpdateItem`. Đó là action mà
		// `DynamoDbSessionRepository` không bao giờ gọi, nên cấp nó nghĩa là đang
		// mô tả sai cách phiên trượt hạn — và một mô tả sai trong policy sống rất
		// lâu vì thừa quyền không hỏng gì.
		for (Map<String, Object> statement : statements) {
			if (!actionsOf(statement).contains("dynamodb:UpdateItem")) {
				continue;
			}
			for (String resource : resourcesOf(statement)) {
				assertFalse(resource.contains("SessionsTable"),
						"`UpdateItem` trên sessions là quyền chết — repository ghi bằng "
								+ "PutItem, thực tế: " + resource);
			}
		}
	}

	/**
	 * Hai Function URL, CẢ HAI private. `admin` mà public là một trang quản trị
	 * nằm trần trên Internet.
	 *
	 * `allResourcesProperties` chứ KHÔNG `hasResourceProperties`: cái sau xanh
	 * khi CHỈ MỘT resource khớp, nên nó sẽ nói "có AWS_IAM" ngay cả khi URL thứ
	 * hai để NONE — đúng cái nửa mà test này tồn tại để canh.
	 */
	@Test
	void function_url_cua_admin_cung_la_AWS_IAM() {
		Template template = appStack(EnvConfig.DEV);
		template.resourceCountIs("AWS::Lambda::Url", 2);
		template.allResourcesProperties("AWS::Lambda::Url",
				Match.objectLike(Map.of("AuthType", "AWS_IAM")));
	}

	/**
	 * Kiểm trên CẢ BA môi trường, như bản một-role đã làm — một role phình ra chỉ
	 * ở `prod` là đúng kịch bản mà tập đóng tồn tại để bắt.
	 *
	 * `dev` và `prod` kiểm BẰNG NHAU tuyệt đối. `qa` chỉ kiểm TẬP CON, và đó là
	 * khác biệt có thật chứ không phải nới lỏng cho tiện: `qa` không có Schedule
	 * nào (`EnvConfig.QA` để null cả `ingestionRate` lẫn `sweepRate`) nên không có
	 * `configureAsyncInvoke`, nên `SqsDestination.bind()` không chạy và role
	 * `ingest` của `qa` thiếu đúng `sqs:GetQueueAttributes` + `sqs:GetQueueUrl`.
	 * Ép bằng nhau ở đó sẽ buộc phải khai một danh sách thứ tư chỉ để mô tả một
	 * môi trường không chạy gì.
	 */
	private void assertClosedActionSet(String functionId, Set<String> khaiBao) {
		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			Set<String> thucTe = statementsOfRole(appStack(cfg), functionId).stream()
					.flatMap(s -> actionsOf(s).stream())
					.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

			// Không có dòng này thì mọi vế dưới xanh rỗng khi prefix tra sai —
			// và một tập đóng rỗng trông y hệt một tập đóng sạch.
			assertFalse(thucTe.isEmpty(),
					"[" + cfg.name() + "] không đọc được action nào của role `"
							+ functionId + "` — prefix tra sai?");

			Set<String> thua = new java.util.TreeSet<>(thucTe);
			thua.removeAll(khaiBao);
			assertTrue(thua.isEmpty(),
					"[" + cfg.name() + "] role `" + functionId + "` có quyền KHÔNG khai "
							+ "báo: " + thua + " — thêm vào danh sách KÈM LÝ DO, đừng nới test.");

			if (cfg == EnvConfig.QA) {
				continue;
			}
			Set<String> thieu = new java.util.TreeSet<>(khaiBao);
			thieu.removeAll(thucTe);
			assertTrue(thieu.isEmpty(),
					"[" + cfg.name() + "] danh sách khai `" + functionId + "` có quyền mà "
							+ "role KHÔNG có: " + thieu + " — quyền đã bị gỡ khỏi AppStack "
							+ "thì gỡ luôn khỏi đây, đừng để danh sách mô tả quá khứ.");
		}
	}

	@Test
	void bon_function_bon_role_bon_bo_trigger() {
		Template template = appStack(EnvConfig.DEV);

		template.resourceCountIs("AWS::Lambda::Function", 4);
		// Bốn function dùng chung một log group thì log của người đọc, log của
		// lượt summarize và log của mặt phẳng vận hành trộn vào nhau, và
		// retention/quyền không tách được nữa.
		template.resourceCountIs("AWS::Logs::LogGroup", 4);

		// Ghim theo TÊN chứ không theo TỔNG SỐ role. Tổng không phải 4: Scheduler
		// tự sinh `SchedulerRoleForTarget` cho mỗi function được nhắm, và sau khi
		// tách thì hai Schedule trỏ hai function khác nhau nên có hai role loại đó
		// (bản một-function dùng chung một). Đếm tổng sẽ biến một role lạ mọc thêm
		// thành thứ test phải nới số, thay vì thứ test phải chỉ mặt.
		Set<String> roleIds = template.findResources("AWS::IAM::Role").keySet();
		for (String prefix : List.of("FunctionRole", "AdminFunctionRole",
				"IngestFunctionRole", "SummarizeFunctionRole")) {
			assertTrue(roleIds.stream().anyMatch(id -> id.startsWith(prefix)),
					"thiếu execution role `" + prefix + "` — đang có: " + roleIds);
		}
		// ĐÚNG HAI Function URL — `web` và `admin`. `ingest` và `summarize` không
		// có đường HTTP nào từ Internet, đó là nửa còn lại của việc thu hẹp blast
		// radius. Cả hai URL đều `AWS_IAM`, canh ở
		// `function_url_cua_admin_cung_la_AWS_IAM`.
		template.resourceCountIs("AWS::Lambda::Url", 2);
		// Một ESM (SQS → summarize), hai Schedule (ingest-feeds → ingest,
		// summarize-sweep → summarize). `admin` KHÔNG có trigger nào ngoài HTTP.
		template.resourceCountIs("AWS::Lambda::EventSourceMapping", 1);
		template.resourceCountIs("AWS::Scheduler::Schedule", 2);
	}

	@Test
	void web_khong_ghi_duoc_articles_khong_goi_duoc_gemini() {
		// Đây là LÝ DO TỒN TẠI của cả slice 1. Nếu chỉ giữ được một test của
		// task này thì giữ test này.
		Template template = appStack(EnvConfig.DEV);
		List<Map<String, Object>> statements = statementsOfRole(template, "Function");

		// Ba assertion dưới đều PHỦ ĐỊNH, nên chúng xanh một cách rỗng nếu prefix
		// tra nhầm và `statements` rỗng. Đổi logical id của `web` mà quên sửa test
		// là đúng cái kịch bản đó — và nó sẽ trông y hệt một test đang canh.
		assertFalse(statements.isEmpty(),
				"không đọc được statement nào của role `web` — prefix tra sai?");

		// Từ Task 7, `web` CÓ action ghi — nên câu hỏi đổi từ "có ghi không" sang
		// "ghi ở ĐÂU". Vế cũ ("không action ghi nào") nay là câu sai, và giữ nó
		// nghĩa là phải chọn giữa việc xoá nó đi hay nới nó thành vô nghĩa.
		//
		// Kiểm theo TỪNG statement chứ không gộp: `Resource` được phép là một
		// DANH SÁCH ARN, nên một statement cấp `PutItem` trên cả `sessions` lẫn
		// `articles` phải đỏ — cặp action/resource chỉ có nghĩa trong phạm vi một
		// statement (xem `statementsOf`).
		Set<String> ghiDynamo = Set.of("dynamodb:PutItem", "dynamodb:UpdateItem",
				"dynamodb:DeleteItem", "dynamodb:BatchWriteItem");
		for (Map<String, Object> statement : statements) {
			if (actionsOf(statement).stream().noneMatch(ghiDynamo::contains)) {
				continue;
			}
			for (String resource : resourcesOf(statement)) {
				assertTrue(resource.contains("SessionsTable")
								|| resource.contains("PreferencesTable"),
						"`web` chỉ được ghi trên `sessions` và (từ slice 4) "
								+ "`user-preferences` — không bao giờ trên articles/sources, "
								+ "thực tế: " + statement);
			}
		}

		assertTrue(statements.stream().noneMatch(
						s -> resourcesOf(s).stream().anyMatch(r -> r.contains("gemini-api-key"))),
				"`web` không được đọc gemini key — không có đường nào tới model — "
						+ statements);

		assertTrue(statements.stream().noneMatch(
						s -> actionsOf(s).stream().anyMatch(a -> a.startsWith("sqs:Send"))),
				"`web` không được gửi SQS — nó không kích hoạt việc tốn tiền — "
						+ statements);
	}

	/**
	 * `sessions` là bảng đầu tiên `web` ghi được, và bộ action của nó là ĐÚNG BA —
	 * không hơn, không kém.
	 *
	 * <p><b>Vế KHẲNG ĐỊNH mới là vế quan trọng, và nó từng không tồn tại.</b> Tập
	 * đóng `WEB_ACTIONS` chỉ hỏi *"role có action nào"*, không hỏi *"action nào
	 * trên bảng nào"* — mà `PutItem` còn được cấp trên `user-preferences`, nên gỡ
	 * `PutItem` khỏi ĐÚNG statement này vẫn để tập đóng nguyên vẹn. Đã đo bằng
	 * mutation 2026-08-19: bỏ `PutItem` khỏi `sessions` ⇒ **mọi lượt đăng nhập
	 * chết bằng AccessDenied** trên môi trường thật, và toàn bộ suite vẫn xanh.
	 *
	 * <p>`PutItem` ở đây gánh HAI việc, và đó là lý do dễ tưởng nó thừa: tạo phiên
	 * mới, VÀ trượt TTL mỗi lần dùng — `DynamoDbSessionRepository.save()` ghi đè
	 * cả item, `SessionRepositoryFilter` gọi nó ở cuối mọi request chạm session.
	 *
	 * <p>Vế phủ định `UpdateItem`: repository KHÔNG BAO GIỜ gọi `updateItem` trên
	 * bảng này. Quyền đó được cấp từ Task 7 với chú thích "trượt TTL" — một mô tả
	 * sai — và sống tới 2026-08-19 vì thừa quyền không tạo triệu chứng nào.
	 */
	@Test
	void web_doc_ghi_xoa_sessions_nhung_khong_update() {
		Template t = appStack();

		for (String action : List.of("dynamodb:GetItem", "dynamodb:PutItem",
				"dynamodb:DeleteItem")) {
			assertTrue(resourcesForAction(t, "FunctionRoleDefaultPolicy", action).stream()
							.anyMatch(resource -> resource.contains("SessionsTable")),
					"`web` phải được " + action + " trên bảng sessions — thiếu "
							+ action + " thì " + moTa(action));
		}

		for (String resource
				: resourcesForAction(t, "FunctionRoleDefaultPolicy", "dynamodb:UpdateItem")) {
			assertFalse(resource.contains("SessionsTable"),
					"`UpdateItem` trên sessions là quyền CHẾT — repository trượt TTL bằng "
							+ "PutItem ghi đè cả item, thực tế: " + resource);
		}
	}

	private static String moTa(String action) {
		return switch (action) {
			case "dynamodb:GetItem" -> "không phân giải được cookie thành phiên";
			case "dynamodb:PutItem" -> "không tạo được phiên VÀ không trượt được TTL";
			default -> "nút đăng xuất xoá cookie mà không xoá phiên";
		};
	}

	/**
	 * `user-preferences` là bảng THỨ HAI mà function phục vụ Internet ghi được, và
	 * bộ action của nó hẹp hơn `sessions` đúng một bậc: KHÔNG có `DeleteItem`.
	 *
	 * "Bỏ chọn hết nguồn" là `PutItem` một danh sách rỗng, không phải xoá item —
	 * hai đường dẫn tới cùng một màn hình, nhưng đường xoá cần thêm một quyền mà
	 * không tính năng nào đòi. `UpdateItem` cũng không: `SourcePreferenceRepository`
	 * ghi đè cả item, vì item chỉ có đúng hai attribute ngoài khoá.
	 *
	 * Vế phủ định soi theo BẢNG chứ không theo action — `DeleteItem` là quyền HỢP
	 * LỆ của `web` trên `sessions` (đăng xuất), nên "web không có DeleteItem" là
	 * câu sai. Câu đúng là "không có trên bảng NÀY".
	 *
	 * ⚠️ Vòng lặp `UpdateItem` nay xanh RỖNG: từ 2026-08-19 `web` không còn
	 * `UpdateItem` trên bảng nào cả, nên `resourcesForAction` trả danh sách trống.
	 * Giữ lại vì nó vẫn đúng và sẽ bắt được ngày ai đó cấp `UpdateItem` trên
	 * `user-preferences`; nhưng chốt chặn THẬT cho "web không có UpdateItem" là
	 * tập đóng `WEB_ACTIONS`, không phải dòng này.
	 */
	@Test
	void web_doc_ghi_user_preferences_nhung_khong_xoa() {
		Template t = appStack();

		for (String action : List.of("dynamodb:GetItem", "dynamodb:PutItem")) {
			assertTrue(resourcesForAction(t, "FunctionRoleDefaultPolicy", action).stream()
							.anyMatch(resource -> resource.contains("PreferencesTable")),
					"`web` phải được " + action + " trên bảng user-preferences");
		}
		for (String action : List.of("dynamodb:DeleteItem", "dynamodb:UpdateItem")) {
			for (String resource
					: resourcesForAction(t, "FunctionRoleDefaultPolicy", action)) {
				assertFalse(resource.contains("PreferencesTable"),
						"`web` KHÔNG được cấp " + action + " trên user-preferences — bỏ "
								+ "chọn hết nguồn là PutItem danh sách rỗng, thực tế: "
								+ resource);
			}
		}
	}

	/**
	 * `Scan` của `web` là quyền MỚI đáng dừng lại một nhịp: đây là lần đầu function
	 * phục vụ Internet đọc bảng `sources` (`GET /api/sources` dựng hàng chip).
	 *
	 * Chấp nhận được vì bảng bị chặn trên ~30 dòng bởi master §2 và endpoint là
	 * công khai — nhưng phạm vi phải hẹp hết mức: `Scan` trên `articles` là bảng
	 * TĂNG VÔ HẠN, và `Scan` tính tiền theo kích thước BẢNG chứ không theo số item
	 * trả về (master §4 nguyên tắc 3).
	 *
	 * Vế "phải có" đứng trước vế phủ định là cố ý: thiếu nó thì test này xanh rỗng
	 * đúng vào ngày ai đó gỡ mất quyền và `GET /api/sources` chết bằng AccessDenied.
	 */
	@Test
	void web_scan_duoc_sources_va_chi_sources() {
		List<String> scanOn = resourcesForAction(appStack(),
				"FunctionRoleDefaultPolicy", "dynamodb:Scan");

		assertFalse(scanOn.isEmpty(),
				"AP14 — `GET /api/sources` liệt kê nguồn bằng Scan trên bảng sources");
		for (String resource : scanOn) {
			assertTrue(resource.contains("SourcesTable"),
					"`web` chỉ được Scan bảng sources, thực tế: " + resource);
		}
	}

	/**
	 * Secret THỨ HAI của chương trình, và ranh giới quanh nó phải hẹp y như
	 * secret thứ nhất — `summarize_chi_doc_dung_mot_ssm_parameter` là bản gốc
	 * của khẳng định này, đây là bản cho `web`.
	 *
	 * Vế đáng giá nhất là "ĐÚNG MỘT": `web` nay là function phục vụ Internet CÓ
	 * quyền đọc SecureString, nên câu hỏi không còn là "nó đọc được secret không"
	 * mà là "nó đọc được BAO NHIÊU secret". Một wildcard `/news/dev/*` ở đây là
	 * đường từ Internet tới gemini key — và nó không tạo ra triệu chứng nào.
	 */
	@Test
	void web_chi_doc_dung_client_secret_cua_cognito() {
		Template t = appStack();

		List<String> readOn = resourcesForAction(t, "FunctionRoleDefaultPolicy",
				"ssm:GetParameter");
		assertEquals(1, readOn.size(),
				"`web` đọc đúng một SSM parameter, thực tế: " + readOn);
		assertTrue(readOn.get(0).endsWith(":parameter/news/dev/cognito-client-secret"),
				"resource phải ghim đúng tên parameter, thực tế: " + readOn.get(0));
		assertFalse(readOn.get(0).contains("gemini"),
				"`web` KHÔNG có đường nào tới gemini key — đó là của `summarize`, "
						+ "thực tế: " + readOn.get(0));

		List<String> decryptOn = resourcesForAction(t, "FunctionRoleDefaultPolicy",
				"kms:Decrypt");
		assertEquals(1, decryptOn.size(),
				"kms:Decrypt được cấp đúng một lần, thực tế: " + decryptOn);
		assertTrue(decryptOn.get(0).contains("alias/aws/ssm"),
				"kms:Decrypt phải ghim về khoá quản lý của SSM, thực tế: "
						+ decryptOn.get(0));

		for (String action : List.of("ssm:GetParametersByPath", "ssm:PutParameter")) {
			assertTrue(resourcesForAction(t, "FunctionRoleDefaultPolicy", action).isEmpty(),
					"`web` KHÔNG được cấp " + action);
		}
	}

	/**
	 * Env var trỏ tới ĐÚNG parameter mà statement IAM mở khoá — khẳng định chúng
	 * khớp NHAU chứ không chép literal vào hai chỗ, đúng lý do đã ghi ở
	 * `ten_parameter_trong_env_khop_arn_duoc_cap_quyen` cho gemini key.
	 */
	@Test
	void ten_client_secret_trong_env_khop_arn_duoc_cap_quyen() {
		Template t = appStack();
		String secretParameter = "/news/dev/cognito-client-secret";

		t.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
				"Environment", Match.objectLike(Map.of(
						"Variables", Match.objectLike(Map.of(
								"NEWS_COGNITO_SECRET_PARAMETER", secretParameter)))))));

		assertTrue(resourceForAction(t, "FunctionRoleDefaultPolicy", "ssm:GetParameter")
						.endsWith(":parameter" + secretParameter),
				"ARN được cấp quyền phải trỏ đúng parameter mà env var chỉ tới");
	}

	/**
	 * `ingest` và `summarize` KHÔNG mang cấu hình danh tính; `web` và `admin` thì
	 * PHẢI mang.
	 *
	 * Bốn biến `NEWS_COGNITO_*` và `NEWS_PUBLIC_BASE_URL` nằm ở khối của hai
	 * function phục vụ HTTP chứ không ở `baseEnv`, và khác biệt đó vô hình: đưa
	 * chúng vào `baseEnv` thì mọi thứ chạy y hệt, chỉ là hai function không có bề
	 * mặt đăng nhập bỗng mang theo issuer, client id và tên một secret mà chúng
	 * không có quyền đọc.
	 *
	 * Vế KHẲNG ĐỊNH cho `admin` không thừa. `SecurityConfig` là `@Profile(HTTP)`
	 * — tức nó dựng ở CẢ `admin` — và `defaultSuccessUrl`/`failureUrl` đọc
	 * `news.identity.public-base-url` lúc dựng bean. Thiếu biến, giá trị rơi về
	 * mặc định `http://localhost:8080` và console đẩy người vận hành về localhost
	 * sau khi đăng nhập, không lỗi nào nổ ra.
	 */
	@Test
	void chi_web_va_admin_mang_cau_hinh_cognito() {
		Map<String, Map<String, Object>> functions = appStack()
				.findResources("AWS::Lambda::Function");

		int mangCauHinh = 0;
		for (Map.Entry<String, Map<String, Object>> e : functions.entrySet()) {
			// `web` giữ logical id `Function` từ bản Lambdalith, nên `admin` phải
			// được loại TRƯỚC — `AdminFunction…` không bắt đầu bằng `Function`.
			boolean phucVuHttp = e.getKey().startsWith("Function")
					|| e.getKey().startsWith("AdminFunction");
			for (String prefix : List.of("NEWS_COGNITO_", "NEWS_PUBLIC_BASE_URL")) {
				if (phucVuHttp) {
					assertTrue(String.valueOf(e.getValue()).contains(prefix),
							"`" + e.getKey() + "` phục vụ HTTP nên PHẢI mang " + prefix
									+ ": " + e.getValue());
				}
				else {
					assertFalse(String.valueOf(e.getValue()).contains(prefix),
							"`" + e.getKey() + "` không được mang cấu hình danh tính ("
									+ prefix + "): " + e.getValue());
				}
			}
			if (phucVuHttp) {
				mangCauHinh++;
			}
		}
		assertEquals(2, mangCauHinh,
				"đúng HAI function phục vụ HTTP mang cấu hình danh tính, thực tế: "
						+ mangCauHinh);
	}

	/**
	 * Origin THỨ BA của distribution, và nó phải đi kèm ĐÚNG hai tính chất.
	 *
	 * <p><b>Không cache.</b> Console lật flag rồi tải lại trang; một response
	 * được cache biến "đã tắt" thành "vẫn hiện là bật" — người vận hành sẽ lật
	 * lại lần nữa và tin rằng console hỏng.
	 *
	 * <p><b>Origin RIÊNG, không dùng chung với `/api/*`.</b> Trỏ `/admin/*` về
	 * Function URL của `web` thì mọi thứ vẫn chạy — `SecurityConfig` giống hệt
	 * nhau ở hai profile — chỉ là ranh giới IAM mà cả slice 5 tồn tại để dựng
	 * biến mất: `web` sẽ phải có `dynamodb:UpdateItem` trên bảng flag.
	 */
	@Test
	void admin_co_behavior_rieng_khong_cache_va_khong_dung_chung_origin_voi_api() {
		Map<String, Map<String, Object>> distributions =
				edgeStack().findResources("AWS::CloudFront::Distribution");
		assertEquals(1, distributions.size(),
				"ADR-0005: MỘT distribution, thực tế: " + distributions.keySet());

		@SuppressWarnings("unchecked")
		Map<String, Object> config = (Map<String, Object>)
				distributions.values().iterator().next().get("Properties");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> behaviors = (List<Map<String, Object>>)
				((Map<String, Object>) config.get("DistributionConfig")).get("CacheBehaviors");

		Map<String, Object> admin = behaviors.stream()
				.filter(b -> "/admin/*".equals(b.get("PathPattern")))
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"thiếu behavior `/admin/*` — đang có: " + behaviors));
		Map<String, Object> api = behaviors.stream()
				.filter(b -> "/api/*".equals(b.get("PathPattern")))
				.findFirst()
				.orElseThrow(() -> new AssertionError("thiếu behavior `/api/*`"));

		assertEquals("4135ea2d-6df8-44a3-9df3-4b5a84be39ad", admin.get("CachePolicyId"),
				"`/admin/*` phải CACHING_DISABLED, thực tế: " + admin);

		// So DOMAIN của origin chứ KHÔNG so `TargetOriginId`. Mỗi lời gọi
		// `FunctionUrlOrigin.withOriginAccessControl()` dựng một origin RIÊNG kể
		// cả khi truyền vào cùng một Function URL, nên hai id luôn khác nhau —
		// đã đo bằng mutation: trỏ `/admin/*` về `functionUrl` của `web` vẫn cho
		// hai id khác nhau và assertion cũ vẫn xanh.
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> origins = (List<Map<String, Object>>)
				((Map<String, Object>) config.get("DistributionConfig")).get("Origins");
		Map<String, String> domainOf = new java.util.LinkedHashMap<>();
		origins.forEach(o -> domainOf.put(String.valueOf(o.get("Id")),
				String.valueOf(o.get("DomainName"))));

		String adminDomain = domainOf.get(String.valueOf(admin.get("TargetOriginId")));
		String apiDomain = domainOf.get(String.valueOf(api.get("TargetOriginId")));
		assertFalse(adminDomain.equals(apiDomain),
				"`/admin/*` phải đi tới Function URL của `admin`, không dùng chung với "
						+ "`/api/*`: " + adminDomain);
		assertTrue(adminDomain.contains("AdminFunction"),
				"origin của `/admin/*` phải là Function URL của `admin`, thực tế: "
						+ adminDomain);

		// GHIM SLOT, không chỉ ghim "khác nhau". CDK đánh số origin theo THỨ TỰ
		// LẶP của map `additionalBehaviors`, nên `Map.of` — thứ có thứ tự lặp
		// ngẫu nhiên hoá theo mỗi lần JVM khởi động — làm cùng một dòng code sinh
		// ra hai template khác nhau. Đã đo: năm lượt `cdk synth` liên tiếp cho ra
		// `Origin2 = admin` bốn lần, `Origin2 = web` một lần.
		//
		// `/api/*` đang GIỮ `Origin2` trong distribution đang chạy. Đổi số của nó
		// là đổi `TargetOriginId` của một behavior đang phục vụ traffic thật, kéo
		// theo cả OAC lẫn `CfnPermission` mà CDK tự sinh theo tên origin — một
		// lượt cập nhật CloudFront đầy đủ cho một thay đổi KHÔNG CÓ trong code.
		//
		// Test này đỏ khi ai đó chèn behavior mới vào TRƯỚC `/api/*`, và đó là
		// hành vi đúng: behavior mới phải nối vào CUỐI.
		//
		// ⚠️ Với `Map.of`, hai vế dưới chỉ đỏ ở KHOẢNG MỘT NỬA số lần chạy (đo
		// được: 3/6 lượt JVM), vì salt quyết định thứ tự. Một lượt xanh KHÔNG
		// chứng minh map đang có thứ tự — đọc `EdgeStack` để chắc.
		assertTrue(String.valueOf(api.get("TargetOriginId")).contains("DistributionOrigin2"),
				"`/api/*` phải giữ nguyên slot origin thứ 2, thực tế: "
						+ api.get("TargetOriginId"));
		assertTrue(String.valueOf(admin.get("TargetOriginId")).contains("DistributionOrigin3"),
				"`/admin/*` là origin MỚI nên nó lấy slot thứ 3, thực tế: "
						+ admin.get("TargetOriginId"));

		// Origin timeout NỚI cho `/admin/*` và CHỈ cho nó. Cold start của `admin`
		// đo được 24–27s trên dev và function này không có traffic nên luôn nguội;
		// với 30s mặc định, lần vào console đầu tiên trả 504 (đã gặp hai lần).
		//
		// Vế thứ hai mới là vế đáng canh: `/api/*` KHÔNG được nới theo. Ở đó một
		// request 25s là TRIỆU CHỨNG, và che nó bằng timeout dài hơn là bịt mất
		// tín hiệu — một thay đổi không ai thấy cho tới lần sự cố sau.
		@SuppressWarnings("unchecked")
		Map<String, Object> adminOrigin = origins.stream()
				.filter(o -> String.valueOf(o.get("Id"))
						.equals(String.valueOf(admin.get("TargetOriginId"))))
				.findFirst().orElseThrow();
		@SuppressWarnings("unchecked")
		Map<String, Object> apiOrigin = origins.stream()
				.filter(o -> String.valueOf(o.get("Id"))
						.equals(String.valueOf(api.get("TargetOriginId"))))
				.findFirst().orElseThrow();

		assertEquals(60, ((Map<String, Object>) adminOrigin.get("CustomOriginConfig"))
						.get("OriginReadTimeout"),
				"`/admin/*` phải chờ được cold start ~25s, thực tế: " + adminOrigin);
		assertFalse(((Map<String, Object>) apiOrigin.get("CustomOriginConfig"))
						.containsKey("OriginReadTimeout"),
				"`/api/*` giữ nguyên 30s mặc định — request 25s ở đó là triệu chứng, "
						+ "thực tế: " + apiOrigin);
	}

	/**
	 * `NEWS_PUBLIC_BASE_URL` và `callbackUrls` của Cognito phải mọc từ CÙNG một
	 * `cfg.appDomain()`.
	 *
	 * Đây là chốt chặn cho một lỗi đã trả giá thật trên dev (2026-08-13). Ứng
	 * dụng KHÔNG suy ra được domain công khai từ request: `EdgeStack` dùng
	 * `ALL_VIEWER_EXCEPT_HOST_HEADER` nên `Host` của viewer bị strip (SigV4 đòi
	 * Host của Function URL), và Spring khi đó dựng URL tuyệt đối bằng
	 * `*.lambda-url.us-east-1.on.aws`. Hệ quả đo được: redirect đăng nhập trỏ vào
	 * Function URL — thứ có `AuthType=AWS_IAM` nên trình duyệt nhận 403 — và
	 * `redirect_uri` gửi Cognito lệch `callbackUrls` nên Cognito từ chối.
	 *
	 * Test này KHÔNG chỉ kiểm biến có mặt: nó kiểm hai giá trị KHỚP NHAU. Chép
	 * literal domain vào hai chỗ sẽ xanh ở lượt đầu rồi lệch âm thầm ở lần đổi
	 * domain sau — mà lệch domain thì triệu chứng duy nhất là "đăng nhập hỏng",
	 * không log nào của ta nói ra.
	 */
	@Test
	void public_base_url_khop_domain_dung_cho_callback_cua_cognito() {
		EnvConfig cfg = EnvConfig.DEV;

		appStack(cfg).hasResourceProperties("AWS::Lambda::Function",
				Match.objectLike(Map.of("Environment", Match.objectLike(Map.of(
						"Variables", Match.objectLike(Map.of(
								"NEWS_PUBLIC_BASE_URL", "https://" + cfg.appDomain())))))));

		identityStack(cfg).hasResourceProperties("AWS::Cognito::UserPoolClient",
				Match.objectLike(Map.of("CallbackURLs", Match.arrayEquals(List.of(
						"https://" + cfg.appDomain() + "/api/auth/callback/cognito")))));
	}

	/**
	 * Ba tính chất của user pool, cả ba đều KHÔNG có triệu chứng khi sai — pool
	 * vẫn tạo được, vẫn đăng nhập được bằng đường khác, chỉ là không đúng thứ đã
	 * thiết kế.
	 *
	 * <p><b>PASSWORD nằm trong danh sách vì COGNITO BẮT BUỘC, không phải vì ta
	 * chọn.</b> Bản trước của test này đòi đúng {@code [EMAIL_OTP, WEB_AUTHN]} và
	 * XANH lúc synth — rồi `Dev-IdentityStack` CREATE_FAILED trên môi trường thật
	 * (2026-08-13):
	 *
	 * <pre>
	 * Resource handler returned message: "Invalid request provided: PASSWORD
	 * should be configured as one of the allowed first auth factors."
	 * (HandlerErrorCode: InvalidRequest)
	 * </pre>
	 *
	 * <p>Nghĩa là validation của CDK L2 (`PasswordAuthenticationCannotDisabled`)
	 * chép đúng luật của service, và escape hatch `addPropertyOverride` chỉ dời
	 * được chỗ chết từ synth sang deploy. Đã gỡ.
	 *
	 * <p>Lời hứa "không mật khẩu" của ADR-0017 KHÔNG mất, nó chuyển tầng: pool
	 * PHẢI liệt kê PASSWORD, nhưng không người dùng nào phải có mật khẩu — AWS:
	 * *"Users can sign up without a password when your user pool supports
	 * passwordless sign-in with email or SMS OTPs."* Chốt chặn cho vế đó là bước
	 * QA của slice 2, không phải test này.
	 *
	 * <p><b>ĐỪNG gỡ PASSWORD ra lần nữa.</b> Nó sẽ xanh ở đây và đỏ ở prod.
	 */
	@Test
	void user_pool_dung_tier_essentials_va_du_ba_first_factor() {
		Template template = identityStack(EnvConfig.DEV);

		// Essentials: điều kiện để `SignInPolicy` có hiệu lực. Ở Lite, khai
		// AllowedFirstAuthFactors là lỗi deploy.
		template.hasResourceProperties("AWS::Cognito::UserPool", Match.objectLike(
				Map.of("UserPoolTier", "ESSENTIALS")));

		// `arrayEquals` chứ không `arrayWith`: vế "có chứa EMAIL_OTP" xanh nguyên
		// vẹn cả khi SMS_OTP mọc thêm — mà SMS thì tốn tiền và cần account được
		// kích hoạt gửi SMS. Thứ tự là thứ tự L2 sinh ra.
		template.hasResourceProperties("AWS::Cognito::UserPool", Match.objectLike(
				Map.of("Policies", Match.objectLike(Map.of("SignInPolicy", Map.of(
						"AllowedFirstAuthFactors",
						Match.arrayEquals(
								List.of("PASSWORD", "EMAIL_OTP", "WEB_AUTHN"))))))));

		// Nhóm `ops` là TOÀN BỘ mô hình phân quyền của chương trình.
		template.hasResourceProperties("AWS::Cognito::UserPoolGroup", Match.objectLike(
				Map.of("GroupName", "ops")));
	}

	/**
	 * Tự đăng ký TẮT — và đây là thứ BIẾN lời hứa "không mật khẩu" của ADR-0017
	 * thành sự thật, sau khi QA slice 2 chứng minh nó chưa phải sự thật.
	 *
	 * <p><b>Đo trên prod 2026-08-13.</b> Pool có `EMAIL_OTP` nhưng form *Sign up*
	 * của managed login vẫn đòi **Email + Password + Confirm password**, và một
	 * email chưa tồn tại gõ vào form *Sign in* nhận về `"Invalid input: Password
	 * reset required for the user"` — thông báo sai (do `preventUserExistenceErrors`
	 * che sự tồn tại) và là ngõ cụt. Pool prod lúc đó có **0 user**, nên mọi
	 * người đọc đều rơi vào đường đó: story "đăng nhập bằng mã một lần gửi qua
	 * email" KHÔNG thực hiện được cho người đầu tiên.
	 *
	 * <p><b>Vì sao tắt tự đăng ký là câu trả lời, không phải một cách né.</b>
	 * Master §2 viết người dùng là *"tác giả và một nhóm nhỏ người quen"* — sản
	 * phẩm này chưa bao giờ là self-service công khai. Với mô hình mời, người
	 * vận hành tạo tài khoản bằng `admin-create-user` **không kèm
	 * `--temporary-password`**; AWS: *"If you don't specify a value, Amazon
	 * Cognito generates one for you **unless you have passwordless options active
	 * for your user pool**"*. Pool ta có passwordless, nên user sinh ra KHÔNG có
	 * mật khẩu và đăng nhập thẳng bằng EMAIL_OTP — đúng story, không mật khẩu ở
	 * bất kỳ đâu.
	 *
	 * <p>Kèm theo một vế tiết kiệm: sender mặc định của Cognito giới hạn 50
	 * email/ngày cho cả pool, và một form đăng ký công khai là cách dễ nhất để
	 * người lạ đốt sạch hạn mức đó.
	 *
	 * <p>⚠️ NGƯỠNG PHẢI ĐỔI: ngày sản phẩm muốn mở cho người lạ. Lúc đó KHÔNG
	 * bật lại `selfSignUpEnabled` — nó kéo password quay lại — mà phải tự gọi
	 * `SignUp` API bỏ trống password (AWS cho phép đúng điều đó với pool
	 * passwordless) từ một endpoint của ta.
	 */
	@Test
	void pool_tat_tu_dang_ky_vi_form_sign_up_cua_cognito_luon_doi_mat_khau() {
		identityStack(EnvConfig.DEV).hasResourceProperties("AWS::Cognito::UserPool",
				Match.objectLike(Map.of("AdminCreateUserConfig", Match.objectLike(
						Map.of("AllowAdminCreateUserOnly", true)))));
	}

	/**
	 * Cửa mật khẩu KHÔNG đóng được (xem test trên), nên nó phải được canh.
	 *
	 * Đây là hệ quả trực tiếp của việc Cognito ép PASSWORD vào danh sách: từ giây
	 * đó, "sẽ không ai đặt mật khẩu" là một Ý ĐỊNH, không phải một ràng buộc kỹ
	 * thuật. Một chính sách mật khẩu mạnh là thứ duy nhất còn lại đứng giữa ý
	 * định đó và một tài khoản có mật khẩu `123456`.
	 *
	 * Nó cũng là lý do `AwsSolutions-COG1` KHÔNG còn nằm trong allowlist của
	 * `CdkNagTest`: rule đó từng bị bỏ qua với lý do "pool này không có mật
	 * khẩu", và lý do đó nay sai.
	 */
	@Test
	void chinh_sach_mat_khau_du_manh_cho_canh_cua_khong_dong_duoc() {
		identityStack(EnvConfig.DEV).hasResourceProperties("AWS::Cognito::UserPool",
				Match.objectLike(Map.of("Policies", Match.objectLike(Map.of(
						"PasswordPolicy", Match.objectLike(Map.of(
								"MinimumLength", 12,
								"RequireLowercase", true,
								"RequireUppercase", true,
								"RequireNumbers", true,
								"RequireSymbols", true)))))));
	}

	/**
	 * Pool KHÔNG khai `passkeyRelyingPartyId`, và đây là khẳng định PHỦ ĐỊNH có
	 * chủ ý — nó chặn việc thêm lại một field trông rất hợp lý mà **không deploy
	 * được ở lần create đầu**.
	 *
	 * Đã trả giá thật (2026-08-13). Đặt RP ID = domain Cognito
	 * (`na-dev-auth.auth.us-east-1.amazoncognito.com`) synth xanh và chết ở deploy:
	 *
	 * <pre>
	 * "RelyingPartyId cannot be reserved domain other than User Pool's prefix
	 *  domain" (Status Code: 400)
	 * </pre>
	 *
	 * Cơ chế: giá trị `*.amazoncognito.com` chỉ hợp lệ khi nó LÀ prefix domain
	 * của chính pool, mà `AWS::Cognito::UserPoolDomain` là resource RIÊNG tạo SAU
	 * pool — nên tại thời điểm pool ra đời, pool chưa có domain nào để giá trị đó
	 * khớp. Không thứ tự nào trong một lượt deploy sửa được: domain cần pool id.
	 *
	 * Bỏ trống là hợp lệ và đúng cho ta: CDK ghi default *"No authentication
	 * domain"*, và AWS chỉ BẮT BUỘC khai RP ID khi pool có **custom domain** —
	 * thứ phase này cố ý không dùng (spec §10).
	 *
	 * Còn mở, và chỉ trả lời được ở QA passkey (thiết bị thật, spec §"ngoài phạm
	 * vi" của test tự động): nếu Cognito KHÔNG tự suy RP ID từ prefix domain thì
	 * đăng ký passkey sẽ hỏng, và đường sửa là một lượt deploy THỨ HAI đặt RP ID
	 * sau khi domain đã tồn tại — không phải nhét lại vào lần create đầu.
	 */
	/**
	 * Managed login **version 2**, và một style cho app client.
	 *
	 * Đây là chốt chặn cho một lỗi chỉ trình duyệt mới thấy (dev, 2026-08-13):
	 * mặc định của CDK là version 1 — classic hosted UI — thứ chỉ có email +
	 * password. Toàn bộ cấu hình passwordless của ADR-0017 (`EMAIL_OTP`,
	 * passkey) vẫn nằm nguyên trong pool nhưng KHÔNG đường nào chạm tới được từ
	 * UI. Pool đúng, cửa vào sai.
	 *
	 * <p>Vì sao không test nào khác bắt được: mọi assertion trên
	 * `AWS::Cognito::UserPool` đều xanh, vì pool THẬT SỰ được cấu hình đúng.
	 * Thứ sai nằm ở `AWS::Cognito::UserPoolDomain`, một resource mà trước hôm
	 * nay không assertion nào chạm tới ngoài `Domain` prefix.
	 *
	 * <p>Vế thứ hai — branding — không phải trang trí: v2 ĐÒI một style tồn tại
	 * cho app client, thiếu nó thì người dùng gặp trang lỗi thay vì màn hình
	 * đăng nhập. Đổi version mà quên branding còn tệ hơn không đổi.
	 */
	@Test
	void managed_login_la_v2_va_co_style_cho_app_client() {
		Template template = identityStack(EnvConfig.DEV);

		template.hasResourceProperties("AWS::Cognito::UserPoolDomain",
				Match.objectLike(Map.of("ManagedLoginVersion", 2)));

		template.hasResourceProperties("AWS::Cognito::ManagedLoginBranding",
				Match.objectLike(Map.of("UseCognitoProvidedValues", true)));
	}

	/**
	 * `ALLOW_USER_AUTH` trên APP CLIENT — mảnh thứ ba của passwordless.
	 *
	 * `SignInPolicy.allowedFirstAuthFactors` cấu hình POOL; choice-based
	 * authentication lại bật ở CLIENT. Đo được trên dev 2026-08-13: sau khi bật
	 * managed login v2, giao diện đã đúng là v2 (ô Password không còn bắt buộc)
	 * nhưng màn hình đăng nhập vẫn chỉ có email + password, vì
	 * `ExplicitAuthFlows` của client là `null`.
	 *
	 * <p>Test này cũng là chỗ kiểm chứng ánh xạ của CDK: prop `user` phải render
	 * ra đúng chuỗi `ALLOW_USER_AUTH`. Template synth là nguồn sự thật cho việc
	 * đó, không phải trí nhớ.
	 *
	 * <p>`ALLOW_USER_SRP_AUTH` đi kèm CÓ CHỦ Ý: khai `authFlows` tường minh thay
	 * TRỌN danh sách, nên bỏ nó là lấy mất flow an toàn của đường mật khẩu —
	 * đường mà Cognito BẮT BUỘC phải tồn tại.
	 */
	/**
	 * Không nói cho người lạ biết email nào có tài khoản.
	 *
	 * CDK để mặc định LEGACY, và đã ĐO trên dev 2026-08-13: gõ một địa chỉ bất
	 * kỳ vào ô đăng nhập thì Cognito trả thẳng `Invalid input: User does not
	 * exist.` Ai cũng dò được một email có phải người dùng của hệ thống hay
	 * không — không cần tài khoản, không để lại dấu vết đáng chú ý.
	 */
	/**
	 * URL đăng xuất phải mang ĐỦ tham số Cognito đòi.
	 *
	 * `<domain>/logout` trần redirect sang `<domain>/login?null` — một URL hỏng,
	 * và triệu chứng ở tầng người dùng là trang lỗi trắng sau khi bấm "Đăng
	 * xuất": phiên phía ta ĐÃ chết, nhưng người dùng bị bỏ lại ở đó. Đã đo trên
	 * dev 2026-08-13. Không test nào trước đây chạm tới giá trị này — nó chỉ
	 * được truyền từ infra sang env var rồi đi tiếp.
	 */
	@Test
	void url_dang_xuat_mang_du_tham_so_cognito_doi() {
		Map<String, Map<String, Object>> functions = appStack()
				.findResources("AWS::Lambda::Function");
		String web = functions.entrySet().stream()
				.filter(e -> e.getKey().startsWith("Function"))
				.map(e -> String.valueOf(e.getValue()))
				.findFirst()
				.orElseThrow();

		assertTrue(web.contains("/logout?client_id="),
				"thiếu `client_id` thì Cognito trả 400, thực tế: " + web);
		assertTrue(web.contains("logout_uri=https%3A%2F%2F"),
				"`logout_uri` phải có và phải được URL-encode, thực tế: " + web);
	}

	@Test
	void khong_lo_email_nao_co_tai_khoan() {
		identityStack(EnvConfig.DEV).hasResourceProperties(
				"AWS::Cognito::UserPoolClient",
				Match.objectLike(Map.of("PreventUserExistenceErrors", "ENABLED")));
	}

	@Test
	void app_client_bat_choice_based_auth_va_giu_srp() {
		Template template = identityStack(EnvConfig.DEV);

		// MỘT phần tử mỗi lần: `arrayWith` khớp một dãy con LIỀN KỀ và ĐÚNG THỨ
		// TỰ, mà CDK render ra `[ALLOW_USER_SRP_AUTH, ALLOW_USER_AUTH,
		// ALLOW_REFRESH_TOKEN_AUTH]`. Khẳng định theo thứ tự là ghim một chi
		// tiết ta không kiểm soát và sẽ vỡ vô cớ.
		for (String flow : List.of("ALLOW_USER_AUTH", "ALLOW_USER_SRP_AUTH")) {
			template.hasResourceProperties("AWS::Cognito::UserPoolClient",
					Match.objectLike(Map.of("ExplicitAuthFlows",
							Match.arrayWith(List.of(flow)))));
		}
	}

	@Test
	void pool_khong_khai_relying_party_id_vi_no_khong_deploy_duoc() {
		Template template = identityStack(EnvConfig.DEV);

		// Domain prefix vẫn được ghim: nó là URL của managed login và là thứ
		// `getLogoutUri()` dựng nên.
		template.hasResourceProperties("AWS::Cognito::UserPoolDomain",
				Match.objectLike(Map.of("Domain", "na-dev-auth")));
		// `WebAuthnRelyingPartyID` — chữ D HOA ở cuối. CDK L2 nhận
		// `passkeyRelyingPartyId` (chữ d thường) rồi render ra tên khác, nên viết
		// theo trực giác là một assertion không bao giờ khớp.
		template.hasResourceProperties("AWS::Cognito::UserPool", Match.objectLike(
				Map.of("WebAuthnRelyingPartyID", Match.absent())));
	}

	@Test
	void app_client_la_confidential_va_chi_nhan_authorization_code() {
		identityStack(EnvConfig.DEV).hasResourceProperties(
				"AWS::Cognito::UserPoolClient", Match.objectLike(Map.of(
						"GenerateSecret", true,
						"AllowedOAuthFlows", Match.arrayEquals(List.of("code")),
						// `implicit` trả token thẳng vào URL trình duyệt — đúng thứ
						// ADR-0018 tồn tại để ngăn. Không bao giờ bật.
						"AllowedOAuthFlowsUserPoolClient", true)));
	}

	/**
	 * Callback phải nằm TRONG `/api/*` và phải là HTTPS trên đúng domain của môi
	 * trường. Cả ba vế đều hỏng-mà-vẫn-chạy theo kiểu riêng:
	 *
	 * - Sai domain: đăng nhập ở `dev` đá người dùng sang `prod`.
	 * - Ngoài `/api/*`: CloudFront không route path đó tới Lambda origin, nên
	 *   callback rơi vào SPA và trả về index.html kèm `?code=…` — trình duyệt
	 *   hiện một trang trắng, không có lỗi nào ở đâu cả.
	 * - `http`: Cognito từ chối lúc deploy, nhưng chỉ khi ai đó sửa tay.
	 */
	@Test
	void callback_url_nam_trong_api_va_dung_domain_moi_truong() {
		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			identityStack(cfg).hasResourceProperties("AWS::Cognito::UserPoolClient",
					Match.objectLike(Map.of("CallbackURLs", Match.arrayEquals(List.of(
							"https://" + cfg.appDomain() + "/api/auth/callback/cognito")))));
		}
	}

	/**
	 * Pool KHÔNG bao giờ bị xoá theo stack, ở MỌI môi trường — kể cả `dev`, nơi
	 * ba bảng DynamoDB đều `Delete`.
	 *
	 * Khác biệt có chủ ý: xoá `DataStack` của dev là mất dữ liệu tái tạo được
	 * bằng một lượt ingest, còn xoá pool là mất TÀI KHOẢN NGƯỜI DÙNG — Cognito
	 * không có PITR, không có backup, không có đường về. Và vì `cfg.removalPolicy()`
	 * là công thức chung của cả repo, dòng `RETAIN` cứng ở đây trông y hệt một
	 * chỗ ai đó quên tham số hoá; test này là thứ nói rằng nó cố ý.
	 */
	@Test
	void user_pool_giu_lai_o_moi_moi_truong() {
		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			identityStack(cfg).hasResource("AWS::Cognito::UserPool",
					Match.objectLike(Map.of("DeletionPolicy", "Retain")));
		}
	}

	/**
	 * `email` PHẢI được ánh xạ ở CẢ HAI provider. Thiếu nó, Cognito tạo user
	 * không có thuộc tính bắt buộc và đăng nhập hỏng ở bước cuối — sau khi người
	 * dùng đã bấm "Đồng ý" bên Facebook, chỗ tệ nhất để hỏng.
	 *
	 * Ghim `ProviderName` ở mỗi assertion chứ KHÔNG viết một
	 * `hasResourceProperties` trần: `hasResourceProperties` xanh khi CHỈ MỘT
	 * resource cùng type khớp, nên với hai IdP thì một assertion trần chứng minh
	 * được "có ít nhất một provider ánh xạ email" — đúng nửa câu, và nửa còn
	 * thiếu là nửa hỏng được.
	 */
	@Test
	void hai_social_idp_deu_anh_xa_email() {
		Template template = identityStack(EnvConfig.DEV);

		template.resourceCountIs("AWS::Cognito::UserPoolIdentityProvider", 2);

		for (String provider : List.of("Facebook", "Google")) {
			template.hasResourceProperties("AWS::Cognito::UserPoolIdentityProvider",
					Match.objectLike(Map.of(
							"ProviderName", provider,
							"AttributeMapping", Match.objectLike(
									Map.of("email", "email")))));
		}
	}

	/**
	 * App client phải liệt kê ĐỦ BA provider. Thiếu một cái thì nút tương ứng
	 * không hiện trên managed login, dù IdP đã cấu hình xong và deploy sạch —
	 * hỏng im lặng, không lỗi ở đâu cả.
	 *
	 * Một phần tử mỗi lượt, cùng lý do đã ghi ở
	 * `app_client_bat_choice_based_auth_va_giu_srp`: `arrayWith` khớp dãy con
	 * LIỀN KỀ và ĐÚNG THỨ TỰ, nên khẳng định cả ba cùng lúc là ghim luôn thứ tự
	 * render — một chi tiết ta không kiểm soát.
	 */
	@Test
	void app_client_liet_ke_ca_ba_provider() {
		Template template = identityStack(EnvConfig.DEV);

		for (String provider : List.of("COGNITO", "Facebook", "Google")) {
			template.hasResourceProperties("AWS::Cognito::UserPoolClient",
					Match.objectLike(Map.of("SupportedIdentityProviders",
							Match.arrayWith(List.of(provider)))));
		}
	}

	/**
	 * Secret phải rơi ra thành dynamic reference `{{resolve:ssm:…}}`, không phải
	 * giá trị thật và không phải `ssm-secure`.
	 *
	 * Hai chế độ hỏng test này canh, cả hai đều synth xanh:
	 *
	 * <ol>
	 * <li>Ai đó dán thẳng secret vào code. Template đi vào CloudFormation, và
	 *     `get-template` đọc ra được — nhưng không có gì đỏ.</li>
	 * <li>Ai đó dùng `SecretValue.ssmSecure(...)`. CloudFormation từ chối
	 *     `ssm-secure` ở ĐÚNG resource này (danh sách trắng 11 cặp
	 *     resource/property, không có Cognito), nên nó chết ở DEPLOY chứ không ở
	 *     synth — xem runbook §C.</li>
	 * </ol>
	 */
	@Test
	@SuppressWarnings("unchecked")
	void secret_cua_idp_la_dynamic_reference_ssm_khong_phai_gia_tri_that() {
		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			Map<String, Map<String, Object>> idps = identityStack(cfg)
					.findResources("AWS::Cognito::UserPoolIdentityProvider");

			assertEquals(2, idps.size(),
					"phải có đúng hai IdP ở " + cfg.name() + ", thực tế: " + idps.keySet());

			for (Map<String, Object> idp : idps.values()) {
				Map<String, Object> props =
						(Map<String, Object>) idp.get("Properties");
				Map<String, Object> details =
						(Map<String, Object>) props.get("ProviderDetails");
				String provider = String.valueOf(props.get("ProviderName"));
				String secret = String.valueOf(details.get("client_secret"));

				assertEquals("{{resolve:ssm:/news/" + cfg.tagPrefix() + "/"
						+ provider.toLowerCase(java.util.Locale.ROOT)
						+ "-client-secret}}", secret,
						provider + " ở " + cfg.name() + " phải đọc secret từ SSM lúc"
								+ " deploy, thực tế: " + secret);
			}
		}
	}

	/**
	 * Trigger liên kết tài khoản phải ĐƯỢC NỐI vào pool, và nối bằng đúng
	 * `LambdaVersion`.
	 *
	 * `V1_0` là giá trị hợp lệ DUY NHẤT của trigger này (API reference:
	 * *"You must use a LambdaVersion of V1_0 with an inbound federation
	 * function"*). Sai nó thì synth vẫn xanh — property chỉ là chuỗi — và lỗi rơi
	 * ở DEPLOY.
	 *
	 * `LambdaArn` được ghim vào ĐÚNG function chứ không chỉ "có mặt": nối nhầm
	 * sang function khác cũng deploy sạch, và triệu chứng ở tầng người dùng là
	 * mỗi lượt đăng nhập social lại đẻ thêm một tài khoản — không lỗi ở đâu cả.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void inbound_federation_trigger_noi_vao_user_pool() {
		Template template = identityStack(EnvConfig.DEV);

		template.hasResourceProperties("AWS::Cognito::UserPool", Match.objectLike(
				Map.of("LambdaConfig", Match.objectLike(
						Map.of("InboundFederation", Match.objectLike(
								Map.of("LambdaVersion", "V1_0")))))));

		Map<String, Object> pool = template.findResources("AWS::Cognito::UserPool")
				.values().iterator().next();
		Map<String, Object> props = (Map<String, Object>) pool.get("Properties");
		Map<String, Object> lambdaConfig =
				(Map<String, Object>) props.get("LambdaConfig");
		Map<String, Object> inbound =
				(Map<String, Object>) lambdaConfig.get("InboundFederation");
		String arn = String.valueOf(inbound.get("LambdaArn"));

		assertTrue(arn.contains("AccountLinking"),
				"`LambdaArn` phải trỏ vào function liên kết tài khoản, thực tế: " + arn);
	}

	/**
	 * Cognito phải ĐƯỢC PHÉP gọi hàm, và chỉ từ pool này.
	 *
	 * Thiếu `AWS::Lambda::Permission` thì Cognito không gọi được, và người dùng
	 * KHÔNG thấy lỗi gì — họ chỉ lặng lẽ có thêm một tài khoản nữa. Đây là chế độ
	 * hỏng tệ nhất của cả task: nó trông y hệt lúc chưa làm gì.
	 *
	 * Thiếu `SourceArn` thì BẤT KỲ user pool nào trong account cũng gọi được hàm
	 * — mà hàm này có quyền `AdminLinkProviderForUser`, tức quyền cho một danh
	 * tính ngoài đăng nhập thành một user có sẵn.
	 */
	@Test
	void cognito_duoc_phep_goi_ham_va_chi_tu_pool_nay() {
		Template template = identityStack(EnvConfig.DEV);

		template.resourceCountIs("AWS::Lambda::Permission", 1);
		template.hasResourceProperties("AWS::Lambda::Permission", Match.objectLike(Map.of(
				"Action", "lambda:InvokeFunction",
				"Principal", "cognito-idp.amazonaws.com",
				"SourceArn", Match.anyValue())));
	}

	/**
	 * Hàm liên kết được ĐÚNG BA quyền `cognito-idp`, và không quyền nào trỏ vào
	 * `*`.
	 *
	 * `cognito-idp:*` là đường tắt hấp dẫn ở đây vì handler gọi ba API khác nhau
	 * và tên chúng dài. Nó cũng cấp luôn `AdminSetUserPassword`, `AdminDeleteUser`
	 * và `AdminUpdateUserAttributes` cho một hàm chạy ĐỒNG BỘ trong đường đăng
	 * nhập — tức mọi lượt đăng nhập social đều đi qua một hàm có quyền xoá user.
	 *
	 * Tập đóng chứ không phải `arrayWith`: thêm một action thứ tư phải làm test
	 * đỏ, kèm tên action đó trong thông báo, chứ không im lặng đi qua.
	 */
	@Test
	void ham_lien_ket_chi_duoc_ba_quyen_va_chi_tren_pool_nay() {
		Template template = identityStack(EnvConfig.DEV);

		Set<String> cognitoActions = new java.util.TreeSet<>();
		for (Map<String, Object> policy
				: template.findResources("AWS::IAM::Policy").values()) {
			for (Map<String, Object> statement : statementsOf(policy)) {
				List<String> actions = actionsOf(statement).stream()
						.filter(a -> a.startsWith("cognito-idp:"))
						.toList();
				if (actions.isEmpty()) {
					continue;
				}
				cognitoActions.addAll(actions);
				assertFalse(resourcesOf(statement).contains("*"),
						"quyền cognito-idp không bao giờ được trỏ vào `*` —"
								+ " statement: " + statement);
			}
		}

		assertEquals(Set.of(
				"cognito-idp:AdminCreateUser",
				"cognito-idp:AdminLinkProviderForUser",
				"cognito-idp:ListUsers"), cognitoActions,
				"đúng ba action của ADR-0021 §3, không hơn");
	}

	/**
	 * Lấy statement của role thuộc về một function, tìm theo logical-id prefix.
	 * Tìm theo prefix chứ không theo tên role: role do CDK sinh tên, còn logical
	 * id thì ta đặt và test phải ghim vào thứ ta kiểm soát.
	 */
	private static List<Map<String, Object>> statementsOfRole(
			Template template, String functionId) {
		List<Map<String, Object>> all = new java.util.ArrayList<>();
		template.findResources("AWS::IAM::Policy").forEach((id, policy) -> {
			if (id.startsWith(functionId + "Role")) {
				all.addAll(statementsOf(policy));
			}
		});
		return all;
	}

	@Test
	void role_chi_ghi_duoc_vao_log_group_cua_chinh_no() {
		// Chốt chặn cho việc role KHÔNG bao giờ được cấp `logs:` trên `*`: managed
		// policy AWSLambdaBasicExecutionRole làm đúng thế, và `LambdaRole` cố ý
		// không dùng nó — nó tự dựng log group rồi `grantWrite` vào đúng cái đó.
		//
		// Test này XANH cả TRƯỚC lẫn SAU khi trích `LambdaRole`, và đó là điều nó
		// phải làm: trích helper không đổi hành vi, nên thứ cần canh là "phạm vi
		// quyền không bị nới ra trong lúc dời code", không phải một hành vi mới.
		//
		// Vế "MỖI function một log group riêng" cần ba function mới kiểm được, nên
		// nó nằm ở `bon_function_bon_role_bon_bo_trigger` chứ không phải ở đây.
		Template template = appStack(EnvConfig.DEV);

		for (Map<String, Object> policy
				: template.findResources("AWS::IAM::Policy").values()) {
			for (Map<String, Object> statement : statementsOf(policy)) {
				if (actionsOf(statement).stream().noneMatch(a -> a.startsWith("logs:"))) {
					continue;
				}
				assertFalse(resourcesOf(statement).contains("*"),
						"quyền logs không bao giờ được trỏ vào `*` — statement: "
								+ statement);
			}
		}
	}

	/**
	 * Statement của MỘT policy đã synth. Tách khỏi `actionsOf(Template, prefix)`
	 * vì hai câu hỏi khác nhau: cái kia hỏi *"policy này có action nào"* nên làm
	 * phẳng tất cả, còn từ Phase 7 phải hỏi được *"action này đi cùng resource
	 * nào"* — cặp action/resource chỉ có nghĩa trong phạm vi một statement.
	 */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> statementsOf(Map<String, Object> policy) {
		Map<String, Object> props = (Map<String, Object>) policy.get("Properties");
		Map<String, Object> doc = (Map<String, Object>) props.get("PolicyDocument");
		return (List<Map<String, Object>>) doc.get("Statement");
	}

	private static List<String> actionsOf(Map<String, Object> statement) {
		return stringsOf(statement.get("Action"));
	}

	private static List<String> resourcesOf(Map<String, Object> statement) {
		return stringsOf(statement.get("Resource"));
	}

	/**
	 * `Action` và `Resource` của CloudFormation nhận CẢ chuỗi lẻ LẪN mảng, nên
	 * chỗ gọi không được phép giả định hình dạng nào.
	 *
	 * Phần tử không phải chuỗi (`{"Fn::GetAtt": …}`) đi qua `String.valueOf` chứ
	 * KHÔNG bị bỏ: một statement trỏ ARN dạng token vẫn phải đếm được, và cái ta
	 * so ở đây — `startsWith("logs:")`, `equals("*")` — không cần token resolve.
	 * Bỏ chúng đi sẽ làm mọi assertion phủ định xanh một cách rỗng.
	 */
	private static List<String> stringsOf(Object value) {
		if (value == null) {
			return List.of();
		}
		if (value instanceof List<?> list) {
			return list.stream().map(String::valueOf).toList();
		}
		return List.of(String.valueOf(value));
	}

	/**
	 * Đọc toàn bộ action của một policy từ template đã synth. Trả về tập phẳng,
	 * không quan tâm statement nào — câu hỏi ở đây là *"có action nào lạ không"*,
	 * không phải *"action nào ở statement nào"*.
	 */
	@SuppressWarnings("unchecked")
	private Set<String> actionsOf(Template template, String policyLogicalIdPrefix) {
		Set<String> actions = new java.util.TreeSet<>();
		template.findResources("AWS::IAM::Policy").forEach((logicalId, resource) -> {
			if (!logicalId.startsWith(policyLogicalIdPrefix)) {
				return;
			}
			Map<String, Object> props = (Map<String, Object>) resource.get("Properties");
			Map<String, Object> doc = (Map<String, Object>) props.get("PolicyDocument");
			for (Object stmt : (List<Object>) doc.get("Statement")) {
				Object action = ((Map<String, Object>) stmt).get("Action");
				if (action instanceof String s) {
					actions.add(s);
				}
				else if (action instanceof List<?> list) {
					list.forEach(a -> actions.add(String.valueOf(a)));
				}
			}
		});
		return actions;
	}
}
