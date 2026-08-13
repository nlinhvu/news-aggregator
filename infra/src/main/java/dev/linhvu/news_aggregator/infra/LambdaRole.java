package dev.linhvu.news_aggregator.infra;

import java.util.List;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * Phần GIỐNG NHAU của bốn execution role, và chỉ phần đó.
 *
 * Cố ý KHÔNG nhận tham số "cấp thêm quyền gì": mọi quyền riêng của từng
 * function được viết TƯỜNG MINH tại chỗ dựng function đó. Một helper nhận
 * danh sách action sẽ biến bốn tập-đóng của `SecurityBoundaryTest` thành bốn
 * lời gọi cùng một hàm, và test sẽ không còn phân biệt được ai có gì.
 *
 * Hai thứ ở đây đúng là chung cho cả bốn:
 *   - ghi log vào log group CỦA CHÍNH NÓ (không phải `/aws/lambda/*`)
 *   - `xray:PutTraceSegments` — bắt buộc `Resource: "*"` vì X-Ray không hỗ trợ
 *     resource-level permission cho action ghi trace (xem AppStack Phase 4)
 */
record LambdaRole(Role role, LogGroup logGroup) {

	static LambdaRole create(Construct scope, String id, EnvConfig cfg) {
		LogGroup logGroup = LogGroup.Builder.create(scope, id + "LogGroup")
				.retention(RetentionDays.TWO_WEEKS)
				.removalPolicy(RemovalPolicy.DESTROY)
				.build();

		// Execution role viết TAY thay vì để CDK tự gắn AWSLambdaBasicExecutionRole.
		// Managed policy đó cấp `logs:CreateLogGroup` trên `*` và cấp quyền ghi trên
		// TOÀN BỘ `/aws/lambda/*` — cả hai đều thừa, vì log group ở trên do chính
		// CloudFormation tạo. Role tự viết thu phạm vi về đúng một log group.
		Role role = Role.Builder.create(scope, id + "Role")
				.assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
				.build();
		logGroup.grantWrite(role);

		// X-Ray OTLP endpoint. `PutTraceSegments` — con số này đo bằng RUNTIME, không
		// suy ra từ tài liệu, và đó là cả bài học.
		//
		// Hai trang tài liệu AWS nói khác nhau. Service authorization reference mô tả
		// `PutSpans` là *"upload OpenTelemetry spans to AWS X-Ray"* — câu khớp hoàn
		// hảo với đường này, và bản đầu của dòng code này đã chốt theo nó. Nhưng
		// trang collector-less ADOT SDK (đúng kịch bản ở đây: SDK bắn thẳng vào OTLP
		// endpoint, không collector) bảo gắn `AWSXrayWriteOnlyPolicy`, policy chỉ
		// chứa `PutTraceSegments`. Endpoint thật phân xử:
		//
		//   ...FunctionRole... is not authorized to perform: xray:PutTraceSegments
		//   because no identity-based policy allows the xray:PutTraceSegments action
		//
		// KHÔNG gắn managed policy đó dù tài liệu bảo thế: nó kèm
		// `PutTelemetryRecords`, quyền của X-Ray daemon — mà ở đây không có daemon.
		//
		// Cấp nhầm KHÔNG có triệu chứng ở tầng nào ta kiểm được: `cdk synth` xanh,
		// cdk-nag im, mọi test xanh, alarm không nổ (span rơi là chuyện của
		// BatchSpanProcessor, không phải lỗi invoke). Chốt chặn duy nhất là một lượt
		// export THẬT — xem plan Phase 4 Task 16 Step 10.
		//
		// `Resource: "*"` là BẮT BUỘC — X-Ray không hỗ trợ resource-level permission
		// cho action ghi trace. Entry cdk-nag cho nó phải CÓ THAM SỐ.
		role.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("xray:PutTraceSegments"))
				.resources(List.of("*"))
				.build());

		return new LambdaRole(role, logGroup);
	}
}
