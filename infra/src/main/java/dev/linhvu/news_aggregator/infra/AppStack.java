package dev.linhvu.news_aggregator.infra;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.CfnFunction;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.EcrImageCodeProps;
import software.amazon.awscdk.services.lambda.EventInvokeConfigOptions;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionUrl;
import software.amazon.awscdk.services.lambda.FunctionUrlAuthType;
import software.amazon.awscdk.services.lambda.FunctionUrlOptions;
import software.amazon.awscdk.services.lambda.Handler;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.destinations.SqsDestination;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.scheduler.Schedule;
import software.amazon.awscdk.services.scheduler.ScheduleExpression;
import software.amazon.awscdk.services.scheduler.ScheduleTargetInput;
import software.amazon.awscdk.services.scheduler.targets.LambdaInvoke;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

public class AppStack extends Stack {

	private final Function function;
	private final FunctionUrl functionUrl;
	private final LogGroup logGroup;
	private final Queue scheduleDlq;
	private final Queue summarizeDlq;

	public AppStack(final Construct scope, final String id, final EnvConfig cfg,
			final ITable articlesTable, final ITable featureTogglesTable,
			final ITable sourcesTable) {
		super(scope, id, StackProps.builder().env(cfg.awsEnvironment()).build());

		String imageDigest = StringParameter.valueForStringParameter(
				this, "/news/" + cfg.tagPrefix() + "/image-digest");

		IRepository repo = Repository.fromRepositoryArn(this, "SharedRepository",
				"arn:aws:ecr:" + cfg.region() + ":" + EnvConfig.TOOLING_ACCOUNT
						+ ":repository/news-aggregator");

		this.logGroup = LogGroup.Builder.create(this, "LogGroup")
				.retention(RetentionDays.TWO_WEEKS)
				.removalPolicy(RemovalPolicy.DESTROY)
				.build();

		// Execution role viết TAY thay vì để CDK tự gắn AWSLambdaBasicExecutionRole.
		// Managed policy đó cấp `logs:CreateLogGroup` trên `*` và cấp quyền ghi trên
		// TOÀN BỘ `/aws/lambda/*` — cả hai đều thừa, vì log group ở trên do chính
		// CloudFormation tạo. Role tự viết thu phạm vi về đúng một log group.
		Role executionRole = Role.Builder.create(this, "FunctionRole")
				.assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
				.build();
		this.logGroup.grantWrite(executionRole);

		// KHÔNG dùng `articlesTable.grantReadData()`. Nó cấp resource
		// `<table>.Arn/index/*` trong khi bảng có ĐÚNG MỘT index; kèm theo
		// `dynamodb:Scan` mà repository không bao giờ gọi (findRecent là Query —
		// xem TDD §6), và cả `dynamodb:GetRecords`/`GetShardIterator` là quyền của
		// DynamoDB Streams, thứ DataStack còn chưa bật. Wildcard resource đó chính
		// là finding cdk-nag AwsSolutions-IAM5, và ở đây nó bắt đúng — cùng loại
		// với `bucket.grantReadWrite()` đã bị thay ở CicdStack.
		//
		// Lambda CHỈ đọc bảng này. Phase 1 thì đường ghi duy nhất là
		// `SeedApplication` (người vận hành chạy bằng credential của chính họ);
		// Phase 2 Task 7 xoá nó, và đường ghi của ingestion thật được cấp quyền
		// tường minh ở task riêng — KHÔNG phải ở dòng này.
		// Thêm operation mới cho Lambda thì phải thêm action ở ĐÂY một cách có ý thức.
		//
		// ĐÚNG MỘT ARN, trỏ index chứ không trỏ bảng: cấp `dynamodb:Query` trên
		// `articlesTable.getTableArn()` trần là cấp luôn Query trên bảng, và
		// `/index/*` là cấp trên mọi index tương lai. Cả hai đều làm mất tính chất
		// "muốn thêm đường đọc thì phải sửa dòng này".
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:Query"))
				.resources(List.of(articlesTable.getTableArn()
						+ "/index/" + DataStack.RECENT_INDEX_V2_NAME))
				.build());

