package dev.linhvu.news_aggregator.infra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.PolicyValidationPluginReport;
import software.amazon.awscdk.PolicyViolation;
import software.amazon.awscdk.Stack;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CdkNagTest {

	/**
	 * Ngoại lệ được chấp nhận, khai báo TƯỜNG MINH kèm lý do. cdk-nag 3.x
	 * không có cơ chế suppression nên allowlist nằm ở đây.
	 *
	 * CẢNH BÁO về phạm vi — nó KHÔNG đồng nhất giữa các rule:
	 * <ul>
	 * <li>Rule không tham số (S1, CFR*) áp cho TOÀN BỘ stack. Thêm bucket thứ
	 * hai vào EdgeStack thì S1 của nó bị nuốt im lặng.</li>
	 * <li>Rule có tham số (IAM4, IAM5) mang luôn action/resource trong ngoặc
	 * vuông, nên entry chỉ áp cho đúng action/resource đó — mọi wildcard mới
	 * vẫn làm test đỏ.</li>
	 * </ul>
	 * Thêm resource mới thì phải rà lại bảng này.
	 */
	// `Map.ofEntries` chứ KHÔNG `Map.of`: `Map.of` chỉ nhận tối đa 10 cặp, và
	// Phase 7 vượt ngưỡng đó. Lỗi khi vượt là "no suitable method found" ở dòng
	// khai báo — không hề nhắc tới giới hạn 10.
	private static final Map<String, String> ACCEPTED = new LinkedHashMap<>(
			Map.ofEntries(

			Map.entry("AwsSolutions-S1", "Bucket private tuyệt đối, client DUY NHẤT là CloudFront "
					+ "qua OAC (master §8.1) — server access log chỉ chép lại đúng một "
					+ "nguồn đó, đổi lại phải dựng thêm một bucket tích luỹ dữ liệu vĩnh "
					+ "viễn, trái §4 nguyên tắc 3."),

			Map.entry("AwsSolutions-CFR1", "Geo restriction không áp dụng: site public toàn cầu "
					+ "cho người đọc tin kỹ thuật (master §2)."),

			Map.entry("AwsSolutions-DDB3", "PITR là quyết định THEO MÔI TRƯỜNG — prod bật, dev "
					+ "tắt vì nó tính tiền liên tục theo dung lượng (master §4 nguyên "
					+ "tắc 3) — mà test này chỉ synth EnvConfig.DEV. Rule KHÔNG tham số "
					+ "nên entry này nuốt luôn DDB3 của mọi bảng thêm sau vào DataStack; "
					+ "chốt chặn thật cho prod nằm ở "
					+ "DataStackTest#pitr_bat_o_prod_tat_o_dev."),

			Map.entry("AwsSolutions-CFR2", "WAF bị loại theo master §4 nguyên tắc 3 — nó tính "
					+ "tiền theo tháng và là chi phí cố định."),

			// KHÔNG có entry cho `AwsSolutions-COG1`, và chỗ trống này là một kết luận
			// chứ không phải một thiếu sót.
			//
			// Bản đầu của Task 8 CÓ một entry ở đây, với lý do "pool này không có mật
			// khẩu nên chính sách mật khẩu là mô tả một thứ không tồn tại", kèm một
			// ngưỡng xem lại: *nếu Cognito từ chối danh sách không PASSWORD lúc deploy
			// thì entry này phải bị xoá*. Cognito ĐÃ từ chối, ngày 2026-08-13:
			// `Dev-IdentityStack` CREATE_FAILED với *"PASSWORD should be configured as
			// one of the allowed first auth factors."*
			//
			// Nên cửa mật khẩu là thứ không đóng được, COG1 nói một điều CÓ THẬT, và
			// cách trả lời đúng là dựng `passwordPolicy` thật chứ không phải giữ một
			// ngoại lệ. Xem `IdentityStack` và
			// SecurityBoundaryTest#chinh_sach_mat_khau_du_manh_cho_canh_cua_khong_dong_duoc.

			Map.entry("AwsSolutions-COG2", "MFA bắt buộc và email OTP LOẠI TRỪ NHAU — đây là "
					+ "mâu thuẫn kỹ thuật, không phải đánh đổi tiện lợi. AWS ghi rõ: "
					+ "*\"One-time password (OTP) authentication flows aren't compatible "
					+ "with required multi-factor authentication (MFA) in your user pool\"* "
					+ "(Cognito dev guide, Authentication flows). Bật MFA bắt buộc là tắt "
					+ "đúng đường đăng nhập mà walkthrough slice 2 dùng làm tiêu chí "
					+ "nghiệm thu. Và tinh thần của rule vẫn được giữ theo cách khác: cả "
					+ "hai first factor đều là yếu tố SỞ HỮU (hộp thư, thiết bị có "
					+ "passkey), không phải một thứ thuộc-về-trí-nhớ đứng một mình."),

			Map.entry("AwsSolutions-COG8", "Plus tier bán ADVANCED SECURITY, mà phần lõi của "
					+ "nó — phát hiện credential bị lộ, chấm điểm rủi ro đăng nhập bằng "
					+ "mật khẩu — nói về một thứ pool này không có. Giá đã tra bằng AWS "
					+ "Pricing API (us-east-1, hiệu lực 2026-06-01): Essentials "
					+ "$0,015/MAU, Plus $0,020/MAU — đắt hơn 33% để mua bảo vệ cho mật "
					+ "khẩu không tồn tại. Essentials là mức TỐI THIỂU bắt buộc để có "
					+ "choice-based auth, nên đây không phải chọn rẻ nhất mà là chọn đúng "
					+ "cái cần. NGƯỠNG XEM LẠI: khi có mặt phẳng /admin trên Internet với "
					+ "người dùng thật ngoài chủ dự án (Task 26 mở nó, nhưng sau nhóm "
					+ "`ops` chỉ có một người)."),

			Map.entry("AwsSolutions-CFR3", "Đã cân ở Phase 4 và LOẠI — đây là kết luận chung "
					+ "cuộc, không phải hoãn tiếp. Access log của CloudFront trả lời "
					+ "được per-URL, per-referrer, per-IP và chi tiết cache hit/miss; "
					+ "site này có ĐÚNG MỘT trang và không có URL riêng cho từng "
					+ "article, nên nó sẽ chỉ nói: request tới `/`, `/assets/*`, "
					+ "`/api/articles`, `/api/health` — bốn dòng đã biết trước. Còn "
					+ "*bao nhiêu người đọc* và *có ai đang quật site* thì `Requests`, "
					+ "`4xxErrorRate`, `5xxErrorRate` của CloudFront đã cấp MIỄN PHÍ. "
					+ "Cái giá không phải tiền mà là một bucket TÍCH LUỸ DỮ LIỆU VĨNH "
					+ "VIỄN — đúng lý do đã dùng để suppress AwsSolutions-S1 ở ngay "
					+ "trên trong chính bảng này; bật CFR3 mà vẫn giữ S1 là tự mâu "
					+ "thuẫn. NGƯỠNG XEM LẠI: khi có URL riêng cho từng article (trang "
					+ "chi tiết) — master §7 KHÔNG có phase nào hứa việc đó. Xem TDD "
					+ "Phase 4 §17 #9."),

			Map.entry("AwsSolutions-SQS3", "Hai queue bị rule này chạm — `IngestDlq` và "
					+ "`SummarizeDlq` — và cả hai CHÍNH LÀ dead-letter queue: cấp DLQ "
					+ "cho một DLQ là đệ quy vô hạn, còn cdk-nag 3.x không có cách đánh "
					+ "dấu 'đây là DLQ'. `SummarizeQueue` KHÔNG nằm trong số đó, nó có "
					+ "redrive policy thật. Rule KHÔNG tham số nên entry này nuốt luôn "
					+ "SQS3 của MỌI queue thêm sau vào bất kỳ stack nào, kể cả queue "
					+ "làm việc quên mất DLQ — mỗi queue mới phải tự mang theo một test "
					+ "canh DLQ. Chốt chặn hiện có: "
					+ "SecurityBoundaryTest#schedule_gioi_han_retry_va_co_dlq và "
					+ "#sweep_schedule_co_retry_va_dlq cho ingestion, "
					+ "#queue_summarize_co_dlq_voi_max_receive_count_3 cho summarization."),



			// Hash 76856677 là logical id CDK sinh cho `AppStack/Function`. Cùng loại
			// footgun với entry SpaBucket bên dưới: đổi tên construct đó là entry lệch.
			Map.entry("AwsSolutions-IAM5[Resource::<Function76856677.Arn>:*]",
			"Role do `scheduler.targets.LambdaInvoke` tự dựng. `Function#grantInvoke` "
					+ "của CDK luôn cấp trên CẢ HAI `<arn>` và `<arn>:*` (vế thứ hai là "
					+ "alias/version), và target L2 gọi thẳng `grantInvoke` — truyền role "
					+ "tự viết vào `.role()` cũng không tránh được, vì chính target thêm "
					+ "statement đó. Wildcard còn lại chỉ nằm ở phần QUALIFIER của đúng "
					+ "MỘT function; function này chưa có alias hay version nào. Entry có "
					+ "tham số nên nó chỉ áp cho đúng resource này. "
					+ "PHASE 7: entry này nay là DI SẢN — sau khi tách, không Schedule nào "
					+ "còn trỏ `web` nữa. Giữ lại vì `Function` vẫn là logical id của "
					+ "`web`, nhưng nếu nó biến mất khỏi finding thì XOÁ entry chứ đừng để "
					+ "một ngoại lệ không còn ai dùng nằm lại."),



			// Hai entry dưới là cùng một cơ chế, nhân lên theo số function được
			// Schedule nhắm: Phase 7 chuyển `ingest-feeds` sang `IngestFunction` và
			// `summarize-sweep` sang `SummarizeFunction`, nên mỗi cái sinh một
			// `SchedulerRoleForTarget` riêng với đúng cặp `<arn>` + `<arn>:*`.
			// Trước khi tách, hai Schedule cùng trỏ một function nên dùng chung một
			// role và chỉ có một finding.
			Map.entry("AwsSolutions-IAM5[Resource::<IngestFunction4B2F2EB2.Arn>:*]",
			"Cùng lý do entry `Function…` ngay trên: `grantInvoke` của target L2 luôn "
					+ "kèm qualifier `:*`. Function này chưa có alias hay version nào. "
					+ "Entry CÓ THAM SỐ nên nó chỉ áp cho đúng resource này."),

			Map.entry("AwsSolutions-IAM5[Resource::<SummarizeFunction10D6AD57.Arn>:*]",
			"Cùng lý do entry `Function…` ở trên. `summarize-sweep` chạy trên "
					+ "`summarize` chứ không trên `ingest` (ADR-0020 driver #2: cắt theo "
					+ "ranh giới NGHIỆP VỤ, không theo nguồn kích hoạt), nên function này "
					+ "cũng là target của một Schedule và cũng dính qualifier `:*`."),

			Map.entry("AwsSolutions-IAM5[Resource::*]", "X-Ray KHÔNG hỗ trợ resource-level "
					+ "permission cho action ghi trace — `Resource: \"*\"` là hình "
					+ "thức hẹp nhất tồn tại cho `xray:PutTraceSegments`, không phải sự cẩu "
					+ "thả. Entry CÓ THAM SỐ nên nó chỉ áp cho đúng resource này; "
					+ "ĐỪNG nới thành `AwsSolutions-IAM5` trống, vì như thế là chấp "
					+ "nhận mọi wildcard tương lai của mọi stack."),



			// Hash 48E1059F là logical id CDK sinh cho `EdgeStack/SpaBucket`. Đổi tên
			// construct đó sẽ làm entry này lệch và test đỏ — khi ấy đọc tên rule mới
			// trong thông báo lỗi rồi cập nhật lại, ĐỪNG nới thành `AwsSolutions-IAM5`
			// trống, vì như thế là chấp nhận mọi wildcard tương lai của CicdStack.
			Map.entry("AwsSolutions-IAM5[Resource::<SpaBucket48E1059F.Arn>/*]",
			"Quyền object-level bắt buộc phải trỏ `<bucket>/*`; không có cách viết "
					+ "nào hẹp hơn cho `aws s3 sync`. Action đã được liệt kê tường minh "
					+ "và resource khoá đúng bucket SPA, nên wildcard còn lại chỉ nằm ở "
					+ "phần key của object.")
			));

	// KHÔNG có entry nào cho SNS, và đó là kết luận đã đo chứ không phải bỏ sót.
	//
	// Topic của ObservabilityStack CỐ Ý không bật SSE (xem Javadoc của
	// `SecurityBoundaryTest#sns_khong_bat_sse_nhung_bat_ssl`), nên phản xạ đầu
	// tiên là thêm một suppression `AwsSolutions-SNS2`. Entry đó sẽ là entry
	// CHẾT: rule ấy KHÔNG TỒN TẠI trong cdk-nag 3.0.1. Pack `aws-solutions` nạp
	// đúng MỘT rule SNS — `SNSTopicSSLPublishOnly` → `AwsSolutions-SNS3`; rule
	// mã hoá (`SNSEncryptedKMS`) chỉ nằm trong pack PCI-DSS / NIST-800-53 /
	// HIPAA, những pack test này không chạy.
	//
	// Một entry chết ở đây tệ hơn không có entry: nó trông y hệt một ngoại lệ đã
	// được cân nhắc, và nếu bản cdk-nag sau này THÊM rule SSE thật thì nó nuốt
	// luôn finding đó mà không ai phải quyết định lại. Cùng lý do với comment
	// "assertion CHẾT" ở cuối `SecurityBoundaryTest#kms_decrypt_ghim_ve_khoa_cua_ssm`.
	//
	// `AwsSolutions-SNS3` thì có thật và ĐANG xanh nhờ `enforceSsl(true)` — đo
	// bằng mutation: bỏ dòng đó ra thì test này đỏ với
	// `[ObservabilityStack → AwsSolutions-SNS3]`. Đó cũng là bằng chứng
	// ObservabilityStack thật sự nằm trong tầm quét của vòng lặp dưới.

	/**
	 * cdk-nag quét best practice trên construct tree — không cần AWS, chạy
	 * trong ./gradlew test. Bất kỳ finding nào KHÔNG nằm trong ACCEPTED đều
	 * làm test đỏ, kèm nguyên văn rule id để tra.
	 *
	 * Danh sách stack phải khớp với AppStage — thêm stack mới vào stage mà quên
	 * thêm vào đây thì nó nằm NGOÀI tầm quét, và mọi finding của nó biến mất
	 * lặng lẽ thay vì làm test đỏ. DataStack đã rơi đúng vào bẫy này một lần.
	 *
	 * Gom finding của TẤT CẢ stack rồi mới assert MỘT LẦN. Assert ngay trong
	 * vòng lặp thì stack đỏ đầu tiên che hết finding của các stack sau, và
	 * mỗi lần sửa lại lòi ra một đợt mới.
	 */
	@Test
	void khong_con_finding_ngoai_danh_sach_chap_nhan() {
		App app = new App();
		AppStage stage = new AppStage(app, EnvConfig.DEV);

		List<String> unexpected = new ArrayList<>();
		for (String stackId : List.of("DnsStack", "DataStack", "IdentityStack", "AppStack",
				"EdgeStack", "CicdStack", "ObservabilityStack")) {
			Stack stack = (Stack) stage.getNode().findChild(stackId);
			PolicyValidationPluginReport report =
					new AwsSolutionsChecks().validateScope(stack);

			report.getViolations().stream()
					.map(PolicyViolation::getRuleName)
					.filter(rule -> !ACCEPTED.containsKey(rule))
					.distinct()
					.map(rule -> stackId + " → " + rule)
					.forEach(unexpected::add);
		}

		assertTrue(unexpected.isEmpty(),
				"Còn finding chưa xử lý: " + unexpected
						+ " — sửa hạ tầng, hoặc thêm vào ACCEPTED KÈM LÝ DO.");
	}
}
