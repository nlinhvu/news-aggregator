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
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionUrl;
import software.amazon.awscdk.services.lambda.FunctionUrlAuthType;
import software.amazon.awscdk.services.lambda.FunctionUrlOptions;
import software.amazon.awscdk.services.lambda.Handler;
import software.amazon.awscdk.services.lambda.Runtime;
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

	public AppStack(final Construct scope, final String id, final EnvConfig cfg,
			final ITable articlesTable, final ITable featureTogglesTable,
			final ITable sourcesTable) {
		super(scope, id, StackProps.builder().env(cfg.awsEnvironment()).build());

		String imageDigest = StringParameter.valueForStringParameter(
				this, "/news/" + cfg.tagPrefix() + "/image-digest");

		IRepository repo = Repository.fromRepositoryArn(this, "SharedRepository",
				"arn:aws:ecr:" + cfg.region() + ":" + EnvConfig.TOOLING_ACCOUNT
						+ ":repository/news-aggregator");

		LogGroup logGroup = LogGroup.Builder.create(this, "LogGroup")
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
		logGroup.grantWrite(executionRole);

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
		// Cấp CẢ HAI index trong lúc migrate sang `gsi-recent-v2`: code còn đọc v1
		// tới Task 13, và thiếu ARN v2 ở đây thì lần chuyển đọc đó là AccessDenied
		// lúc runtime trong khi `cdk synth` vẫn xanh. ARN v1 biến mất ở Task 17,
		// sau khi không còn ai đọc index cũ.
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:Query"))
				.resources(List.of(
						articlesTable.getTableArn()
								+ "/index/" + DataStack.RECENT_INDEX_NAME,
						articlesTable.getTableArn()
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
		Queue summarizeDlq = Queue.Builder.create(this, "SummarizeDlq")
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
						.queue(summarizeDlq)
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
		env.put("NEWS_SUMMARIZE_QUEUE_URL", summarizeQueue.getQueueUrl());
		// Chỉ TÊN parameter đi qua đây, không phải giá trị: key nằm nguyên trong
		// SSM SecureString và chỉ được giải mã lúc runtime bằng đúng hai quyền ở
		// trên. `ssm-secure` dynamic reference KHÔNG dùng được cho env var của
		// Lambda (CloudFormation chỉ hỗ trợ nó trên 11 cặp resource/property, và
		// `AWS::Lambda::Function` không nằm trong đó), nên truyền tham chiếu là
		// cách DUY NHẤT giữ được cả audit lẫn xoay key không cần redeploy.
		env.put("NEWS_GEMINI_KEY_PARAMETER", keyParameterName);
		env.put("NEWS_SUMMARIZATION_MODEL", "gemini-3.5-flash-lite");

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
				.logGroup(logGroup)
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
		}

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
}
