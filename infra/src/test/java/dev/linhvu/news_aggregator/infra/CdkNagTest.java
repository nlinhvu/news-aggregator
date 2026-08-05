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
	private static final Map<String, String> ACCEPTED = new LinkedHashMap<>(Map.of(
			"AwsSolutions-S1", "Bucket private tuyệt đối, client DUY NHẤT là CloudFront "
					+ "qua OAC (master §8.1) — server access log chỉ chép lại đúng một "
					+ "nguồn đó, đổi lại phải dựng thêm một bucket tích luỹ dữ liệu vĩnh "
					+ "viễn, trái §4 nguyên tắc 3.",

			"AwsSolutions-CFR1", "Geo restriction không áp dụng: site public toàn cầu "
					+ "cho người đọc tin kỹ thuật (master §2).",

			"AwsSolutions-DDB3", "PITR là quyết định THEO MÔI TRƯỜNG — prod bật, dev "
					+ "tắt vì nó tính tiền liên tục theo dung lượng (master §4 nguyên "
					+ "tắc 3) — mà test này chỉ synth EnvConfig.DEV. Rule KHÔNG tham số "
					+ "nên entry này nuốt luôn DDB3 của mọi bảng thêm sau vào DataStack; "
					+ "chốt chặn thật cho prod nằm ở "
					+ "DataStackTest#pitr_bat_o_prod_tat_o_dev.",

			"AwsSolutions-CFR2", "WAF bị loại theo master §4 nguyên tắc 3 — nó tính "
					+ "tiền theo tháng và là chi phí cố định.",

			"AwsSolutions-CFR3", "Access logging của CloudFront thuộc scope Phase 4 "
					+ "(Observability & Cost Governance, master §7). Phase 1 chỉ quan sát "
					+ "qua CloudWatch log của Lambda, retention 14 ngày.",

			"AwsSolutions-SQS3", "Queue `IngestDlq` CHÍNH LÀ dead-letter queue của "
					+ "IngestSchedule — cấp DLQ cho một DLQ là đệ quy vô hạn, và cdk-nag "
					+ "3.x không có cách đánh dấu 'đây là DLQ'. Rule KHÔNG tham số nên "
					+ "entry này nuốt luôn SQS3 của MỌI queue thêm sau vào bất kỳ stack "
					+ "nào — Phase 3 sẽ thêm queue thật, và khi ấy phải tự viết test "
					+ "canh DLQ cho nó. Chốt chặn hiện tại cho đường ingestion là "
					+ "SecurityBoundaryTest#schedule_gioi_han_retry_va_co_dlq.",

			// Hash 76856677 là logical id CDK sinh cho `AppStack/Function`. Cùng loại
			// footgun với entry SpaBucket bên dưới: đổi tên construct đó là entry lệch.
			"AwsSolutions-IAM5[Resource::<Function76856677.Arn>:*]",
			"Role do `scheduler.targets.LambdaInvoke` tự dựng. `Function#grantInvoke` "
					+ "của CDK luôn cấp trên CẢ HAI `<arn>` và `<arn>:*` (vế thứ hai là "
					+ "alias/version), và target L2 gọi thẳng `grantInvoke` — truyền role "
					+ "tự viết vào `.role()` cũng không tránh được, vì chính target thêm "
					+ "statement đó. Wildcard còn lại chỉ nằm ở phần QUALIFIER của đúng "
					+ "MỘT function; function này chưa có alias hay version nào. Entry có "
					+ "tham số nên nó chỉ áp cho đúng resource này.",

			// Hash 48E1059F là logical id CDK sinh cho `EdgeStack/SpaBucket`. Đổi tên
			// construct đó sẽ làm entry này lệch và test đỏ — khi ấy đọc tên rule mới
			// trong thông báo lỗi rồi cập nhật lại, ĐỪNG nới thành `AwsSolutions-IAM5`
			// trống, vì như thế là chấp nhận mọi wildcard tương lai của CicdStack.
			"AwsSolutions-IAM5[Resource::<SpaBucket48E1059F.Arn>/*]",
			"Quyền object-level bắt buộc phải trỏ `<bucket>/*`; không có cách viết "
					+ "nào hẹp hơn cho `aws s3 sync`. Action đã được liệt kê tường minh "
					+ "và resource khoá đúng bucket SPA, nên wildcard còn lại chỉ nằm ở "
					+ "phần key của object."));

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
		for (String stackId : List.of("DnsStack", "DataStack", "AppStack", "EdgeStack",
				"CicdStack")) {
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
