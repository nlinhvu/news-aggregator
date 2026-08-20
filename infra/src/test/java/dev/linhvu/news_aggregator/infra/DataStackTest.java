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
	void the_gsi_projection_is_INCLUDE_not_ALL() {
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
	 * ĐÚNG HAI GSI, không hơn — `gsi-recent-v2` (AP1, AP9) và `gsi-by-source`
	 * (AP10, AP11).
	 *
	 * `arrayEquals` chứ không `arrayWith`: vế "có chứa v2" xanh cả khi index đời
	 * đầu `gsi-recent` còn nguyên, mà index cũ còn nguyên nghĩa là mỗi lượt ghi
	 * vẫn trả WCU cho một index không ai đọc. Đó là hồi quy thật của lần migrate
	 * v1 → v2, không phải giả định.
	 *
	 * Và nó phải soi gương `FlociTestConfiguration.articlesTableSchema` (master §9:
	 * schema chép tay, rủi ro đã chấp nhận). Fixture thiếu `gsi-by-source` thì test
	 * T2 vẫn xanh trong khi query trên AWS trỏ vào một index local không có — sai
	 * lệch theo chiều không ai phát hiện được.
	 *
	 * ⚠️ MỘT lần update stack chỉ thêm/xoá được MỘT GSI. Con số này lên 3 thì phải
	 * chia làm hai lượt deploy, đúng như migrate v1 → v2 đã phải làm.
	 */
	@Test
	void the_articles_table_has_exactly_two_gsis() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of(
						"GlobalSecondaryIndexes", Match.arrayEquals(List.of(
								Match.objectLike(Map.of("IndexName", "gsi-recent-v2")),
								Match.objectLike(Map.of("IndexName",
										DataStack.BY_SOURCE_INDEX_NAME))))
				)));
	}

	/**
	 * `gsi-by-source` dùng projection ALL, và đây là QUYẾT ĐỊNH chứ không phải
	 * mặc định bị bỏ quên.
	 *
	 * `gsi-recent-v2` dùng INCLUDE với một danh sách BẤT BIẾN sau lần deploy đầu —
	 * sửa rồi deploy là `UPDATE_FAILED`, và cách chữa duy nhất là một index tên mới
	 * nữa. Chương trình đã trả giá đó một lần (v1 → v2). ALL xoá hẳn cái bẫy, và
	 * giá tính được: item ~3 KB, ~200 bài/ngày ⇒ ~220 MB/năm nhân đôi, nằm gọn
	 * trong 25 GB free tier.
	 *
	 * `Map.of` chứ không `Match.objectLike` ở vế `Projection`: ALL đi kèm
	 * `NonKeyAttributes` là cấu hình mà DynamoDB TỪ CHỐI, nên vế khớp-chính-xác ở
	 * đây bắt được lỗi copy dòng projection của index bên trên.
	 *
	 * KeySchema cũng ghim ở đây: PK `sourceId` + SK `publishedAt`. Thiếu sort key
	 * thì "bài mới nhất của một nguồn" mất chỗ dựa và mọi query fan-out phải quét
	 * cả partition rồi tự sắp xếp.
	 */
	@Test
	void gsi_by_source_uses_projection_ALL() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of(
						"GlobalSecondaryIndexes", Match.arrayWith(List.of(
								Match.objectLike(Map.of(
										"IndexName", DataStack.BY_SOURCE_INDEX_NAME,
										"KeySchema", List.of(
												Map.of("AttributeName", "sourceId",
														"KeyType", "HASH"),
												Map.of("AttributeName", "publishedAt",
														"KeyType", "RANGE")),
										"Projection", Map.of("ProjectionType", "ALL"))))
				))));
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
	void gsi_v2_projects_the_excerpt_too() {
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
	void billing_is_on_demand() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of("BillingMode", "PAY_PER_REQUEST")));
	}

	/**
	 * Prod phải RETAIN + termination protection; dev thì không.
	 * Đây là lý do DataStack được tách ra khỏi các stack khác.
	 */
	@Test
	void prod_protects_the_data_and_dev_does_not() {
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
	void the_toggles_table_partition_key_follows_the_togglz_contract() {
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
	void pitr_on_the_toggles_table_is_on_in_prod_and_off_in_dev() {
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
	void pitr_is_on_in_prod_and_off_in_dev() {
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
	void the_sources_table_has_no_gsi() {
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
	void prod_retains_the_sources_table_when_the_stack_is_deleted() {
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
	void pitr_on_the_sources_table_is_on_in_prod_and_off_in_dev() {
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
	void the_sessions_table_has_ttl_on_the_expiresAt_attribute() {
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
	void sessions_never_enables_pitr_not_even_in_prod() {
		for (EnvConfig cfg : List.of(EnvConfig.PROD, EnvConfig.DEV)) {
			dataStack(cfg).hasResourceProperties("AWS::DynamoDB::Table",
					Match.objectLike(Map.of(
							"KeySchema", List.of(Map.of(
									"AttributeName", "sessionId",
									"KeyType", "HASH")),
							"PointInTimeRecoverySpecification", Match.absent())));
		}
	}

	/**
	 * Bảng `user-preferences`: PK `userId` (= Cognito `sub`), KHÔNG GSI, KHÔNG TTL.
	 *
	 * Ba vế trong một test vì chúng cùng mô tả MỘT quyết định — bảng này chỉ trả
	 * lời AP13 ("lựa chọn nguồn của một người"), nên mọi thứ ngoài `GetItem` theo
	 * `userId` là thừa:
	 * - GSI: không có access pattern thứ hai nào. Thêm một cái là trả WCU cho một
	 *   index không ai đọc.
	 * - TTL: lựa chọn của người dùng KHÔNG tự hết hạn. Đây là khác biệt với
	 *   `sessions` ngay bên trên, và chép nhầm dòng `timeToLiveAttribute` sang đây
	 *   sẽ làm lựa chọn của người dùng lặng lẽ biến mất — không lỗi, không log,
	 *   chỉ là một ngày nào đó feed quay về "tất cả nguồn".
	 *
	 * Ghim đích danh bằng `KeySchema` vì `hasResourceProperties` xanh khi CÓ MỘT
	 * resource khớp: một vế `TimeToLiveSpecification: absent` trần được ba bảng
	 * không-TTL kia làm cho xanh sẵn.
	 */
	@Test
	void the_user_preferences_table_is_keyed_by_userId_with_no_gsi_and_no_ttl() {
		dataStack(EnvConfig.DEV).hasResourceProperties("AWS::DynamoDB::Table",
				Match.objectLike(Map.of(
						"KeySchema", List.of(Map.of(
								"AttributeName", "userId",
								"KeyType", "HASH")),
						"BillingMode", "PAY_PER_REQUEST",
						"GlobalSecondaryIndexes", Match.absent(),
						"TimeToLiveSpecification", Match.absent())));
	}

	/**
	 * PITR của `user-preferences` soi gương `articles`: bật ở prod, tắt ở dev.
	 *
	 * Ngược hẳn `sessions` — thứ CỐ Ý không có PITR ở bất kỳ đâu. Hai bảng cùng
	 * sinh ra ở Phase 7 và cùng khoá theo một người dùng, nên khác biệt này phải
	 * có chốt chặn riêng: `sessions` chứa trạng thái phù du (khôi phục nó là hồi
	 * sinh phiên đã đăng xuất), còn đây là thứ NGƯỜI DÙNG TỰ TAY nhập và không có
	 * cách nào dựng lại nếu mất.
	 */
	@Test
	void pitr_on_the_user_preferences_table_is_on_in_prod_and_off_in_dev() {
		for (Map.Entry<EnvConfig, Boolean> e
				: Map.of(EnvConfig.PROD, true, EnvConfig.DEV, false).entrySet()) {
			dataStack(e.getKey()).hasResourceProperties("AWS::DynamoDB::Table",
					Match.objectLike(Map.of(
							"KeySchema", List.of(Map.of(
									"AttributeName", "userId",
									"KeyType", "HASH")),
							"PointInTimeRecoverySpecification", Map.of(
									"PointInTimeRecoveryEnabled", e.getValue()))));
		}
	}

	/**
	 * `user-preferences` RETAIN ở prod, DELETE ở dev — ghim đích danh bằng
	 * `KeySchema` lồng trong `Properties`, đúng lý do đã ghi ở
	 * `prod_retains_the_sources_table_when_the_stack_is_deleted`.
	 */
	@Test
	void prod_retains_the_user_preferences_table_when_the_stack_is_deleted() {
		for (Map.Entry<EnvConfig, String> e
				: Map.of(EnvConfig.PROD, "Retain", EnvConfig.DEV, "Delete").entrySet()) {
			dataStack(e.getKey()).hasResource("AWS::DynamoDB::Table",
					Match.objectLike(Map.of(
							"DeletionPolicy", e.getValue(),
							"Properties", Match.objectLike(Map.of(
									"KeySchema", List.of(Map.of(
											"AttributeName", "userId",
											"KeyType", "HASH")))))));
		}
	}
}
