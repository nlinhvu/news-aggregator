package dev.linhvu.news_aggregator.infra;

import java.util.List;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudfront.IDistribution;
import software.amazon.awscdk.services.iam.ArnPrincipal;
import software.amazon.awscdk.services.iam.IPrincipal;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.s3.IBucket;
import software.constructs.Construct;

public class CicdStack extends Stack {

	public CicdStack(final Construct scope, final String id, final EnvConfig cfg,
			final List<? extends IFunction> functions, final IBucket bucket,
			final IDistribution distribution) {
		super(scope, id, StackProps.builder().env(cfg.awsEnvironment()).build());

		IPrincipal hub = new ArnPrincipal("arn:aws:iam::" + EnvConfig.TOOLING_ACCOUNT
				+ ":role/GhaHubRole-" + cfg.name());

		Role appDeploy = Role.Builder.create(this, "AppDeployRole")
				.roleName("AppDeployRole")
				.assumedBy(hub)
				.build();
		// AppDeployRole phải cập nhật được CẢ BỐN function bằng cùng một digest.
		// Liệt kê ARN tường minh chứ không wildcard `function:*`: một function thứ
		// năm phải là một lần sửa CÓ Ý THỨC, không phải tự động được cấp quyền.
		// Danh sách đến từ `AppStack.getAllFunctions()`, nên thêm function ở đó là
		// đủ — nhưng `app-deploy.yml` thì KHÔNG tự biết: nó liệt kê từng lệnh
		// `update-function-code` bằng tay và một function thiếu ở đó sẽ chạy code
		// cũ mà không lệnh nào hỏng.
		appDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("lambda:UpdateFunctionCode", "lambda:GetFunction"))
				.resources(functions.stream().map(IFunction::getFunctionArn).toList())
				.build());
		appDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("ssm:PutParameter", "ssm:GetParameter"))
				.resources(List.of("arn:aws:ssm:" + cfg.region() + ":" + cfg.account()
						+ ":parameter/news/" + cfg.tagPrefix() + "/image-digest"))
				.build());
		// Cross-account cần allow ở CẢ HAI phía. Repo policy bên tooling cấp cho
		// `arn:aws:iam::<env>:root` mới chỉ DELEGATE quyền xuống account này —
		// principal gọi API vẫn phải được chính identity-based policy của nó cho
		// phép. Thiếu nó, `lambda:UpdateFunctionCode` trả về "Lambda does not have
		// permission to access the ECR image", câu lỗi trỏ vào ECR trong khi thứ
		// thiếu nằm ở ĐÂY. `cdk deploy` không lộ ra vì cdk-…-cfn-exec-role có
		// AdministratorAccess; chỉ pipeline ứng dụng mới đụng.
		appDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"))
				.resources(List.of("arn:aws:ecr:" + EnvConfig.TOOLING_REGION + ":"
						+ EnvConfig.TOOLING_ACCOUNT + ":repository/"
						+ EnvConfig.ECR_REPOSITORY_NAME))
				.build());

		Role webDeploy = Role.Builder.create(this, "WebDeployRole")
				.roleName("WebDeployRole")
				.assumedBy(hub)
				.build();
		// KHÔNG dùng `bucket.grantReadWrite()`: nó cấp action dạng wildcard
		// (`s3:GetObject*`, `s3:List*`, `s3:DeleteObject*`…) và kéo theo cả
		// `s3:PutObjectVersionAcl`. Đây là đúng bộ action mà `aws s3 sync
		// --delete` cần, liệt kê tường minh — cdk-nag AwsSolutions-IAM5 bắt
		// wildcard action, và ở đây nó bắt đúng.
		webDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("s3:ListBucket"))
				.resources(List.of(bucket.getBucketArn()))
				.build());
		webDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("s3:GetObject", "s3:PutObject", "s3:DeleteObject",
						"s3:AbortMultipartUpload"))
				.resources(List.of(bucket.getBucketArn() + "/*"))
				.build());
		webDeploy.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("cloudfront:CreateInvalidation"))
				.resources(List.of("arn:aws:cloudfront::" + cfg.account()
						+ ":distribution/" + distribution.getDistributionId()))
				.build());

		// Role hẹp nhất chương trình: một action, một resource.
		// KHÔNG gộp vào AppDeployRole — role đó có `lambda:UpdateFunctionCode`, và
		// gộp lại nghĩa là job smoke (chạy sau MỌI lần deploy) mang theo quyền ghi
		// code. Master §4 nguyên tắc 8: phân vân thì tách.
		//
		// `roleName` phải CỐ ĐỊNH: OidcHubStack nằm ở account tooling, không tham
		// chiếu được resource của stack ở account khác nên nó dựng ARN bằng chuỗi.
		// Tên sinh tự động sẽ khiến chuỗi đó sai và job smoke đỏ ở bước AssumeRole,
		// với thông báo không liên quan gì tới nguyên nhân.
		//
		// KHÔNG hậu tố theo môi trường. Mỗi môi trường là một ACCOUNT riêng, nên
		// account id trong ARN đã tách chúng ra rồi — `-dev` chỉ lặp lại thông tin
		// đã có. Đặt tên trần giống `AppDeployRole` và `WebDeployRole` ngay trên.
		Role smokeRole = Role.Builder.create(this, "SmokeRole")
				.roleName("SmokeRole")
				.assumedBy(hub)
				.build();
		// SmokeRole chỉ invoke được hai function có đường scheduled — `web` và
		// `admin` có Function URL nên smoke test chạm chúng bằng `curl`, không cần
		// quyền invoke. Thứ tự của `getAllFunctions()` là hợp đồng: [0]=web,
		// [1]=ingest, [2]=summarize, [3]=admin — `admin` nối vào CUỐI chính là để
		// hai chỉ số dưới không bị đẩy đi một bậc.
		smokeRole.addToPolicy(PolicyStatement.Builder.create()
				.actions(List.of("lambda:InvokeFunction"))
				.resources(List.of(functions.get(1).getFunctionArn(),
						functions.get(2).getFunctionArn()))
				.build());

		CfnOutput.Builder.create(this, "SmokeRoleArn")
				.value(smokeRole.getRoleArn()).build();
	}
}