		// Bộ action dưới đây đọc ra từ bytecode của togglz-dynamodb 4.6.2, không
		// phải từ tài liệu:
		//   DynamoDBStateRepositoryBuilder.initializeTable() → describeTable
		//   DynamoDBStateRepository.getFeatureState()        → getItem
		//   DynamoDBStateRepository.setFeatureState()        → updateItem
		//
		// `DescribeTable` là cái dễ quên nhất và cũng chí mạng nhất: builder gọi nó
		// ĐÚNG MỘT LẦN lúc dựng bean rồi ném RuntimeException nếu hỏng. Vì bean là
		// @Lazy, lần chết đầu tiên rơi vào request đầu tiên chạm tới flag — trên
		// môi trường thật, không phải lúc khởi động.
		//
		// KHÔNG cấp `UpdateItem`: đó là đường GHI, chỉ Togglz console dùng, mà console
		// đang tắt. Lật flag là thao tác của người vận hành bằng credential của họ.
		// KHÔNG cấp `Query`: repository không có đường code nào gọi tới nó — plan đề
		// xuất `GetItem + Query`, và `Query` là quyền thừa đúng theo nghĩa mà
		// `lambda_chi_query_dung_index_gsi_recent` đang canh ở bảng bên kia.
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:DescribeTable", "dynamodb:GetItem"))
				.resources(List.of(featureTogglesTable.getTableArn()))
				.build());

		// Đường GHI mở lần đầu trong chương trình. Đường HTTP không bao giờ dùng tới
		// quyền này — đó là cái giá đã ghi nhận của ADR-0011 §7 khi từ chối phương án
		// tách thành hai function.
		//
		// Resource là ARN của BẢNG, không phải của index như dòng `Query` phía trên.
		// `PutItem` trên ARN index synth vẫn xanh và chỉ chết lúc runtime.
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:PutItem"))
				.resources(List.of(articlesTable.getTableArn()))
				.build());

		// AP5 + AP6. `Scan` chỉ trên bảng này — nó bị chặn trên ~30 dòng bởi master
		// §2, trong khi `articles` tăng vô hạn và `Scan` tính tiền theo kích thước
		// BẢNG chứ không theo số item trả về.
		//
		// `UpdateItem` chứ KHÔNG phải `PutItem`: `SourceRepository.updateFetchState`
		// chỉ đụng ba attribute trạng thái bằng UpdateExpression. `PutItem` ghi đè cả
		// item, tức xoá `name`/`feedUrl`/`enabled` — những thứ `sourcesSync` sở hữu và
		// người vận hành ghi bằng credential của chính họ.
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:Scan", "dynamodb:UpdateItem"))
				.resources(List.of(sourcesTable.getTableArn()))
				.build());

		// Queue summarize + DLQ. Đặt trong AppStack cùng lý do Schedule của Phase 2
		// (TDD §17 #2): đây là TRIGGER CỦA FUNCTION, tách ra thành stack riêng chỉ
		// tạo một stack chứa một trigger trỏ ngược về stack bên cạnh.
		this.summarizeDlq = Queue.Builder.create(this, "SummarizeDlq")
				.retentionPeriod(Duration.days(14))
				.enforceSsl(true)
				.build();

		Queue summarizeQueue = Queue.Builder.create(this, "SummarizeQueue")
				// 6 × 120s function timeout + 60s batch window. Nhỏ hơn function
				// timeout thì Lambda TỪ CHỐI tạo ESM; nhỏ hơn 6× thì message tái
				// hiện trong lúc đang xử lý và article bị gọi model hai lần.
				.visibilityTimeout(Duration.seconds(780))
				.enforceSsl(true)
				.deadLetterQueue(DeadLetterQueue.builder()
						.queue(this.summarizeDlq)
						.maxReceiveCount(3)
						.build())
				.build();

		// Producer. `SendMessage` trên ĐÚNG queue summarize — không wildcard,
		// và KHÔNG có quyền nào trên DLQ: chỉ dịch vụ SQS ghi vào đó, và một bug
		// có quyền xoá DLQ là một bug dọn được bằng chứng của chính nó.
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("sqs:SendMessage"))
				.resources(List.of(summarizeQueue.getQueueArn()))
				.build());

		// AP4 (ghi `summary`) + AP8 (đọc bài cần tóm tắt). Resource là ARN BẢNG,
		// không phải ARN index — dòng `Query` ở trên trỏ index vì nó query GSI,
		// còn hai action này đọc/ghi theo partition key. Cấp nhầm chiều thì synth
		// vẫn xanh, cdk-nag vẫn im, và mọi lượt summarize chết bằng AccessDenied.
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:GetItem", "dynamodb:UpdateItem"))
				.resources(List.of(articlesTable.getTableArn()))
				.build());

		// Secret ĐẦU TIÊN và duy nhất của chương trình (master §8.1). Đọc đúng
		// MỘT parameter — KHÔNG `GetParametersByPath`: ta biết chính xác tên, nên
		// quyền quét cây là quyền thừa và nó với tới cả image digest lẫn mọi config
		// tương lai. KHÔNG `PutParameter`: key do người vận hành ghi bằng credential
		// của chính họ (TDD §17 #10).
		String keyParameterName = "/news/" + cfg.tagPrefix() + "/gemini-api-key";
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("ssm:GetParameter"))
				.resources(List.of("arn:aws:ssm:" + cfg.region() + ":" + cfg.account()
						+ ":parameter" + keyParameterName))
				.build());

		// SecureString dùng khoá quản lý `alias/aws/ssm`. Ghim về đúng khoá đó —
		// để `Resource: *` thì execution role giải mã được MỌI thứ mã hoá trong
		// account, và đó là quyền không ai để ý cho tới khi có sự cố.
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("kms:Decrypt"))
				.resources(List.of("arn:aws:kms:" + cfg.region() + ":" + cfg.account()
						+ ":alias/aws/ssm"))
				.build());

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
		// cdk-nag im, cả 57 test xanh, alarm không nổ (span rơi là chuyện của
		// BatchSpanProcessor, không phải lỗi invoke). Chốt chặn duy nhất là một lượt
		// export THẬT — xem plan Task 16 Step 10.
		//
		// `Resource: "*"` là BẮT BUỘC — X-Ray không hỗ trợ resource-level permission
		// cho action ghi trace. Entry cdk-nag cho nó phải CÓ THAM SỐ.
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("xray:PutTraceSegments"))
				.resources(List.of("*"))
				.build());

		Map<String, String> env = new HashMap<>();
		env.put("SPRING_PROFILES_ACTIVE", "aws");
		env.put("NEWS_ENV", cfg.tagPrefix());
		env.put("NEWS_ARTICLES_TABLE", articlesTable.getTableName());
		env.put("NEWS_TOGGLES_TABLE", featureTogglesTable.getTableName());
		env.put("NEWS_SOURCES_TABLE", sourcesTable.getTableName());
		// Khai TƯỜNG MINH dù trùng mặc định của LWA — để nó grep được và test
		// được. Phải khớp `news.platform.pass-through-path` bên repo app (chỗ
		// `EventsController` lấy path của nó); hai bên không thấy nhau nên
		// compiler không bắt được lệch.
		env.put("AWS_LWA_PASS_THROUGH_PATH", "/events");
		// ADR-0015. Không có dòng này, LWA trả HTTP status trong BODY và Lambda
		// coi mọi response là thành công — kể cả 500. Đó là lý do
		// `retryAttempts(2)` và DLQ của Schedule chưa bao giờ kích hoạt kể từ
		// Phase 2, và Phase 3 §20B #1 đo được điều đó trên prod.
		//
		// CHỈ 5xx. Đưa 4xx vào là biến mỗi con bot quét 404 thành một "lỗi
		// Lambda" và tự đầu độc alarm. Cái giá đã biết: `/events` phải tự trả
		// 500 cho `job` lạ thay vì 400 — xem `EventsController#handleUnknown`.
		env.put("AWS_LWA_ERROR_STATUS_CODES", "500-599");
		env.put("NEWS_SUMMARIZE_QUEUE_URL", summarizeQueue.getQueueUrl());
		// Chỉ TÊN parameter đi qua đây, không phải giá trị: key nằm nguyên trong
		// SSM SecureString và chỉ được giải mã lúc runtime bằng đúng hai quyền ở
		// trên. `ssm-secure` dynamic reference KHÔNG dùng được cho env var của
		// Lambda (CloudFormation chỉ hỗ trợ nó trên 11 cặp resource/property, và
		// `AWS::Lambda::Function` không nằm trong đó), nên truyền tham chiếu là
		// cách DUY NHẤT giữ được cả audit lẫn xoay key không cần redeploy.
		env.put("NEWS_GEMINI_KEY_PARAMETER", keyParameterName);
		// Ghim phiên bản, KHÔNG dùng alias `gemini-flash-lite-latest`: một alias đổi
		// model mà không có commit nào, nên độ dài đầu ra và tỉ lệ chạm trần 500 ký
		// tự có thể đổi giữa hai lượt chạy mà không ai truy được lý do.
		//
		// Cái giá của việc ghim là model sẽ bị Google ngưng — và nó ĐÃ xảy ra:
		// `gemini-2.5-flash-lite` trả 404 "no longer available to new users" trong
		// khoảng 15:09Z–18:08Z ngày 2026-08-11, làm mọi lượt summarize hỏng cho tới
		// khi đổi dòng này. Đổi model là một commit có diff đọc được, và lưới cảnh
		// báo bắt được sự cố đó trong ~40 phút.
		env.put("NEWS_SUMMARIZATION_MODEL", "gemini-3.5-flash-lite");
		// Tên service trong X-Ray. Boot 4.1 KHÔNG map biến này qua
		// `OpenTelemetryEnvironmentVariableEnvironmentPostProcessor`; đường vào là
		// `OpenTelemetryResourceAttributes`, vốn đọc thẳng `OTEL_SERVICE_NAME` từ
		// env (fallback `spring.application.name`). Hai đường cùng ra
		// `news-aggregator`, nhưng viết ra ở đây thì tên service không đổi theo một
		// lần sửa `spring.application.name` bên repo app.
		env.put("OTEL_SERVICE_NAME", "news-aggregator");
		// TRACES_ENDPOINT chứ KHÔNG phải `OTEL_EXPORTER_OTLP_ENDPOINT` dạng chung:
		// Boot 4.1 map biến dạng chung vào CẢ BA endpoint trace/metric/log cùng
		// lúc, mà X-Ray chỉ nhận trace. Task 14 đã tắt tường minh export metric và
		// log trong `application.yaml` nên biến dạng chung cũng không gây hại hôm
		// nay — nhưng biến signal-specific thì không phụ thuộc vào hai dòng
		// `enabled: false` đó còn sống hay không, và nó cũng là biến mà chính tài
		// liệu X-Ray OTLP dùng.
		//
		// ĐIỀU KIỆN TIÊN QUYẾT ngoài repo: Transaction Search phải BẬT ở account
		// (`aws xray get-trace-segment-destination` trả `CloudWatchLogs`). Không có
		// nó thì endpoint từ chối span, và triệu chứng là X-Ray rỗng chứ không phải
		// một lỗi deploy nào. Lệnh bật nằm ở runbook Phase 4.
		env.put("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
				"https://xray." + cfg.region() + ".amazonaws.com/v1/traces");

		this.function = Function.Builder.create(this, "Function")
				.role(executionRole)
				.runtime(Runtime.FROM_IMAGE)
				.handler(Handler.FROM_IMAGE)
				.code(Code.fromEcrImage(repo, EcrImageCodeProps.builder()
						.tagOrDigest(imageDigest)
						.build()))
				.architecture(Architecture.ARM_64)
				.memorySize(2048)
				// 30s → 120s: cold start median 15s CHỈ để boot Spring (Phase 1 §16),
				// để lại ~15s cho việc fetch 4 feed là quá sát. Timeout cao không
				// tốn tiền — Lambda tính theo duration thật, không theo cấu hình.
				.timeout(Duration.seconds(120))
				.logGroup(this.logGroup)
				.environment(env)
				.build();

		// `Code.fromEcrImage` chọn `@` hay `:` bằng `tagOrDigest.startsWith("sha256:")`.
		// Digest của ta là token chưa resolve (`${Token[TOKEN.n]}`) nên check đó luôn
		// trả false và CDK nối bằng `:` — Lambda hiểu thành TAG và deploy chết.
		// `repositoryUriForDigest` ép `@` mà không soi chuỗi, nên nó đúng với token.
		CfnFunction cfnFunction = (CfnFunction) this.function.getNode().getDefaultChild();
		cfnFunction.addPropertyOverride("Code.ImageUri",
				repo.repositoryUriForDigest(imageDigest));

		// AI được phép gọi Function URL này thì KHÔNG nằm ở đây — hai
		// `AWS::Lambda::Permission` (`lambda:InvokeFunctionUrl` +
		// `lambda:InvokeFunction`) đều ở EdgeStack, vì `SourceArn` của chúng phải
		// trỏ tới distribution. Đưa ngược về đây sẽ tạo circular dependency
		// AppStack → EdgeStack → AppStack, trừ khi bỏ `SourceArn` — mà bỏ thì mọi
		// distribution CloudFront đều gọi được.
		this.functionUrl = this.function.addFunctionUrl(FunctionUrlOptions.builder()
				.authType(FunctionUrlAuthType.AWS_IAM)
				.build());

		CfnOutput.Builder.create(this, "FunctionUrl")
				.value(this.functionUrl.getUrl()).build();

		// Consumer. `addEventSource` tự cấp ReceiveMessage/DeleteMessage/
		// GetQueueAttributes cho execution role — KHÔNG viết tay lại, làm thế sẽ
		// có hai policy chồng nhau và cdk-nag báo trùng.
		//
		// KHÔNG đặt maxConcurrency: nó tắt mất tối ưu hoá poll-khi-rỗng của
		// Lambda và làm ESM gọi SQS nhiều hơn 24/7 (TDD §17 #9).
		this.function.addEventSource(SqsEventSource.Builder.create(summarizeQueue)
				.batchSize(10)
				// Gom batch để né cold start: 45 bài mà mỗi bài một invoke thì
				// riêng tiền boot Spring đã gấp mấy lần tiền model.
				.maxBatchingWindow(Duration.seconds(60))
				// BẮT BUỘC. Thiếu nó thì Lambda BỎ QUA response batchItemFailures
				// và coi cả batch là thành công — message hỏng biến mất im lặng.
				.reportBatchItemFailures(true)
				.build());

		// Schedule và DLQ nằm trong AppStack chứ không tách stack riêng (TDD §17 #2):
		// schedule là TRIGGER CỦA FUNCTION, tách ra sẽ thành một stack chỉ chứa
		// một trigger trỏ ngược về stack bên cạnh.
		// MỘT DLQ cho cả hai schedule. Tách ra hai hàng đợi chỉ làm người vận hành
		// phải nhớ kiểm hai chỗ cho cùng một loại sự cố — "một lượt chạy theo lịch
		// hỏng hết retry" — trong khi payload trong message đã phân biệt được nguồn.
		//
		// Construct id giữ nguyên `IngestDlq` dù nay nó phục vụ cả sweep: đổi id là
		// đổi logical ID, tức CloudFormation xoá queue cũ và tạo queue mới, vứt luôn
		// message hỏng đang nằm trong đó — đúng thứ ta cần đọc nhất.
		Queue scheduleDlq = null;
		if (cfg.ingestionRate() != null || cfg.sweepRate() != null) {
			scheduleDlq = Queue.Builder.create(this, "IngestDlq")
					.retentionPeriod(Duration.days(14))
					.enforceSsl(true)
					.build();

			// TẦNG ② của ADR-0015. Scheduler gọi Lambda BẤT ĐỒNG BỘ: nó trả
			// `202 Accepted` ngay khi Lambda NHẬN sự kiện, và từ giây đó
			// `retryAttempts(2)` + DLQ của Schedule không còn biết gì nữa. Chúng
			// canh tầng ① (không giao được việc: throttle, mất quyền). Lỗi khi
			// HÀM CHẠY rơi vào async retry của Lambda rồi tới ĐÂY.
			//
			// Không có destination thì sự kiện rơi mất, chỉ còn metric
			// `AsyncEventsDropped` — biết "có thứ gì đó rơi" mà không biết cái gì.
			// Message ở đây mang PAYLOAD GỐC nên đọc được ngay là `ingest-feeds`
			// hay `summarize-sweep`.
			//
			// Cùng queue với tầng ①: một chỗ để nhìn tốt hơn hai, và hai loại
			// message phân biệt được bằng hình dạng. Runbook mô tả cách đọc.
			// KHÔNG có `executionRole.addToPolicy(sqs:SendMessage)` đi kèm ở đây, và
			// đó là kết luận đã ĐO chứ không phải bỏ sót.
			//
			// `SqsDestination.bind()` tự gọi `queue.grantSendMessages(fn)`, nên
			// `FunctionRoleDefaultPolicy` đã có `[GetQueueAttributes, GetQueueUrl,
			// SendMessage]` trên `IngestDlq` mà không ai viết dòng nào. Thêm một
			// statement tay nữa chỉ tạo bản sao trong policy: không sai, nhưng nó
			// là thứ mà lần audit IAM sau phải mất công truy nguyên, và Task 9 —
			// tập-đóng quyền — sẽ đếm nhầm nguồn gốc của nó.
			//
			// Cái giá của việc dựa vào auto-grant: nó RỘNG HƠN statement tay, thêm
			// `GetQueueAttributes` và `GetQueueUrl`. Hai quyền đọc metadata trên
			// một DLQ — chấp nhận được, nhưng phải nằm trong tập-đóng của Task 9
			// một cách có ý thức, không phải lọt vào vì không ai nhìn.
			//
			// Chốt chặn: `function_co_on_failure_destination_tro_ve_ingest_dlq`
			// khẳng định CẢ HAI vế — có `EventInvokeConfig`, VÀ role có
			// `sqs:SendMessage` trên `IngestDlq`. Nếu CDK bỏ auto-grant ở bản sau,
			// vế thứ hai đỏ.
			this.function.configureAsyncInvoke(EventInvokeConfigOptions.builder()
					.onFailure(new SqsDestination(scheduleDlq))
					.build());
		}
		this.scheduleDlq = scheduleDlq;

		if (cfg.ingestionRate() != null) {
			Schedule.Builder.create(this, "IngestSchedule")
					.schedule(ScheduleExpression.rate(cfg.ingestionRate()))
					.description("Kích hoạt một lượt ingestion RSS/Atom")
					.target(LambdaInvoke.Builder.create(this.function)
							// Payload là HỢP ĐỒNG, không phải mặc định của EventBridge:
							// Phase 3 đổ message SQS vào cùng path /events, nên cần một
							// discriminator. Chọn sẵn bây giờ tốn 0 dòng.
							.input(ScheduleTargetInput.fromObject(
									Map.of("job", "ingest-feeds")))
							// MẶC ĐỊNH LÀ 185. Không set = một lỗi kéo dài thành 185
							// lần invoke.
							.retryAttempts(2)
							.maxEventAge(Duration.minutes(15))
							.deadLetterQueue(scheduleDlq)
							.build())
					.build();
		}

		// Schedule thứ hai — sweep (ADR-0014). Thưa hơn ingest có chủ đích: đây là
		// LƯỚI AN TOÀN, không phải đường chính. `ArticleAddedListener` đã lo bài mới
		// trong vòng vài phút; xem `EnvConfig.sweepRate` về cái giá của nhịp dày.
		if (cfg.sweepRate() != null) {
			Schedule.Builder.create(this, "SummarizeSweepSchedule")
					.schedule(ScheduleExpression.rate(cfg.sweepRate()))
					.description("Quét article còn thiếu tóm tắt trong cửa sổ 48h")
					.target(LambdaInvoke.Builder.create(this.function)
							.input(ScheduleTargetInput.fromObject(
									Map.of("job", "summarize-sweep")))
							.retryAttempts(2)
							.maxEventAge(Duration.minutes(15))
							.deadLetterQueue(scheduleDlq)
							.build())
					.build();
		}
	}

	public Function getFunction() {
		return function;
	}

	public FunctionUrl getFunctionUrl() {
		return functionUrl;
	}

	public LogGroup getLogGroup() {
		return logGroup;
	}

	/**
	 * `null` ở môi trường không có Schedule nào (`qa`) — chỗ gọi phải xử lý, đừng
	 * dựng alarm trỏ vào hư vô.
	 */
	public Queue getScheduleDlq() {
		return scheduleDlq;
	}

	public Queue getSummarizeDlq() {
		return summarizeDlq;
	}
}
