package dev.linhvu.news_aggregator.personalization;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import dev.linhvu.news_aggregator.sources.SourceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.togglz.core.context.FeatureContext;
import org.togglz.core.manager.FeatureManager;
import org.togglz.junit5.AllEnabled;
import org.togglz.testing.TestFeatureManagerProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.util.List;
import java.util.Map;

import dev.linhvu.news_aggregator.platform.NewsFeature;
import org.junit.jupiter.api.AfterEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `@AllEnabled` trên mọi test vì `UserAccountsGate` nay phủ `/api/preferences/**`:
 * flag OFF là mặc định của enum, và khi đó endpoint trả 404 — mọi assertion
 * dưới đây sẽ đo một tính năng đang tắt. Vế "tắt thì 404" có nhà riêng ở
 * `UserAccountsToggleTest`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(RoleProfiles.WEB)
@Import(FlociTestConfiguration.class)
class PreferencesControllerTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	SourceCatalog sources;

	@Autowired
	DynamoDbClient dynamo;

	@Value("${news.sources.table-name}")
	String sourcesTable;

	@Value("${news.personalization.preferences-table}")
	String preferencesTable;

	@BeforeEach
	void napNguon() {
		donBang(sourcesTable, "sourceId");
		donBang(preferencesTable, "userId");
		putSource("spring-blog", "Spring Blog", true);
		putSource("aws-news", "AWS News Blog", true);
		putSource("da-tat", "Nguồn Đã Tắt", false);
	}

	@AfterEach
	void traLaiFeatureManager() {
		TestFeatureManagerProvider.setFeatureManager(null);
		FeatureContext.clearCache();
	}

	@Test
	@AllEnabled(NewsFeature.class)
	void an_danh_thi_401_chu_khong_200_rong() throws Exception {
		mvc.perform(get("/api/preferences/sources")).andExpect(status().isUnauthorized());
	}

	/**
	 * Người mới đăng nhập chưa chọn gì ⇒ `[]`, và `[]` nghĩa là TẤT CẢ nguồn.
	 * Trả `404` hay `null` ở đây buộc SPA phải đoán.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void chua_chon_gi_thi_tra_danh_sach_rong() throws Exception {
		mvc.perform(get("/api/preferences/sources").with(oidcLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceIds").isArray())
				.andExpect(jsonPath("$.sourceIds.length()").value(0));
	}

	@Test
	@AllEnabled(NewsFeature.class)
	void ghi_roi_doc_lai_dung_thu_da_chon() throws Exception {
		mvc.perform(put("/api/preferences/sources")
						.with(oidcLogin()).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceIds\":[\"spring-blog\",\"aws-news\"]}"))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/preferences/sources").with(oidcLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceIds.length()").value(2));
	}

	/**
	 * `sourceId` không tồn tại ⇒ `400`, và **không được ghi gì**.
	 *
	 * Bỏ vế này thì một id rác nằm im trong bảng và sinh một query fan-out vô
	 * ích mãi mãi: mỗi lần người đó mở feed, một query trả rỗng — không lỗi,
	 * không log, chỉ chậm và tốn thêm một chút mãi mãi.
	 *
	 * Vế "không ghi gì" mới là vế đắt: một bản dựng validate SAU khi ghi vẫn trả
	 * đúng 400 và vẫn hỏng đúng như mô tả trên.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void sourceId_khong_ton_tai_thi_400_va_khong_ghi_gi() throws Exception {
		mvc.perform(put("/api/preferences/sources")
						.with(oidcLogin()).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceIds\":[\"spring-blog\",\"nguon-bia\"]}"))
				.andExpect(status().isBadRequest());

		assertThat(demItem(preferencesTable))
				.as("request hỏng không được để lại dấu vết nào trong bảng")
				.isZero();
	}

	/**
	 * Nguồn ĐÃ TẮT cũng là `400`: nó không nằm trong `GET /api/sources` nên
	 * người dùng không bấm được nó — request tới đây là request dựng tay, và
	 * chấp nhận nó là tự tạo ra một lựa chọn vĩnh viễn không có bài.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void nguon_da_tat_cung_bi_tu_choi() throws Exception {
		mvc.perform(put("/api/preferences/sources")
						.with(oidcLogin()).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceIds\":[\"da-tat\"]}"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * Thiếu CSRF token ⇒ `403`. Hệ quả bắt buộc của việc xác thực bằng cookie:
	 * không có vế này thì một trang bất kỳ trên Internet đổi được lựa chọn nguồn
	 * của người đang đăng nhập.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void thieu_csrf_thi_403() throws Exception {
		mvc.perform(put("/api/preferences/sources")
						.with(oidcLogin())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceIds\":[\"spring-blog\"]}"))
				.andExpect(status().isForbidden());
	}

	/**
	 * Chốt chặn IDOR: `userId` đến từ PHIÊN, không từ body.
	 *
	 * Người A ghi lựa chọn của mình; người B đọc ra RỖNG chứ không thấy của A.
	 * Một bản dựng nhận `userId` từ request sẽ xanh ở mọi test trên và đỏ ở đây.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void lua_chon_cua_nguoi_nay_khong_lan_sang_nguoi_khac() throws Exception {
		mvc.perform(put("/api/preferences/sources")
						.with(oidcLogin().idToken(t -> t.subject("nguoi-a")))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceIds\":[\"spring-blog\"]}"))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/preferences/sources")
						.with(oidcLogin().idToken(t -> t.subject("nguoi-b"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceIds.length()").value(0));
	}

	private void putSource(String id, String name, boolean enabled) {
		dynamo.putItem(PutItemRequest.builder().tableName(sourcesTable)
				.item(Map.of("sourceId", AttributeValue.fromS(id),
						"name", AttributeValue.fromS(name),
						"feedUrl", AttributeValue.fromS("https://x.test/" + id),
						"enabled", AttributeValue.fromBool(enabled)))
				.build());
	}

	private void donBang(String table, String key) {
		List<Map<String, AttributeValue>> items = dynamo.scanPaginator(
				ScanRequest.builder().tableName(table).build()).items().stream().toList();
		items.forEach(item -> dynamo.deleteItem(b -> b.tableName(table)
				.key(Map.of(key, item.get(key)))));
	}

	private int demItem(String table) {
		return dynamo.scan(ScanRequest.builder().tableName(table)
				.select(software.amazon.awssdk.services.dynamodb.model.Select.COUNT)
				.build()).count();
	}
}
