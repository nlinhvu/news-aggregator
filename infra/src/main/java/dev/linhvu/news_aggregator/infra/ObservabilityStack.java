package dev.linhvu.news_aggregator.infra;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.budgets.CfnBudget;
import software.amazon.awscdk.services.ce.CfnAnomalyMonitor;
import software.amazon.awscdk.services.ce.CfnAnomalySubscription;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.Dashboard;
import software.amazon.awscdk.services.cloudwatch.GraphWidget;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.logs.FilterPattern;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.MetricFilter;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.amazon.awscdk.services.sqs.IQueue;
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
			final Function function, final Function summarizeFunction,
			final LogGroup logGroup, final IQueue scheduleDlq,
			final IQueue summarizeDlq) {
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

		// CHỈ THÔNG BÁO, không `action`: budget có action tính $0,10/ngày sau hai
		// cái đầu, budget thông báo thì miễn phí KHÔNG GIỚI HẠN. Và nó gửi email
		// THẲNG — bớt một mắt xích so với đường qua SNS, tức bớt một chỗ có thể
		// im lặng (và đường qua SNS đã im lặng thật một lần, 2026-08-11).
		//
		// Ngưỡng $3: trần chương trình là $5 (master §8.3) và Phase 4 thêm $0, nên
		// $3 cho MỘT môi trường là biên báo động rộng rãi. Route 53 một mình đã
		// ~$0,50/môi trường.
		//
		// Dựng ở CẢ BA môi trường, trước cả chỗ `qa` return: chi phí là thứ duy
		// nhất trong stack này mà `qa` vẫn sinh ra được dù không có lượt chạy nền
		// nào — một bug làm rò tiền ở qa vẫn là tiền thật.
		CfnBudget.Builder.create(this, "MonthlyBudget")
				.budget(CfnBudget.BudgetDataProperty.builder()
						.budgetName("na-" + cfg.tagPrefix() + "-monthly")
						.budgetType("COST")
						.timeUnit("MONTHLY")
						.budgetLimit(CfnBudget.SpendProperty.builder()
								.amount(3).unit("USD").build())
						.build())
				.notificationsWithSubscribers(List.of(
						CfnBudget.NotificationWithSubscribersProperty.builder()
								.notification(CfnBudget.NotificationProperty.builder()
										.notificationType("ACTUAL")
										.comparisonOperator("GREATER_THAN")
										.threshold(80)
										.thresholdType("PERCENTAGE")
										.build())
								.subscribers(List.of(CfnBudget.SubscriberProperty.builder()
										.subscriptionType("EMAIL")
										.address(EnvConfig.OPERATOR_EMAIL)
										.build()))
								.build()))
				.build();

		// Bắt được thứ budget KHÔNG bắt: một service bình thường $0,002 nhảy lên
		// $0,20 là 100× nhưng tổng vẫn dưới ngưỡng. Master §8.3 nói *"vượt trần mà
		// khối lượng chưa tăng là tín hiệu sai kiến trúc"* — thay đổi HÌNH DẠNG
		// đáng lo hơn thay đổi TỔNG. Miễn phí.
		CfnAnomalyMonitor monitor = CfnAnomalyMonitor.Builder.create(this, "CostAnomaly")
				.monitorName("na-" + cfg.tagPrefix() + "-cost-anomaly")
				.monitorType("DIMENSIONAL")
				.monitorDimension("SERVICE")
				.build();

		// `thresholdExpression` chứ không phải `threshold`: property `Threshold`
		// đã deprecated và CloudFormation TỪ CHỐI template chỉ có nó.
		//
		// Ngưỡng $1 tuyệt đối, không phải phần trăm: ở quy mô $0,00x thì mọi thay
		// đổi đều là vài trăm phần trăm, nên ngưỡng tương đối biến subscription
		// này thành máy sinh thư rác.
		CfnAnomalySubscription.Builder.create(this, "CostAnomalyEmail")
				.subscriptionName("na" + cfg.tagPrefix() + "costanomaly")
				.frequency("DAILY")
				.monitorArnList(List.of(monitor.getAttrMonitorArn()))
				.subscribers(List.of(CfnAnomalySubscription.SubscriberProperty.builder()
						.type("EMAIL").address(EnvConfig.OPERATOR_EMAIL).build()))
				.thresholdExpression("{\"Dimensions\":{\"Key\":"
						+ "\"ANOMALY_TOTAL_IMPACT_ABSOLUTE\",\"MatchOptions\":"
						+ "[\"GREATER_THAN_OR_EQUAL\"],\"Values\":[\"1\"]}}")
				.build();

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

		// Alarm thứ hai, cho `summarize`. Sau khi tách, `FunctionErrors` ở trên chỉ
		// còn phủ `web` — lượt summarize hỏng không còn đẩy metric `Errors` của
		// function đó nữa, nên không có dòng này là mất hẳn tín hiệu.
		//
		// NGÂN SÁCH ALARM: Phase 4 dùng 6/10 alarm metric org-wide (free tier tính
		// theo ORG, chia cho 8 account). Thêm MỘT thành 7. KHÔNG thêm alarm cho
		// `ingest`: lượt ingest hỏng đã có `IngestHeartbeatAlarm` (metric filter
		// trên log) và DLQ alarm phủ. Nhân ba bộ alarm là cách nhanh nhất để vỡ
		// pool free tier của cả org.
		Alarm summarizeErrors = Alarm.Builder.create(this, "SummarizeFunctionErrors")
				.alarmName("na-" + cfg.tagPrefix() + "-summarize-errors")
				.alarmDescription("Lambda summarize invoke thất bại — app ném, hoặc timeout/OOM")
				.metric(summarizeFunction.metricErrors(MetricOptions.builder()
						.period(Duration.minutes(5))
						.statistic("Sum")
						.build()))
				.threshold(1)
				.evaluationPeriods(1)
				.comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
				.treatMissingData(TreatMissingData.NOT_BREACHING)
				.build();
		summarizeErrors.addAlarmAction(new SnsAction(this.alerts));

		// Ngưỡng 1: message ĐẦU TIÊN trong DLQ đã là tin đáng đọc. ADR-0014 §8 có
		// mốc "> 20 message/ngày" nhưng đó là mốc để đổi THIẾT KẾ (thêm state
		// `failed`), không phải mốc để BÁO — tính tới 2026-08-11 `SummarizeDlq`
		// chưa nhận message nào.
		//
		// Vế `!= null` KHÔNG bao giờ sai ở đây hôm nay: `qa` là môi trường duy nhất
		// không có Schedule, và nó đã `return` phía trên. Giữ lại vì nó canh môi
		// trường TIẾP THEO — `getScheduleDlq()` trả `null` là một phần hợp đồng, và
		// một alarm trỏ vào queue không tồn tại thì chết lúc synth chứ không lúc đọc.
		if (scheduleDlq != null) {
			dlqDepthAlarm(cfg, "IngestDlqDepth", "ingest-dlq-depth", scheduleDlq);
		}

		if (cfg == EnvConfig.PROD) {
			// Chỉ prod, và đó là ngân sách chứ không phải thiếu sót: 10 alarm metric
			// cho CẢ ORG. dev có DLQ này nhưng dev là nơi ta cố tình làm hỏng đồ —
			// message trong đó thường là do chính ta bỏ vào.
			dlqDepthAlarm(cfg, "SummarizeDlqDepth", "summarize-dlq-depth", summarizeDlq);

			// CUSTOM METRIC DUY NHẤT của cả phase (10 cho toàn org, $0,30/cái khi
			// vượt). Không có metric AWS nào tách được lượt ingest khỏi lưu lượng
			// đọc: `Invocations` gộp cả ba đường vào, còn metric của EventBridge
			// Scheduler gắn theo `ScheduleGroup` nên ingest và sweep che lấp nhau.
			//
			// `$.message` chứ không phải text trần hay `$.log.message`:
			// `logging.structured.format.console: ecs` cho ECS JSON có `message` ở
			// TẦNG GỐC. Đã đo trên log group prod bằng `aws logs filter-log-events`,
			// gồm cả hai vế phủ định — bỏ `*` khớp 0 event, đổi chuỗi khớp 0 event.
			//
			// Pattern khớp TIỀN TỐ của dòng log ở `IngestionRunner`, và dòng đó chỉ
			// xuất hiện khi lượt chạy THÀNH CÔNG (runner ném trước khi log nếu mọi
			// nguồn hỏng). `IngestionRunnerTest#log_ket_thuc_luot_giu_dung_tien_to…`
			// canh phía kia của hợp đồng — hai tầng không thấy nhau.
			MetricFilter heartbeat = MetricFilter.Builder.create(this, "IngestHeartbeat")
					.logGroup(logGroup)
					.filterPattern(FilterPattern.stringValue(
							"$.message", "=", "ingestion run xong:*"))
					.metricNamespace("NewsAggregator")
					.metricName("IngestRunCompleted")
					.metricValue("1")
					.build();

			Alarm heartbeatAlarm = Alarm.Builder.create(this, "IngestHeartbeatAlarm")
					.alarmName("na-" + cfg.tagPrefix() + "-ingest-heartbeat")
					.alarmDescription("Không lượt ingest thành công nào trong 3 giờ — "
							+ "schedule bị xoá, function không boot nổi, hoặc "
							+ "EventBridge hỏng")
					.metric(heartbeat.metric(MetricOptions.builder()
							.period(Duration.hours(1))
							.statistic("Sum")
							.build()))
					.threshold(1)
					// prod chạy mỗi giờ; 3 chu kỳ tha thứ được 2 lượt trượt trước
					// khi báo. Hẹp hơn thì một lượt chậm thành báo động giả, và một
					// alarm hay báo động giả bị phớt lờ đúng lúc cần tin nhất.
					.evaluationPeriods(3)
					.datapointsToAlarm(3)
					.comparisonOperator(ComparisonOperator.LESS_THAN_THRESHOLD)
					// TOÀN BỘ Ý NGHĨA CỦA HEARTBEAT nằm ở dòng này. Không có
					// datapoint = không lượt chạy nào = HỎNG. Đổi thành
					// NOT_BREACHING là làm nó câm đúng lúc cần nhất.
					.treatMissingData(TreatMissingData.BREACHING)
					.build();
			heartbeatAlarm.addAlarmAction(new SnsAction(this.alerts));

			// MỘT trang, toàn metric AWS cấp miễn phí. $3,00/dashboard/tháng (AWS
			// Pricing API, 2026-08-11) với free tier 3 cái TÍNH THEO ORG — một cái
			// mỗi môi trường là tiêu sạch pool, không chừa gì cho project khác.
			// `dev`/`qa` là nơi ta LÀM VIỆC (Logs Insights, console); `prod` là nơi
			// ta LIẾC NHÌN.
			//
			// KHÔNG có widget CloudFront và DynamoDB, và đó là điều đã cân nhắc chứ
			// không phải bỏ sót. `DistributionId` sống ở `EdgeStack`, tên bảng ở
			// `DataStack`; đưa chúng vào đây cần thêm tham số constructor và đảo thứ
			// tự dựng stack trong `AppStage` — một thay đổi có thật, thuộc về task
			// riêng của nó. Vế "đường đọc có sao không?" tạm thời do
			// `function.metricErrors()` gánh: ADR-0015 §6 map 5xx của LWA thành
			// invoke error, nên lỗi tầng ứng dụng trên đường đọc VẪN hiện ở đây.
			// Thứ còn thiếu là lỗi tầng EDGE (origin không tới được, 403 của OAC) —
			// đúng lớp sự cố đã ngốn trọn buổi debug Task 14.
			// MỘT hàng, hai widget rộng 12 — không phải hai hàng. Mặc định của
			// `GraphWidget` là `width` 6 trên lưới 24, nên để nguyên thì dashboard
			// ra một cột hẹp bằng 1/4 màn hình và phải cuộn để thấy widget thứ hai.
			// Một trang "liếc nhìn trong 10 giây" mà phải cuộn thì không còn là
			// trang liếc nhìn.
			Dashboard.Builder.create(this, "Ops")
					.dashboardName("na-" + cfg.tagPrefix() + "-ops")
					.widgets(List.of(List.of(
							GraphWidget.Builder.create()
									.title("Lambda — invocations / errors / duration")
									.width(12)
									.left(List.of(function.metricInvocations(),
											function.metricErrors()))
									.right(List.of(function.metricDuration()))
									.build(),
							GraphWidget.Builder.create()
									.title("Độ sâu hàng đợi")
									.width(12)
									.left(List.of(
											scheduleDlq
													.metricApproximateNumberOfMessagesVisible(),
											summarizeDlq
													.metricApproximateNumberOfMessagesVisible()))
									.build())))
					.build();
		}
	}

	/**
	 * `ApproximateNumberOfMessagesVisible` là metric AWS cấp — miễn phí, không ăn
	 * vào hạn mức 10 custom metric của org.
	 *
	 * `Maximum` chứ không phải `Sum`: đây là ĐỘ SÂU tại một thời điểm, không phải
	 * lưu lượng. `Sum` trên một gauge cộng dồn các mẫu trong period và cho ra một
	 * con số không có ý nghĩa vật lý nào.
	 */
	private void dlqDepthAlarm(EnvConfig cfg, String id, String suffix, IQueue queue) {
		Alarm alarm = Alarm.Builder.create(this, id)
				.alarmName("na-" + cfg.tagPrefix() + "-" + suffix)
				.alarmDescription("DLQ có message — đọc payload để biết cái gì hỏng")
				.metric(queue.metricApproximateNumberOfMessagesVisible(
						MetricOptions.builder()
								.period(Duration.minutes(5))
								.statistic("Maximum")
								.build()))
				.threshold(1)
				.evaluationPeriods(1)
				.comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
				.treatMissingData(TreatMissingData.NOT_BREACHING)
				.build();
		alarm.addAlarmAction(new SnsAction(this.alerts));
	}

	public Topic getAlerts() {
		return alerts;
	}
}
