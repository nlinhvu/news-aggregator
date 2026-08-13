package dev.linhvu.news_aggregator.infra;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
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
import software.amazon.awscdk.services.scheduler.Schedule;
import software.amazon.awscdk.services.scheduler.ScheduleExpression;
import software.amazon.awscdk.services.scheduler.ScheduleTargetInput;
import software.amazon.awscdk.services.scheduler.targets.LambdaInvoke;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

public class AppStack extends Stack {

	private final Function webFunction;
	private final Function ingestFunction;
	private final Function summarizeFunction;
	private final FunctionUrl functionUrl;
	private final LogGroup logGroup;
	private final Queue scheduleDlq;
	private final Queue summarizeDlq;

	public AppStack(final Construct scope, final String id, final EnvConfig cfg,
			final ITable articlesTable, final ITable featureTogglesTable,
			final ITable sourcesTable, final ITable sessionsTable,
			final IdentityStack identity) {
		super(scope, id, StackProps.builder().env(cfg.awsEnvironment()).build());

		String imageDigest = StringParameter.valueForStringParameter(
				this, "/news/" + cfg.tagPrefix() + "/image-digest");

		IRepository repo = Repository.fromRepositoryArn(this, "SharedRepository",
				"arn:aws:ecr:" + cfg.region() + ":" + EnvConfig.TOOLING_ACCOUNT
						+ ":repository/news-aggregator");

		// Queue summarize + DLQ đứng TRƯỚC ba khối dựng function, vì `ingest` và
		// `summarize` đều tham chiếu `summarizeQueue.getQueueArn()`. Đặt trong
		// AppStack cùng lý do Schedule của Phase 2 (TDD §17 #2): đây là TRIGGER
		// CỦA FUNCTION, tách ra thành stack riêng chỉ tạo một stack chứa một
		// trigger trỏ ngược về stack bên cạnh.
		//
		// KHÔNG đổi construct id của hai queue: đổi là CloudFormation xoá queue cũ
		// và VỨT MỌI MESSAGE đang chờ trong đó.
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

		// ---------- web — chỉ ĐỌC, và là function duy nhất có Function URL ----------

		// Logical id "Function" GIỮ NGUYÊN cho `web`: đổi nó là CloudFormation xoá
		// function cũ và tạo function mới, kéo theo Function URL mới, kéo theo
		// EdgeStack phải đổi origin, kéo theo một khoảng downtime không cần thiết.
		// Hai function mới lấy id mới.
		//
		// Log group cũng GIỮ id cũ `LogGroup`, chứ KHÔNG lấy mặc định
		// `FunctionLogGroup`. `ObservabilityStack` của prod import nó, và
		// CloudFormation từ chối xoá một export đang được stack khác dùng — bản
		// trước của dòng này đã làm `Prod-AppStack` rollback. Xem `LambdaRole`.
		// Ích lợi kèm theo: log prod không mất.
		LambdaRole webRole = LambdaRole.create(this, "Function", "LogGroup", cfg);
		this.logGroup = webRole.logGroup();

		// AP1. ĐÚNG MỘT ARN, trỏ index chứ không trỏ bảng — cấp `Query` trên ARN
		// bảng trần là cấp luôn Query trên bảng, và `/index/*` là cấp trên mọi
		// index tương lai (kể cả `gsi-by-source` của slice 4, thứ phải được cấp
		// bằng một dòng CÓ Ý THỨC ở Task 19).
		webRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:Query"))
				.resources(List.of(articlesTable.getTableArn()
						+ "/index/" + DataStack.RECENT_INDEX_V2_NAME))
				.build());
		grantReadFeatureToggles(webRole.role(), featureTogglesTable);

