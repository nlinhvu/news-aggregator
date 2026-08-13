package dev.linhvu.news_aggregator.infra;

import java.util.List;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.ProjectionType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.constructs.Construct;

public class DataStack extends Stack {

	/**
	 * Tên GSI của AP1. Là hằng số public vì AppStack cấp quyền `dynamodb:Query`
	 * trỏ THẲNG vào ARN của index này — hai nơi viết rời cùng một chuỗi thì lệch
	 * nhau lúc nào không hay, và hậu quả là AccessDenied lúc runtime chứ không
	 * phải lỗi lúc synth.
	 *
	 * Tên có hậu tố `-v2` vì nó thay cho `gsi-recent` đời đầu: projection của một
	 * GSI là BẤT BIẾN, nên thêm `excerpt` vào index cũ làm CloudFormation
	 * UPDATE_FAILED và pipeline Infra đỏ (2026-08-10). Đường duy nhất là index
	 * tên mới. Index cũ đã bị xoá ở lần deploy THỨ HAI của migrate — CloudFormation
	 * chỉ thêm/xoá được MỘT GSI mỗi lần update stack, nên tạo v2 và xoá v1 không
	 * thể nằm cùng một deploy.
	 */
	public static final String RECENT_INDEX_V2_NAME = "gsi-recent-v2";

	/**
	 * Partition key của bảng feature-toggles. Giá trị này do `togglz-dynamodb`
	 * quy định chứ không phải ta chọn — xem comment tại chỗ tạo bảng. Là hằng số
	 * public để `DataStackTest` khẳng định lại đúng chuỗi đó thay vì chép tay.
	 */
	public static final String TOGGLZ_PRIMARY_KEY = "featureName";

	private final Table articlesTable;
	private final Table featureTogglesTable;
	private final Table sourcesTable;
	private final Table sessionsTable;

	public DataStack(final Construct scope, final String id, final EnvConfig cfg) {
		super(scope, id, StackProps.builder()
				.env(cfg.awsEnvironment())
				.terminationProtection(cfg.terminationProtection())
				.build());

		this.articlesTable = Table.Builder.create(this, "ArticlesTable")
				.partitionKey(Attribute.builder()
						.name("articleId").type(AttributeType.STRING).build())
				.billingMode(BillingMode.PAY_PER_REQUEST)
				.removalPolicy(cfg.removalPolicy())
				.pointInTimeRecoverySpecification(
						PointInTimeRecoverySpecification.builder()
								.pointInTimeRecoveryEnabled(cfg.terminationProtection())
								.build())
				.build();

		// AP1: lấy N article mới nhất. Partition key HẰNG SỐ "ALL" —
		// xem TDD §6 "Worked example" về vì sao đây không phải hot partition
		// ở khối lượng này (tới hạn sau ~68 năm).
		//
		// AP9 dùng CHUNG index này: `publishedAt` là sort key nên cửa sổ thời gian
		// của sweep là điều kiện KEY thật, và `excerpt` nằm trong projection nên
		// `attribute_exists(excerpt)` lọc được. Thiếu vế sau thì filter khớp KHÔNG
		// item nào — im lặng, không lỗi.
		this.articlesTable.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
				.indexName(RECENT_INDEX_V2_NAME)
				.partitionKey(Attribute.builder()
						.name("listBucket").type(AttributeType.STRING).build())
				.sortKey(Attribute.builder()
						.name("publishedAt").type(AttributeType.STRING).build())
				.projectionType(ProjectionType.INCLUDE)
				// Danh sách này là BẤT BIẾN sau lần deploy đầu — sửa nó rồi deploy là
				// UPDATE_FAILED, và cách chữa duy nhất lại là một index tên mới nữa.
				.nonKeyAttributes(List.of("title", "canonicalUrl", "sourceName",
						"summary", "excerpt"))
				.build());

		CfnOutput.Builder.create(this, "ArticlesTableName")
				.value(articlesTable.getTableName()).build();

