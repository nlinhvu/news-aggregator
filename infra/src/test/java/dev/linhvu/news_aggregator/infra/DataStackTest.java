package dev.linhvu.news_aggregator.infra;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class DataStackTest {

	private Template dataStack(EnvConfig cfg) {
		App app = new App();
		AppStage stage = new AppStage(app, cfg);
		return Template.fromStack((Stack) stage.getNode().findChild("DataStack"));
	}

	/**
	 * Projection PHẢI là INCLUDE, không phải ALL.
	 *
	 * Đây là quyết định trả cổ tức ở Phase 5: scraping sẽ thêm full text vào
	 * Article, và ALL sẽ nhân đôi toàn bộ khối đó vào GSI — gấp đôi cả
	 * storage lẫn write cost. Nhìn trang web không thấy được, nên phải bắt
	 * ở đây.
	 */
	@Test
	void gsi_projection_la_INCLUDE_khong_phai_ALL() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of(
						"GlobalSecondaryIndexes", Match.arrayWith(List.of(
								Match.objectLike(Map.of(
										"IndexName", "gsi-recent",
										"Projection", Match.objectLike(Map.of(
												"ProjectionType", "INCLUDE"))))
						))
				)));
	}

	/** On-demand billing — master §6.3 cấm mọi thứ tính tiền theo giờ. */
	@Test
	void billing_la_on_demand() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of("BillingMode", "PAY_PER_REQUEST")));
	}

	/**
	 * Prod phải RETAIN + termination protection; dev thì không.
	 * Đây là lý do DataStack được tách ra khỏi các stack khác.
	 */
	@Test
	void prod_bao_ve_du_lieu_dev_thi_khong() {
		dataStack(EnvConfig.PROD).hasResource("AWS::DynamoDB::Table",
				Match.objectLike(Map.of("DeletionPolicy", "Retain")));
		dataStack(EnvConfig.DEV).hasResource("AWS::DynamoDB::Table",
				Match.objectLike(Map.of("DeletionPolicy", "Delete")));
	}

	/**
	 * Partition key của bảng toggles phải là ĐÚNG chuỗi `featureName`.
	 *
	 * `togglz-dynamodb` gán cứng tên đó trong `DynamoDBStateRepositoryBuilder`
	 * (`private final primaryKey`), không có setter nào. Viết hoa thành
	 * `FeatureName` — như bản đầu của plan — vẫn tạo được bảng hợp lệ và
	 * `cdk deploy` vẫn xanh; chỗ vỡ là mọi lần đọc flag, bằng
	 * `ValidationException` trên môi trường thật.
	 *
	 * Không có tầng nào khác bắt được lệch này: T2 dùng Floci mà Floci cũng chỉ
	 * kiểm schema theo bảng nó được tạo, còn cdk-nag không biết gì về hợp đồng
	 * giữa tên attribute và thư viện.
	 */
	@Test
	void partition_key_cua_bang_toggles_dung_hop_dong_cua_togglz() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of(
						"KeySchema", List.of(Map.of(
								"AttributeName", DataStack.TOGGLZ_PRIMARY_KEY,
								"KeyType", "HASH")))));
	}

	/**
	 * PITR của bảng toggles cũng phải bật ở prod, tắt ở dev.
	 *
	 * Khẳng định RIÊNG cho bảng này chứ không dựa vào test PITR bên dưới:
	 * `hasResourceProperties` xanh khi CÓ MỘT resource khớp, nên từ lúc
	 * `DataStack` có hai bảng, test kia đã được `articlesTable` làm cho xanh
	 * bất kể bảng thứ hai cấu hình thế nào. Ghim bằng `KeySchema` là cách trỏ
	 * đích danh vào bảng cần kiểm.
	 */
	@Test
	void pitr_cua_bang_toggles_bat_o_prod_tat_o_dev() {
		for (Map.Entry<EnvConfig, Boolean> e
				: Map.of(EnvConfig.PROD, true, EnvConfig.DEV, false).entrySet()) {
			dataStack(e.getKey()).hasResourceProperties("AWS::DynamoDB::Table",
					Match.objectLike(Map.of(
							"KeySchema", List.of(Map.of(
									"AttributeName", DataStack.TOGGLZ_PRIMARY_KEY,
									"KeyType", "HASH")),
							"PointInTimeRecoverySpecification", Map.of(
									"PointInTimeRecoveryEnabled", e.getValue()))));
		}
	}

	/**
	 * PITR bật ở prod, tắt ở dev.
	 *
	 * Khẳng định ở ĐÂY vì CdkNagTest đã allowlist `AwsSolutions-DDB3` — rule đó
	 * không có tham số nên entry allowlist áp cho TOÀN BỘ DataStack, kể cả bảng
	 * thêm sau này. Nghĩa là cdk-nag không còn là chốt chặn cho PITR nữa, và
	 * test này là thứ duy nhất giữ cho prod không lặng lẽ mất point-in-time
	 * recovery — một mất mát chỉ lộ ra đúng lúc cần khôi phục dữ liệu.
	 */
	@Test
	void pitr_bat_o_prod_tat_o_dev() {
		dataStack(EnvConfig.PROD).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of("PointInTimeRecoverySpecification",
						Map.of("PointInTimeRecoveryEnabled", true))));
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of("PointInTimeRecoverySpecification",
						Map.of("PointInTimeRecoveryEnabled", false))));
	}
}
