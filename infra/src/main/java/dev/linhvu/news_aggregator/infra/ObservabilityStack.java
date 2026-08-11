package dev.linhvu.news_aggregator.infra;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
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
