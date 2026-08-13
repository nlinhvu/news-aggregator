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
										"IndexName", "gsi-recent-v2",
										"Projection", Match.objectLike(Map.of(
												"ProjectionType", "INCLUDE"))))
						))
				)));
	}

	/**
	 * ĐÚNG MỘT GSI — vế cuối của migrate `gsi-recent` → `gsi-recent-v2`.
	 *
	 * `arrayEquals` chứ không `arrayWith`: vế "có chứa v2" xanh cả khi index cũ
	 * còn nguyên, mà index cũ còn nguyên nghĩa là mỗi lượt ghi vẫn trả WCU cho
	 * một index không ai đọc. Con số 2 ở đây là hồi quy thật, không phải giả định.
	 *
	 * Và nó phải soi gương `FlociTestConfiguration.articlesTableSchema` (master §9:
	 * schema chép tay, rủi ro đã chấp nhận). Để sót v1 ở fixture test thì test vẫn
	 * xanh trong khi prod đã xoá nó — sai lệch theo chiều không ai phát hiện được.
	 */
	@Test
	void bang_articles_chi_con_mot_gsi() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of(
						"GlobalSecondaryIndexes", Match.arrayEquals(List.of(
								Match.objectLike(Map.of("IndexName", "gsi-recent-v2"))))
				)));
	}

	/**
	 * `gsi-recent-v2` là index DUY NHẤT có `excerpt`, và Task 13 dựa hoàn toàn
	 * vào điều đó: `excerpt` không nằm trong projection thì query trả về
	 * `getExcerpt() == null` cho MỌI item — đã đo — và sweep im lặng không nhặt
	 * bài nào.
	 *
	 * Ghim ĐÚNG danh sách vì cùng lý do với index cũ: projection là bất biến sau
	 * lần deploy đầu, nên sửa dòng này rồi deploy là làm gãy môi trường.
	 */
	@Test
	void gsi_v2_project_ca_excerpt() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of(
						"GlobalSecondaryIndexes", Match.arrayWith(List.of(
								Match.objectLike(Map.of(
										"IndexName", "gsi-recent-v2",
										"Projection", Match.objectLike(Map.of(
												"NonKeyAttributes", List.of("title",
														"canonicalUrl", "sourceName",
														"summary", "excerpt")))))
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

	/**
	 * Bảng `sources` KHÔNG có GSI (TDD §17 #9). Bảng bị chặn trên ở ~30 dòng
	 * bởi master §2, nên đọc bằng Scan tốn ~1 RCU; một GSI có partition key là
	 * boolean sẽ tạo hot partition thật để đổi lấy con số không.
	 *
	 * `GlobalSecondaryIndexes` phải là `Match.absent()`, không chỉ là "tồn tại
	 * một bảng khoá `sourceId`": khẳng định KeySchema + BillingMode vẫn xanh
	 * nguyên vẹn sau khi ai đó thêm GSI vào đúng bảng này, tức là một test mang
	 * tên "không có GSI" mà không kiểm gì về GSI cả.
	 */
	@Test
	void bang_sources_khong_co_gsi() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of(
						"KeySchema", List.of(Map.of(
								"AttributeName", "sourceId",
								"KeyType", "HASH")),
						"BillingMode", "PAY_PER_REQUEST",
						"GlobalSecondaryIndexes", Match.absent())));
	}

	/**
	 * Bảng `sources` RETAIN ở prod, DELETE ở dev.
	 *
	 * Ghim đích danh bằng `KeySchema` lồng trong `Properties`, đúng lý do đã ghi
	 * ở `pitr_cua_bang_toggles_...`: `hasResource` xanh khi CÓ MỘT resource
	 * khớp, nên một khẳng định chỉ nêu `DeletionPolicy` đã được `articlesTable`
	 * làm cho xanh sẵn — bảng thứ ba cấu hình thế nào cũng không đổi kết quả.
	 */
	@Test
	void prod_giu_lai_bang_sources_khi_xoa_stack() {
		for (Map.Entry<EnvConfig, String> e
				: Map.of(EnvConfig.PROD, "Retain", EnvConfig.DEV, "Delete").entrySet()) {
			dataStack(e.getKey()).hasResource("AWS::DynamoDB::Table",
					Match.objectLike(Map.of(
							"DeletionPolicy", e.getValue(),
							"Properties", Match.objectLike(Map.of(
									"KeySchema", List.of(Map.of(
											"AttributeName", "sourceId",
											"KeyType", "HASH")))))));
		}
	}

	/**
	 * PITR của bảng `sources` cũng bật ở prod, tắt ở dev — khẳng định RIÊNG,
	 * cùng lý do ghim đích danh như bảng toggles.
	 *
	 * Bảng này giữ TRẠNG THÁI VẬN HÀNH chứ không phải nội dung đọc được: mất
	 * `etag` thì mọi nguồn tải full một lượt, mất `enabled` thì một nguồn đã tắt
	 * bỗng chạy lại.
	 */
	@Test
	void pitr_cua_bang_sources_bat_o_prod_tat_o_dev() {
		for (Map.Entry<EnvConfig, Boolean> e
				: Map.of(EnvConfig.PROD, true, EnvConfig.DEV, false).entrySet()) {
			dataStack(e.getKey()).hasResourceProperties("AWS::DynamoDB::Table",
					Match.objectLike(Map.of(
							"KeySchema", List.of(Map.of(
									"AttributeName", "sourceId",
									"KeyType", "HASH")),
							"PointInTimeRecoverySpecification", Map.of(
									"PointInTimeRecoveryEnabled", e.getValue()))));
		}
	}

	/**
	 * TTL là thứ giữ bảng `sessions` không lớn vô hạn, và nó KHÔNG có triệu chứng
	 * khi cấu hình sai: phiên vẫn hoạt động, chỉ là không bao giờ hết hạn và bảng
	 * phình ra âm thầm. Tên attribute phải khớp hằng số bên app (Task 9).
	 *
	 * Ghim đích danh bằng `KeySchema`, đúng lý do đã ghi ở
	 * `pitr_cua_bang_toggles_...`: `hasResourceProperties` xanh khi CÓ MỘT
	 * resource khớp, nên một khẳng định chỉ nêu `TimeToLiveSpecification` sẽ
	 * không nói được nó thuộc bảng nào.
	 */
	@Test
	void bang_sessions_co_ttl_tren_dung_attribute_expiresAt() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of(
						"KeySchema", List.of(Map.of(
								"AttributeName", "sessionId",
								"KeyType", "HASH")),
						"TimeToLiveSpecification", Map.of(
								"AttributeName", "expiresAt",
								"Enabled", true))));
	}

	/**
	 * `sessions` là bảng DUY NHẤT không có PITR, kể cả ở prod — ngược hẳn ba bảng
	 * trên, và đó là quyết định CÓ CHỦ Ý chứ không phải bỏ sót.
	 *
	 * Bảng này giữ trạng thái PHÙ DU có TTL: khôi phục nó về một thời điểm trong
	 * quá khứ là hồi sinh những phiên đã đăng xuất — một tính năng CHỐNG bảo mật.
	 *
	 * Chốt chặn phải nằm ở đây vì không tầng nào khác canh: cdk-nag đã allowlist
	 * `AwsSolutions-DDB3` cho toàn bộ DataStack (rule KHÔNG tham số), và bật PITR
	 * lên thì mọi thứ vẫn chạy y hệt — chỉ khác ở hoá đơn và ở một cánh cửa khôi
	 * phục lẽ ra không nên tồn tại. Kiểm CẢ prod: bản dev-only sẽ xanh nguyên vẹn
	 * khi ai đó chép dòng `pointInTimeRecoverySpecification(...)` của ba bảng kia
	 * sang, vì công thức chung là `cfg.terminationProtection()`.
	 */
	@Test
	void sessions_khong_bao_gio_bat_pitr_ke_ca_o_prod() {
		for (EnvConfig cfg : List.of(EnvConfig.PROD, EnvConfig.DEV)) {
			dataStack(cfg).hasResourceProperties("AWS::DynamoDB::Table",
					Match.objectLike(Map.of(
							"KeySchema", List.of(Map.of(
									"AttributeName", "sessionId",
									"KeyType", "HASH")),
							"PointInTimeRecoverySpecification", Match.absent())));
		}
	}
}
