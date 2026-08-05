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
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.scheduler.Schedule;
import software.amazon.awscdk.services.scheduler.ScheduleExpression;
import software.amazon.awscdk.services.scheduler.ScheduleTargetInput;
import software.amazon.awscdk.services.scheduler.targets.LambdaInvoke;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

public class AppStack extends Stack {

	private final Function function;
	private final FunctionUrl functionUrl;

	public AppStack(final Construct scope, final String id, final EnvConfig cfg,
			final ITable articlesTable, final ITable featureTogglesTable) {
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
		// Đường GHI duy nhất vào bảng là `SeedApplication`, một main riêng người
		// vận hành chạy bằng credential của chính họ, không đi qua role này.
		// Thêm operation mới cho Lambda thì phải thêm action ở ĐÂY một cách có ý thức.
		executionRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:Query"))
				.resources(List.of(articlesTable.getTableArn()
						+ "/index/" + DataStack.RECENT_INDEX_NAME))
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

		Map<String, String> env = new HashMap<>();
		env.put("SPRING_PROFILES_ACTIVE", "aws");
		env.put("NEWS_ENV", cfg.tagPrefix());
		env.put("NEWS_ARTICLES_TABLE", articlesTable.getTableName());
		env.put("NEWS_TOGGLES_TABLE", featureTogglesTable.getTableName());
		// Khai TƯỜNG MINH dù trùng mặc định của LWA — để nó grep được và test
		// được. Phải khớp `IngestionController.PASS_THROUGH_PATH` bên repo app;
		// hai bên không thấy nhau nên compiler không bắt được lệch.
		env.put("AWS_LWA_PASS_THROUGH_PATH", "/events");

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

		// Schedule và DLQ nằm trong AppStack chứ không tách stack riêng (TDD §17 #2):
		// schedule là TRIGGER CỦA FUNCTION, tách ra sẽ thành một stack chỉ chứa
		// một trigger trỏ ngược về stack bên cạnh.
		if (cfg.ingestionRate() != null) {
			Queue dlq = Queue.Builder.create(this, "IngestDlq")
					.retentionPeriod(Duration.days(14))
					.enforceSsl(true)
					.build();

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
							.deadLetterQueue(dlq)
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
