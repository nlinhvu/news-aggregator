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
	void smoke_role_chi_invoke_duoc_dung_mot_function() {
		Template t = cicdStack();

		assertFalse(resourceForAction(t, "SmokeRoleDefaultPolicy",
						"lambda:InvokeFunction").isEmpty(),
				"SmokeRole phải được lambda:InvokeFunction trên function của môi trường");
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
	 * ĐÚNG MỘT ARN. Trong lúc migrate sang `gsi-recent-v2` chỗ này có HAI — code
	 * còn đọc v1 tới Task 13 — và con số đó quay về 1 khi index cũ bị xoá. Giữ
	 * lại ARN của một index không còn tồn tại là để execution role mang một quyền
	 * trỏ vào hư không: không lỗi, không triệu chứng, chỉ là quyền thừa mà lần
	 * audit sau phải mất công truy nguyên.
	 *
	 * Vế `contains(RECENT_INDEX_V2_NAME)` là an toàn, nhưng vế `contains(v1)` thì
	 * KHÔNG bao giờ được viết trần: `…/index/gsi-recent` là TIỀN TỐ của
	 * `…/index/gsi-recent-v2`, nên nó được chính ARN v2 làm cho xanh vĩnh viễn.
	 * Đã đo cả hai chiều hồi Task 11B. Ở đây `assertEquals(1, size)` là thứ chặn
	 * việc ARN v1 lặng lẽ quay lại.
	 */
	@Test
	void lambda_chi_query_dung_index_gsi_recent_v2() {
		List<String> queryOn = resourcesForAction(appStack(),
				"FunctionRoleDefaultPolicy", "dynamodb:Query");

		assertEquals(1, queryOn.size(),
				"Query phải được cấp trên đúng một index, thực tế: " + queryOn);
		assertEquals(1, queryOn.stream()
						.filter(resource -> resource.contains(
								"/index/" + DataStack.RECENT_INDEX_V2_NAME))
						.count(),
				"ARN duy nhất phải trỏ gsi-recent-v2, thực tế: " + queryOn);
		for (String resource : queryOn) {
			assertFalse(resource.contains("/index/*"),
					"KHÔNG được cấp wildcard /index/*, thực tế: " + resource);
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
	void lambda_ghi_duoc_vao_articles() {
		List<String> putOn = resourcesForAction(appStack(), "FunctionRoleDefaultPolicy",
				"dynamodb:PutItem");

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
	void lambda_doc_ghi_summary_tren_arn_bang_articles() {
		Template t = appStack();

		for (String action : List.of("dynamodb:GetItem", "dynamodb:UpdateItem")) {
			List<String> on = resourcesForAction(t, "FunctionRoleDefaultPolicy", action);
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
	void lambda_chi_doc_dung_mot_ssm_parameter() {
		Template t = appStack();

		List<String> readOn = resourcesForAction(t, "FunctionRoleDefaultPolicy",
				"ssm:GetParameter");
		assertEquals(1, readOn.size(),
				"ssm:GetParameter phải được cấp trên đúng một parameter, thực tế: "
						+ readOn);
		assertTrue(readOn.get(0).contains("gemini-api-key"),
				"resource phải ghim tên parameter, thực tế: " + readOn.get(0));
		assertFalse(readOn.get(0).contains("*"),
				"resource KHÔNG được chứa wildcard, thực tế: " + readOn.get(0));

		for (String action : List.of("ssm:GetParametersByPath", "ssm:PutParameter")) {
			assertTrue(resourcesForAction(t, "FunctionRoleDefaultPolicy", action).isEmpty(),
					"Lambda KHÔNG được cấp " + action);
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

		assertTrue(resourceForAction(t, "FunctionRoleDefaultPolicy", "ssm:GetParameter")
						.endsWith(":parameter" + keyParameter),
				"ARN được cấp quyền phải trỏ đúng parameter mà env var chỉ tới, thực tế: "
						+ resourceForAction(t, "FunctionRoleDefaultPolicy",
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
				"FunctionRoleDefaultPolicy", "kms:Decrypt");

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
		List<String> scanOn = resourcesForAction(appStack(), "FunctionRoleDefaultPolicy",
				"dynamodb:Scan");

		assertFalse(scanOn.isEmpty(), "AP5 đọc mọi nguồn bằng Scan trên bảng sources");
		for (String resource : scanOn) {
			assertTrue(resource.contains("SourcesTable"),
					"Scan chỉ được cấp trên bảng sources, thực tế: " + resource);
			assertFalse(resource.contains("ArticlesTable"),
					"Scan KHÔNG bao giờ được chạm bảng articles, thực tế: " + resource);
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
			assertTrue(resourcesForAction(t, "FunctionRoleDefaultPolicy", action).stream()
							.anyMatch(resource -> resource.contains("SourcesTable")),
					"Lambda phải được " + action + " trên bảng sources");
		}
		for (String action : List.of("dynamodb:PutItem", "dynamodb:DeleteItem")) {
			for (String resource
					: resourcesForAction(t, "FunctionRoleDefaultPolicy", action)) {
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
	 * Tổng kiểm kê: 6 alarm metric trên hạn mức 10 CỦA CẢ ORG (8 account, chia
	 * chung với một project khác). Test này là thứ duy nhất giữ con số đó khỏi
	 * trôi — mỗi phase sau thêm alarm phải sửa đúng chỗ này và nêu chỗ nó lấy từ đâu.
	 */
	@Test
	void tong_so_alarm_dung_ngan_sach_free_tier() {
		observabilityStack(EnvConfig.PROD).resourceCountIs("AWS::CloudWatch::Alarm", 4);
		observabilityStack(EnvConfig.DEV).resourceCountIs("AWS::CloudWatch::Alarm", 2);
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
	void oac_co_du_hai_permission_goi_lambda() {
		Template t = edgeStack();
		t.resourceCountIs("AWS::Lambda::Permission", 2);
		for (String action : List.of("lambda:InvokeFunctionUrl", "lambda:InvokeFunction")) {
			t.hasResourceProperties("AWS::Lambda::Permission", Match.objectLike(Map.of(
					"Action", action,
					"Principal", "cloudfront.amazonaws.com")));
		}
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
		appStack().hasResourceProperties("AWS::Lambda::EventInvokeConfig",
				Match.objectLike(Map.of("DestinationConfig",
						Match.objectLike(Map.of("OnFailure", Match.anyValue())))));

		List<String> sendOn = resourcesForAction(appStack(),
				"FunctionRoleDefaultPolicy", "sqs:SendMessage");
		assertTrue(sendOn.stream().anyMatch(r -> r.contains("IngestDlq")),
				"onFailure destination cần sqs:SendMessage trên IngestDlq, thực tế: "
						+ sendOn);
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
		appStack().hasResourceProperties("AWS::Lambda::Function",
				Match.objectLike(Map.of("Environment", Map.of("Variables",
						Match.objectLike(Map.of(
								"OTEL_SERVICE_NAME", "news-aggregator",
								"OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
								"https://xray.us-east-1.amazonaws.com/v1/traces"))))));
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
	 * TẬP ĐÓNG — trả nợ Phase 3 §20B #2.
	 *
	 * Mọi test khác trong file này hỏi *"quyền X có bị khoá đúng phạm vi không?"*.
	 * Không cái nào hỏi *"ở đây có quyền nào chưa ai viết test cho nó không?"* —
	 * nên bộ quyền là TẬP MỞ, và một dòng `addToPolicy` thêm ở Phase 5 lọt qua
	 * cả 54 test còn lại.
	 *
	 * Món nợ thật không phải "role có 13 nhóm quyền" mà là **"role lớn lên mà
	 * không ai phải quyết định gì"**. Test này không làm role nhỏ đi một chút nào;
	 * nó biến việc role lớn lên thành một HÀNH VI CÓ Ý THỨC.
	 *
	 * ⚠️ KHI TEST NÀY ĐỎ: thêm action mới vào danh sách dưới đây **kèm một dòng
	 * nói ai dùng nó và ở đường code nào**. TUYỆT ĐỐI KHÔNG xoá assertion, không
	 * đổi thành `containsAll`, không thêm wildcard. Nới nó là vứt đúng thứ nó
	 * sinh ra để giữ.
	 *
	 * Điểm tách Lambdalith chốt ở **Phase 7**, khi bảng `users` làm vỡ vế thứ nhất
	 * của ADR-0013 §8 (*"ghi trên nhiều hơn hai bảng"* — nay đúng hai:
	 * `articles`, `sources`) và PII đầu tiên vào hệ thống. Xem TDD §17 #7.
	 */
	@Test
	void execution_role_khong_co_quyen_nao_ngoai_danh_sach() {
		// Mỗi dòng: action → ai dùng, đường code nào.
		Set<String> khaiBao = Set.of(
				"logs:CreateLogStream",   // Lambda runtime ghi log
				"logs:PutLogEvents",      // Lambda runtime ghi log
				"dynamodb:Query",         // ArticleRepository.findRecent — đường ĐỌC
				"dynamodb:DescribeTable", // togglz-dynamodb dựng repository
				"dynamodb:GetItem",       // Togglz đọc flag; catalog.findSummarizable
				"dynamodb:PutItem",       // catalog ghi article mới
				"dynamodb:UpdateItem",    // catalog gắn summary; sources cập nhật etag
				"dynamodb:Scan",          // SourceRepository liệt kê nguồn (chỉ `sources`)
				"sqs:SendMessage",             // enqueue summarize; onFailure ghi IngestDlq
				"sqs:ReceiveMessage",          // ESM (addEventSource → grantConsumeMessages)
				"sqs:DeleteMessage",           // ESM
				"sqs:ChangeMessageVisibility", // ESM — grantConsumeMessages cấp cả cái này
				"sqs:GetQueueAttributes",      // đi kèm MỌI grant SQS, cả hai queue
				"sqs:GetQueueUrl",             // đi kèm MỌI grant SQS, cả hai queue
				"ssm:GetParameter",            // GeminiKeyProvider đọc SecureString
				"kms:Decrypt",                 // giải mã SecureString bằng alias/aws/ssm
				"xray:PutTraceSegments"        // exporter OTLP gửi span tới X-Ray endpoint
		);

		for (EnvConfig cfg : List.of(EnvConfig.DEV, EnvConfig.QA, EnvConfig.PROD)) {
			Set<String> thucTe = actionsOf(appStack(cfg), "FunctionRoleDefaultPolicy");
			Set<String> ngoaiDanhSach = new java.util.TreeSet<>(thucTe);
			ngoaiDanhSach.removeAll(khaiBao);

			assertTrue(ngoaiDanhSach.isEmpty(),
					"[" + cfg.name() + "] execution role có quyền KHÔNG khai báo: "
							+ ngoaiDanhSach
							+ " — thêm vào danh sách KÈM LÝ DO, đừng nới test.");
		}
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