		// Schema do module togglz-dynamodb quy định, và nó KHÔNG cấu hình được:
		// `DynamoDBStateRepositoryBuilder.primaryKey` là `private final` gán cứng
		// chuỗi "featureName" — chữ f THƯỜNG. Builder chỉ mở ra `withStateStoredInTable`
		// và `withObjectMapper`. Viết hoa thành "FeatureName" thì bảng tạo ra vẫn hợp
		// lệ, `cdk deploy` vẫn xanh, và mọi lần đọc flag chết bằng ValidationException
		// "provided key element does not match the schema" — ở lần request đầu tiên
		// chạm tới flag, tức là trên môi trường thật chứ không phải lúc build.
		//
		// PITR soi gương `articlesTable` (bật ở prod, tắt ở dev) chứ không tắt hẳn.
		// Bảng này chỉ có 6 dòng nên chi phí backup không đáng kể, còn thứ nó giữ là
		// TRẠNG THÁI VẬN HÀNH: một lệnh `delete-item` nhầm ở prod sẽ tắt feature mà
		// không để lại dấu vết nào ngoài việc trang web đổi hành vi.
		this.featureTogglesTable = Table.Builder.create(this, "FeatureTogglesTable")
				.partitionKey(Attribute.builder()
						.name(TOGGLZ_PRIMARY_KEY).type(AttributeType.STRING).build())
				.billingMode(BillingMode.PAY_PER_REQUEST)
				.removalPolicy(cfg.removalPolicy())
				.pointInTimeRecoverySpecification(
						PointInTimeRecoverySpecification.builder()
								.pointInTimeRecoveryEnabled(cfg.terminationProtection())
								.build())
				.build();

		CfnOutput.Builder.create(this, "FeatureTogglesTableName")
				.value(featureTogglesTable.getTableName()).build();

		// Không GSI: bảng bị chặn trên ~30 dòng bởi master §2, và AP5 (lấy mọi
		// nguồn đang bật) đọc bằng Scan tốn ~1 RCU. GSI với partition key boolean
		// là hot partition hai giá trị đổi lấy con số không.
		//
		// PITR soi gương hai bảng kia: bảng này giữ TRẠNG THÁI VẬN HÀNH — mất
		// `etag` nghĩa là mọi nguồn tải full một lượt, mất `enabled` nghĩa là một
		// nguồn đã tắt bỗng chạy lại.
		this.sourcesTable = Table.Builder.create(this, "SourcesTable")
				.partitionKey(Attribute.builder()
						.name("sourceId").type(AttributeType.STRING).build())
				.billingMode(BillingMode.PAY_PER_REQUEST)
				.removalPolicy(cfg.removalPolicy())
				.pointInTimeRecoverySpecification(
						PointInTimeRecoverySpecification.builder()
								.pointInTimeRecoveryEnabled(cfg.terminationProtection())
								.build())
				.build();

		CfnOutput.Builder.create(this, "SourcesTableName")
				.value(sourcesTable.getTableName()).build();

		// Session store của mô hình BFF ([ADR-0018]). Item chứa token của Cognito,
		// mã hoá tại chỗ nghỉ bằng khoá AWS-owned — mặc định của DynamoDB, miễn
		// phí, và đủ: khoá tự quản chỉ đổi ai giữ chìa, không đổi bề mặt tấn công
		// thật ở đây (một execution role bị chiếm vẫn đọc được qua API).
		//
		// KHÔNG GSI: đúng một access pattern (AP12 — tra theo `sessionId`).
		//
		// PITR TẮT kể cả ở prod, và đây là khác biệt CÓ CHỦ Ý với ba bảng trên.
		// Bảng này chứa trạng thái PHÙ DU có TTL: khôi phục nó về một thời điểm
		// trong quá khứ nghĩa là hồi sinh những phiên đã đăng xuất — một tính
		// năng chống bảo mật. Mất bảng này là mọi người phải đăng nhập lại, hết.
		// Khác biệt không có triệu chứng nên nó có chốt chặn riêng:
		// `DataStackTest#sessions_khong_bao_gio_bat_pitr_ke_ca_o_prod`.
		this.sessionsTable = Table.Builder.create(this, "SessionsTable")
				.partitionKey(Attribute.builder()
						.name("sessionId").type(AttributeType.STRING).build())
				.timeToLiveAttribute("expiresAt")
				.billingMode(BillingMode.PAY_PER_REQUEST)
				.removalPolicy(cfg.removalPolicy())
				.build();

		CfnOutput.Builder.create(this, "SessionsTableName")
				.value(sessionsTable.getTableName()).build();
	}

	public Table getArticlesTable() {
		return articlesTable;
	}

	public Table getFeatureTogglesTable() {
		return featureTogglesTable;
	}

	public Table getSourcesTable() {
		return sourcesTable;
	}

	public Table getSessionsTable() {
		return sessionsTable;
	}
}