		// Đường GHI đầu tiên của function phục vụ Internet, và nó chỉ chạm bảng
		// của CHÍNH người đang đăng nhập. `DeleteItem` là cho đăng xuất — thiếu
		// nó thì nút đăng xuất xoá được cookie nhưng không xoá được phiên, tức là
		// nó nói dối người dùng.
		webRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:GetItem", "dynamodb:PutItem",
						"dynamodb:UpdateItem", "dynamodb:DeleteItem"))
				.resources(List.of(sessionsTable.getTableArn()))
				.build());

		// Client secret của Cognito — secret THỨ HAI của chương trình, và nó đi
		// theo đúng khuôn của gemini key ở `summarize`: chỉ TÊN parameter đi qua
		// env var, giá trị nằm nguyên trong SSM SecureString và chỉ được giải mã
		// lúc runtime. Người vận hành ghi nó bằng credential của chính họ, nên
		// KHÔNG có `ssm:PutParameter` ở đây.
		String secretParameterName = "/news/" + cfg.tagPrefix() + "/cognito-client-secret";
		webRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("ssm:GetParameter"))
				.resources(List.of("arn:aws:ssm:" + cfg.region() + ":" + cfg.account()
						+ ":parameter" + secretParameterName))
				.build());
		webRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("kms:Decrypt"))
				.resources(List.of("arn:aws:kms:" + cfg.region() + ":" + cfg.account()
						+ ":alias/aws/ssm"))
				.build());

		Map<String, String> webEnv = baseEnv(cfg, "web", articlesTable,
				featureTogglesTable, sourcesTable, sessionsTable);
		// Bốn biến này CHỈ ở `web` (và sau này `admin`), không nằm trong `baseEnv`:
		// `ingest`/`summarize` không có bề mặt đăng nhập nào, nên với chúng đây là
		// cấu hình chết — và cấu hình chết là thứ lần audit sau phải truy nguyên.
		webEnv.put("NEWS_COGNITO_ISSUER_URI", identity.getIssuerUri());
		webEnv.put("NEWS_COGNITO_CLIENT_ID", identity.getClient().getUserPoolClientId());
		webEnv.put("NEWS_COGNITO_LOGOUT_URI", identity.getLogoutUri());
		webEnv.put("NEWS_COGNITO_SECRET_PARAMETER", secretParameterName);

		this.webFunction = buildFunction("Function", webRole, repo, imageDigest, webEnv);

		// Quyền cho CloudFront gọi Function URL này KHÔNG nằm ở đây — hai
		// `AWS::Lambda::Permission` (`lambda:InvokeFunctionUrl` +
		// `lambda:InvokeFunction`) đều ở EdgeStack, vì `SourceArn` của chúng phải
		// trỏ tới distribution. Đưa ngược về đây sẽ tạo circular dependency
		// AppStack → EdgeStack → AppStack, trừ khi bỏ `SourceArn` — mà bỏ thì mọi
		// distribution CloudFront đều gọi được.
		this.functionUrl = this.webFunction.addFunctionUrl(FunctionUrlOptions.builder()
				.authType(FunctionUrlAuthType.AWS_IAM)
				.build());

		CfnOutput.Builder.create(this, "FunctionUrl")
				.value(this.functionUrl.getUrl()).build();

		// ---------- ingest — ghi catalog, đọc/ghi sources, đẩy SQS ----------

		LambdaRole ingestRole = LambdaRole.create(this, "IngestFunction", cfg);

		// Đường GHI của catalog. Resource là ARN của BẢNG, không phải của index:
		// `PutItem` trên ARN index synth vẫn xanh và chỉ chết lúc runtime.
		ingestRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:PutItem"))
				.resources(List.of(articlesTable.getTableArn()))
				.build());
		// AP5 + AP6. `Scan` chỉ trên bảng này — nó bị chặn trên ~30 dòng bởi
		// master §2, trong khi `articles` tăng vô hạn. `UpdateItem` chứ KHÔNG
		// `PutItem`: `SourceRepository.updateFetchState` chỉ đụng ba attribute
		// trạng thái; `PutItem` sẽ xoá `name`/`feedUrl`/`enabled`.
		ingestRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:Scan", "dynamodb:UpdateItem"))
				.resources(List.of(sourcesTable.getTableArn()))
				.build());
		grantReadFeatureToggles(ingestRole.role(), featureTogglesTable);
		ingestRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("sqs:SendMessage"))
				.resources(List.of(summarizeQueue.getQueueArn()))
				.build());

		Map<String, String> ingestEnv = baseEnv(cfg, "ingest",
				articlesTable, featureTogglesTable, sourcesTable, sessionsTable);
		// CHỈ hai function nhận payload không-HTTP mới có biến này. Phải khớp
		// `news.platform.pass-through-path` bên repo app; hai bên không thấy nhau
		// nên compiler không bắt được lệch.
		ingestEnv.put("AWS_LWA_PASS_THROUGH_PATH", "/events");
		ingestEnv.put("NEWS_SUMMARIZE_QUEUE_URL", summarizeQueue.getQueueUrl());

		this.ingestFunction = buildFunction("IngestFunction", ingestRole, repo,
				imageDigest, ingestEnv);

		// ---------- summarize — đọc/ghi summary, gọi model, và NHẬN CẢ SWEEP ----------

		LambdaRole summarizeRole = LambdaRole.create(this, "SummarizeFunction", cfg);

		// AP4 (ghi `summary`) + AP8 (đọc bài cần tóm tắt). ARN BẢNG — dòng `Query`
		// dưới trỏ index vì nó query GSI, còn hai action này đọc/ghi theo partition
		// key. Cấp nhầm chiều thì synth vẫn xanh, cdk-nag vẫn im, và mọi lượt
		// summarize chết bằng AccessDenied.
		summarizeRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:GetItem", "dynamodb:UpdateItem"))
				.resources(List.of(articlesTable.getTableArn()))
				.build());
		// AP9 — sweep query trên gsi-recent-v2. ARN INDEX.
		summarizeRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:Query"))
				.resources(List.of(articlesTable.getTableArn()
						+ "/index/" + DataStack.RECENT_INDEX_V2_NAME))
				.build());
		grantReadFeatureToggles(summarizeRole.role(), featureTogglesTable);
		// Sweep là PRODUCER: nó đẩy message vào chính queue mà nó tiêu thụ.
		// Quên dòng này thì sweep chạy, log `enqueued=N`, và không message nào
		// tới nơi — AccessDenied nằm trong một exception bị nuốt.
		summarizeRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("sqs:SendMessage"))
				.resources(List.of(summarizeQueue.getQueueArn()))
				.build());
		// Secret duy nhất mà function này chạm tới. Đọc đúng MỘT parameter —
		// KHÔNG `GetParametersByPath`: ta biết chính xác tên, nên quyền quét cây
		// là quyền thừa và nó với tới cả image digest lẫn mọi config tương lai.
		// KHÔNG `PutParameter`: key do người vận hành ghi bằng credential của
		// chính họ (Phase 3 TDD §17 #10).
		String keyParameterName = "/news/" + cfg.tagPrefix() + "/gemini-api-key";
		summarizeRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("ssm:GetParameter"))
				.resources(List.of("arn:aws:ssm:" + cfg.region() + ":" + cfg.account()
						+ ":parameter" + keyParameterName))
				.build());
		// SecureString dùng khoá quản lý `alias/aws/ssm`. Ghim về đúng khoá đó —
		// để `Resource: *` thì role giải mã được MỌI thứ mã hoá trong account.
		summarizeRole.role().addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("kms:Decrypt"))
				.resources(List.of("arn:aws:kms:" + cfg.region() + ":" + cfg.account()
						+ ":alias/aws/ssm"))
				.build());

		Map<String, String> summarizeEnv = baseEnv(cfg, "summarize",
				articlesTable, featureTogglesTable, sourcesTable, sessionsTable);
		summarizeEnv.put("AWS_LWA_PASS_THROUGH_PATH", "/events");
		summarizeEnv.put("NEWS_SUMMARIZE_QUEUE_URL", summarizeQueue.getQueueUrl());
		// Chỉ TÊN parameter đi qua đây, không phải giá trị: key nằm nguyên trong
		// SSM SecureString và chỉ được giải mã lúc runtime. `ssm-secure` dynamic
		// reference KHÔNG dùng được cho env var của Lambda, nên truyền tham chiếu
		// là cách DUY NHẤT giữ được cả audit lẫn xoay key không cần redeploy.
		summarizeEnv.put("NEWS_GEMINI_KEY_PARAMETER", keyParameterName);
		// Ghim phiên bản, KHÔNG dùng alias `gemini-flash-lite-latest`: một alias
		// đổi model mà không có commit nào. Cái giá của việc ghim là model sẽ bị
		// Google ngưng — và nó ĐÃ xảy ra ngày 2026-08-11.
		summarizeEnv.put("NEWS_SUMMARIZATION_MODEL", "gemini-3.5-flash-lite");

		this.summarizeFunction = buildFunction("SummarizeFunction", summarizeRole, repo,
				imageDigest, summarizeEnv);

		// Consumer. `addEventSource` tự cấp ReceiveMessage/DeleteMessage/
		// GetQueueAttributes — KHÔNG viết tay lại, làm thế sẽ có hai policy chồng
		// nhau và cdk-nag báo trùng.
		//
		// KHÔNG đặt maxConcurrency: nó tắt mất tối ưu hoá poll-khi-rỗng của Lambda
		// và làm ESM gọi SQS nhiều hơn 24/7 (Phase 3 TDD §17 #9).
		this.summarizeFunction.addEventSource(SqsEventSource.Builder.create(summarizeQueue)
				.batchSize(10)
				// Gom batch để né cold start: 45 bài mà mỗi bài một invoke thì
				// riêng tiền boot Spring đã gấp mấy lần tiền model.
				.maxBatchingWindow(Duration.seconds(60))
				// BẮT BUỘC. Thiếu nó thì Lambda BỎ QUA `batchItemFailures` và coi
				// cả batch là thành công — message hỏng biến mất im lặng.
				.reportBatchItemFailures(true)
				.build());

		// ---------- hai Schedule, đấu vào ĐÚNG function ----------

		// MỘT DLQ cho cả hai schedule. Tách ra hai hàng đợi chỉ làm người vận hành
		// phải nhớ kiểm hai chỗ cho cùng một loại sự cố, trong khi payload trong
		// message đã phân biệt được nguồn.
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
		this.scheduleDlq = scheduleDlq;

		if (cfg.ingestionRate() != null) {
			// TẦNG ② của ADR-0015, nay phải khai HAI LẦN vì có hai function chạy
			// bất đồng bộ. Scheduler gọi Lambda BẤT ĐỒNG BỘ: nó trả `202 Accepted`
			// ngay khi Lambda NHẬN sự kiện, và từ giây đó `retryAttempts(2)` + DLQ
			// của Schedule không còn biết gì nữa. Chúng canh tầng ① (không giao
			// được việc). Lỗi khi HÀM CHẠY rơi vào async retry rồi tới ĐÂY.
			//
			// `SqsDestination.bind()` tự gọi `queue.grantSendMessages(fn)`, nên
			// KHÔNG viết tay `sqs:SendMessage` trên `IngestDlq` — thêm statement
			// tay nữa chỉ tạo bản sao mà lần audit IAM sau phải truy nguyên.
			this.ingestFunction.configureAsyncInvoke(EventInvokeConfigOptions.builder()
					.onFailure(new SqsDestination(scheduleDlq))
					.build());

			Schedule.Builder.create(this, "IngestSchedule")
					.schedule(ScheduleExpression.rate(cfg.ingestionRate()))
					.description("Kích hoạt một lượt ingestion RSS/Atom")
					.target(LambdaInvoke.Builder.create(this.ingestFunction)
							// Payload là HỢP ĐỒNG, không phải mặc định của
							// EventBridge: message SQS đổ vào cùng path /events nên
							// cần một discriminator.
							.input(ScheduleTargetInput.fromObject(
									Map.of("job", "ingest-feeds")))
							// MẶC ĐỊNH LÀ 185. Không set = một lỗi kéo dài thành
							// 185 lần invoke.
							.retryAttempts(2)
							.maxEventAge(Duration.minutes(15))
							.deadLetterQueue(scheduleDlq)
							.build())
					.build();
		}

		if (cfg.sweepRate() != null) {
			// SWEEP CHẠY TRÊN `summarize`, KHÔNG TRÊN `ingest` — dù nó cũng là một
			// EventBridge Schedule y hệt `ingest-feeds`. Đây là chỗ ADR-0020 driver
			// #2 trở nên cụ thể: cắt theo ranh giới NGHIỆP VỤ nghĩa là mọi việc của
			// module `summarization` ở cùng một chỗ, bất kể ai đánh thức nó.
			//
			// Thưa hơn ingest có chủ đích: đây là LƯỚI AN TOÀN, không phải đường
			// chính. `ArticleAddedListener` đã lo bài mới trong vòng vài phút.
			this.summarizeFunction.configureAsyncInvoke(EventInvokeConfigOptions.builder()
					.onFailure(new SqsDestination(scheduleDlq))
					.build());

			Schedule.Builder.create(this, "SummarizeSweepSchedule")
					.schedule(ScheduleExpression.rate(cfg.sweepRate()))
					.description("Quét article còn thiếu tóm tắt trong cửa sổ 48h")
					.target(LambdaInvoke.Builder.create(this.summarizeFunction)
							.input(ScheduleTargetInput.fromObject(
									Map.of("job", "summarize-sweep")))
							.retryAttempts(2)
							.maxEventAge(Duration.minutes(15))
							.deadLetterQueue(scheduleDlq)
							.build())
					.build();
		}
	}

	/**
	 * Ba function khác nhau ở role, trigger và env var — KHÔNG khác ở image,
	 * kiến trúc, memory hay timeout. Helper này giữ phần "không khác" ở đúng
	 * một chỗ để một lần đổi memory không thành ba lần sửa lệch nhau.
	 *
	 * `env` nhận vào là bản ĐÃ đầy đủ của function đó. Cố ý không merge với một
	 * map mặc định nào: env var là bề mặt cấu hình mà `SecurityBoundaryTest`
	 * kiểm từng dòng, và một cơ chế merge sẽ làm "function này có biến gì" trở
	 * thành câu hỏi phải suy luận thay vì đọc.
	 */
	private Function buildFunction(String id, LambdaRole lambdaRole,
			IRepository repo, String imageDigest, Map<String, String> env) {
		Function fn = Function.Builder.create(this, id)
				.role(lambdaRole.role())
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
				.logGroup(lambdaRole.logGroup())
				.environment(env)
				.build();

		// Cùng lý do như bản một-function: `Code.fromEcrImage` chọn `@` hay `:`
		// bằng `tagOrDigest.startsWith("sha256:")`, mà digest của ta là token
		// chưa resolve nên check đó luôn false và CDK nối bằng `:` — Lambda hiểu
		// thành TAG và deploy chết.
		CfnFunction cfn = (CfnFunction) fn.getNode().getDefaultChild();
		cfn.addPropertyOverride("Code.ImageUri", repo.repositoryUriForDigest(imageDigest));
		return fn;
	}

	/**
	 * Env var mà cả ba function đều cần: định danh môi trường, tên bảng, và cấu
	 * hình OTel. KHÔNG chứa `AWS_LWA_PASS_THROUGH_PATH` — chỉ hai function
	 * nhận payload không-HTTP mới có nó, và `web` có nó là mở một đường vào
	 * không ai dùng.
	 */
	private Map<String, String> baseEnv(EnvConfig cfg, String profile,
			ITable articlesTable, ITable featureTogglesTable, ITable sourcesTable,
			ITable sessionsTable) {
		Map<String, String> env = new HashMap<>();
		env.put("SPRING_PROFILES_ACTIVE", "aws," + profile);
		env.put("NEWS_ENV", cfg.tagPrefix());
		env.put("NEWS_ARTICLES_TABLE", articlesTable.getTableName());
		env.put("NEWS_TOGGLES_TABLE", featureTogglesTable.getTableName());
		env.put("NEWS_SOURCES_TABLE", sourcesTable.getTableName());
		// TÊN bảng đi cho cả ba function, QUYỀN thì không — chỉ `web` được cấp
		// action nào trên bảng này. Đi theo đúng lối `NEWS_SOURCES_TABLE`: một cái
		// tên không mở được cửa nào, còn giữ `baseEnv` là một danh mục đầy đủ thì
		// "function này thấy bảng nào" đọc được thay vì phải suy luận.
		env.put("NEWS_SESSIONS_TABLE", sessionsTable.getTableName());
		// ADR-0015. Không có dòng này, LWA trả HTTP status trong BODY và Lambda
		// coi mọi response là thành công — kể cả 500.
		//
		// CHỈ 5xx. Đưa 4xx vào là biến mỗi con bot quét 404 thành một "lỗi
		// Lambda" và tự đầu độc alarm.
		env.put("AWS_LWA_ERROR_STATUS_CODES", "500-599");
		// Tên service trong X-Ray, nay CÓ HẬU TỐ PROFILE. Ba process khác nhau
		// cùng tên service sẽ làm service map của X-Ray gộp chúng lại và câu hỏi
		// "lượt hỏng này thuộc function nào" mất câu trả lời. Cái giá: trace của
		// Phase 4 mang tên cũ nên biểu đồ có một đường gãy tại ngày deploy.
		env.put("OTEL_SERVICE_NAME", "news-aggregator-" + profile);
		// TRACES_ENDPOINT chứ KHÔNG phải `OTEL_EXPORTER_OTLP_ENDPOINT` dạng chung:
		// Boot 4.1 map biến dạng chung vào CẢ BA endpoint trace/metric/log cùng
		// lúc, mà X-Ray chỉ nhận trace.
		//
		// ĐIỀU KIỆN TIÊN QUYẾT ngoài repo: Transaction Search phải BẬT ở account
		// (`aws xray get-trace-segment-destination` trả `CloudWatchLogs`).
		env.put("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
				"https://xray." + cfg.region() + ".amazonaws.com/v1/traces");
		return env;
	}

	/**
	 * Bộ action đọc ra từ bytecode của togglz-dynamodb 4.6.2:
	 *   DynamoDBStateRepositoryBuilder.initializeTable() → describeTable
	 *   DynamoDBStateRepository.getFeatureState()        → getItem
	 *
	 * `DescribeTable` là cái dễ quên nhất và chí mạng nhất: builder gọi nó ĐÚNG
	 * MỘT LẦN lúc dựng bean rồi ném RuntimeException. Bean là @Lazy nên lần chết
	 * đầu tiên rơi vào request đầu tiên chạm tới flag — trên môi trường thật.
	 *
	 * KHÔNG cấp `UpdateItem` cho bất kỳ function nào ở đây. Đường GHI chỉ thuộc
	 * về `admin` và nó được cấp ở Task 26, tường minh.
	 */
	private static void grantReadFeatureToggles(Role role, ITable featureTogglesTable) {
		role.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("dynamodb:DescribeTable", "dynamodb:GetItem"))
				.resources(List.of(featureTogglesTable.getTableArn()))
				.build());
	}

	public Function getWebFunction() {
		return webFunction;
	}

	public Function getIngestFunction() {
		return ingestFunction;
	}

	public Function getSummarizeFunction() {
		return summarizeFunction;
	}

	/** Thứ tự cố định: web, ingest, summarize — chỗ gọi dựa vào nó để đặt tên. */
	public List<Function> getAllFunctions() {
		return List.of(webFunction, ingestFunction, summarizeFunction);
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
