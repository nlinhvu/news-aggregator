package dev.linhvu.news_aggregator.infra;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.constructs.Construct;

/**
 * Alarm, notification, budget và dashboard — TÁCH khỏi `AppStack`, ngược với
 * quyết định của Phase 2 (Schedule) và Phase 3 (SQS).
 *
 * Lý do gộp của hai phase kia là *"đây là TRIGGER của function; tách ra chỉ tạo
 * một stack chứa một trigger trỏ ngược về stack bên cạnh"*. Ở đây ngược lại: các
 * resource này KHÔNG kích hoạt gì cả, chúng chỉ QUAN SÁT. Chúng có vòng đời
 * riêng — thêm/bớt một alarm không phải deploy lại function — và nếu đặt chung
 * thì mỗi lần chỉnh ngưỡng alarm là một lần `AppStack` chuyển `UPDATE_IN_PROGRESS`,
 * tức đụng vào chính function đang phục vụ người đọc. Master §4 nguyên tắc 8.
 */
public class ObservabilityStack extends Stack {

	private final Topic alerts;

	public ObservabilityStack(final Construct scope, final String id, final EnvConfig cfg,
			final Function function, final LogGroup logGroup) {
		super(scope, id, StackProps.builder().env(cfg.awsEnvironment()).build());

		// KHÔNG masterKey. SSE bằng `alias/aws/sns` làm alarm action THẤT BẠI IM
		// LẶNG (key policy của AWS-managed key không cho CloudWatch
		// `kms:GenerateDataKey`, và policy đó không sửa được); SSE bằng CMK là
		// $1/tháng/key. Message chỉ chứa tên alarm và mã trạng thái — không PII,
		// không secret. `enforceSsl` siết đường truyền, và đó là thứ bảo vệ được
		// gì thật ở đây. Xem TDD §17 #2.
		this.alerts = Topic.Builder.create(this, "Alerts")
				.displayName("news-" + cfg.tagPrefix())
				.enforceSsl(true)
				.build();

		// ⚠️ BẮT BUỘC, và lý do thì ngược trực giác hoàn toàn.
		//
		// `enforceSsl(true)` ở trên sinh một `AWS::SNS::TopicPolicy`. Gắn BẤT KỲ
		// TopicPolicy nào cũng THAY THẾ policy mặc định của SNS — mà chính policy
		// mặc định đó là nơi CloudWatch lấy quyền publish. Sau khi thay, policy chỉ
		// còn đúng một statement `Deny`, tức topic không cho phép AI publish cả.
		//
		// Chế độ hỏng là IM LẶNG TUYỆT ĐỐI, và nó đã xảy ra thật trên cả `dev` lẫn
		// `prod` ngày 2026-08-11: alarm vẫn chuyển `ALARM`, `StateReason` vẫn trỏ
		// đúng datapoint, console nhìn hoàn hảo — và không mail nào tới. Metric
		// `NumberOfMessagesPublished` của topic không có lấy một datapoint. Chỗ DUY
		// NHẤT nói ra sự thật là `aws cloudwatch describe-alarm-history
		// --history-item-type Action`:
		//
		//   "actionState": "Failed",
		//   "error": "CloudWatch Alarms is not authorized to perform: SNS:Publish"
		//
		// Đây đúng là chế độ hỏng mà khối comment bên trên né tránh cho SSE — chỉ
		// khác là `enforceSsl` gây ra nó, không phải `masterKey`.
		//
		// `aws:SourceAccount` chặn confused deputy: thiếu nó thì CloudWatch của BẤT
		// KỲ account nào biết ARN này đều bơm được vào hộp thư cảnh báo. Nhưng nó
		// cũng là vế rủi ro — sai condition key thì `Allow` không áp và ta quay lại
		// đúng sự im lặng trên, nên mỗi lần deploy phải ép một alarm nổ thật
		// (`aws cloudwatch set-alarm-state`) rồi đọc lại alarm history.
		//
		// `qa` CŨNG được cấp, dù nó không có alarm thường trực: topic của nó tồn tại
		// để một alarm ad-hoc trong lúc điều tra sự cố dùng được NGAY. Một topic
		// không publish được thì không phục vụ được mục đích đó.
		this.alerts.addToResourcePolicy(PolicyStatement.Builder.create()
				.sid("AllowCloudWatchAlarmsToPublish")
				.effect(Effect.ALLOW)
				.principals(List.of(new ServicePrincipal("cloudwatch.amazonaws.com")))
				.actions(List.of("sns:Publish"))
				.resources(List.of(this.alerts.getTopicArn()))
				.conditions(Map.of("StringEquals",
						Map.of("aws:SourceAccount", cfg.account())))
				.build());

		// ⚠️ Subscription sinh ra ở trạng thái PendingConfirmation. CDK KHÔNG xác
		// nhận được — chỉ người thật bấm link trong mail. Chưa xác nhận thì alarm
		// vẫn chuyển ALARM trên console và KHÔNG mail nào tới, không lỗi ở bất kỳ
		// đâu. Đây là cạm bẫy cùng họ với món nợ §20B #1, và chốt chặn duy nhất là
		// bước QA `list-subscriptions-by-topic` ở walkthrough slice 1.
		this.alerts.addSubscription(new EmailSubscription(EnvConfig.OPERATOR_EMAIL));

		// `qa` không có alarm nào: nó không có Schedule (EnvConfig.QA để null cả
		// hai) nên không có lượt chạy nền để hỏng, và lưu lượng duy nhất là smoke
		// test — mà smoke test hỏng thì GitHub Actions đã báo. Một alarm gần như
		// không bao giờ nổ là một alarm chưa từng được kiểm chứng. TDD §17 #3.
		if (cfg == EnvConfig.QA) {
			return;
		}

		// `Errors` do AWS cấp: miễn phí, và nó đếm MỌI lượt invoke thất bại không
		// phân biệt sync hay async — nên một alarm phủ cả ba đường vào, kể cả
		// đường đọc của người dùng.
		Alarm errors = Alarm.Builder.create(this, "FunctionErrors")
				.alarmName("na-" + cfg.tagPrefix() + "-function-errors")
				.alarmDescription("Lambda invoke thất bại — app ném, hoặc timeout/OOM")
				.metric(function.metricErrors(MetricOptions.builder()
						.period(Duration.minutes(5))
						.statistic("Sum")
						.build()))
				.threshold(1)
				.evaluationPeriods(1)
				.comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
				// BẮT BUỘC. Phần lớn thời gian không có lỗi nào ⇒ không có
				// datapoint; để mặc định thì alarm treo ở INSUFFICIENT_DATA vĩnh
				// viễn và không bao giờ nổ.
				.treatMissingData(TreatMissingData.NOT_BREACHING)
				.build();
		errors.addAlarmAction(new SnsAction(this.alerts));
	}

	public Topic getAlerts() {
		return alerts;
	}
}
