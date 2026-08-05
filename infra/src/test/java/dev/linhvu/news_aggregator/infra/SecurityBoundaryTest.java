package dev.linhvu.news_aggregator.infra;

import java.util.List;
import java.util.Map;

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
	 * Trả về Resource của statement cấp {@code action} trong policy có tên bắt đầu
	 * bằng {@code policyPrefix}, dạng chuỗi — rỗng nếu không statement nào cấp.
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
	 */
	@SuppressWarnings("unchecked")
	private String resourceForAction(Template template, String policyPrefix,
			String action) {
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
					return String.valueOf(stmt.get("Resource"));
				}
			}
		}
		return "";
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

	@Test
	void prod_chay_moi_gio_dev_moi_sau_gio() {
		appStack(EnvConfig.PROD).hasResourceProperties(
				"AWS::Scheduler::Schedule",
				Match.objectLike(Map.of("ScheduleExpression", "rate(1 hour)")));
		appStack(EnvConfig.DEV).hasResourceProperties(
				"AWS::Scheduler::Schedule",
				Match.objectLike(Map.of("ScheduleExpression", "rate(6 hours)")));
	}

	/**
	 * Path pass-through khai TƯỜNG MINH trong CDK dù nó trùng mặc định của LWA.
	 * Lý do: nó phải grep được và test được, thay vì là hằng số ngầm nằm trong
	 * binary của extension. Giá trị này phải khớp
	 * `IngestionController.PASS_THROUGH_PATH` — hai repo không thấy nhau nên
	 * compiler không bắt được lệch.
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
	 * Lambda chỉ được Query ĐÚNG index `gsi-recent`, không phải `/index/*`.
	 *
	 * `articlesTable.grantReadData()` — cách viết hiển nhiên, và là cách plan
	 * đề xuất ban đầu — cấp resource `<table>.Arn/index/*` trong khi bảng có
	 * đúng một index, kèm `dynamodb:Scan` cùng `GetRecords`/`GetShardIterator`
	 * của DynamoDB Streams (bảng chưa bật stream). cdk-nag bắt được vế wildcard,
	 * nhưng KHÔNG bắt được `Scan` — vì `Scan` là action tường minh, không phải
	 * wildcard. Nên riêng CdkNagTest là chưa đủ để chặn việc quay lại
	 * `grantReadData`, và đó là lý do test này tồn tại.
	 *
	 * `Scan` thừa quyền ở đây tốn tiền chứ không chỉ là vấn đề an ninh: nó tính
	 * theo kích thước BẢNG chứ không theo số item trả về (master §4 nguyên tắc 3).
	 */
	@Test
	void lambda_chi_query_dung_index_gsi_recent() {
		Template t = appStack();
		String queryOn = resourceForAction(t, "FunctionRoleDefaultPolicy",
				"dynamodb:Query");

		assertTrue(queryOn.contains("/index/" + DataStack.RECENT_INDEX_NAME),
				"Lambda phải được Query trên đúng index gsi-recent, thực tế: " + queryOn);
		assertFalse(queryOn.contains("/index/*"),
				"KHÔNG được cấp wildcard /index/*, thực tế: " + queryOn);
		assertTrue(resourceForAction(t, "FunctionRoleDefaultPolicy",
						"dynamodb:Scan").isEmpty(),
				"Lambda KHÔNG bao giờ được cấp dynamodb:Scan — findRecent là Query");
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
	 */
	@Test
	void lambda_chi_doc_bang_feature_toggles() {
		Template t = appStack();
		for (String action : List.of("dynamodb:DescribeTable", "dynamodb:GetItem")) {
			assertFalse(resourceForAction(t, "FunctionRoleDefaultPolicy", action).isEmpty(),
					"Lambda phải được " + action + " trên bảng feature-toggles");
		}
		for (String action : List.of("dynamodb:UpdateItem", "dynamodb:PutItem",
				"dynamodb:DeleteItem")) {
			assertTrue(resourceForAction(t, "FunctionRoleDefaultPolicy", action).isEmpty(),
					"Lambda KHÔNG được cấp " + action + " — lật flag là việc của người vận hành");
		}
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
}
