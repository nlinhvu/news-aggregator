package dev.linhvu.news_aggregator.personalization;

import java.util.List;

import dev.linhvu.news_aggregator.ArticleFixtures;
import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.CatalogUnavailableException;
import dev.linhvu.news_aggregator.platform.NewsFeature;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.togglz.core.context.FeatureContext;
import org.togglz.junit5.AllEnabled;
import org.togglz.testing.TestFeatureManagerProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fixture có 5 bài: `spring-blog` ×2, `aws-news` ×2, và MỘT bài không có
 * `sourceId` (bài trước backfill). Bài thứ năm là thứ làm hai test dưới đây
 * khác nhau thật sự.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(RoleProfiles.WEB)
@Import(FlociTestConfiguration.class)
class MyFeedControllerTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	SourcePreferenceRepository preferences;

	@Autowired
	DynamoDbEnhancedClient enhancedClient;

	@Value("${news.catalog.table-name}")
	String articlesTable;

	@BeforeEach
	void napBai() {
		DynamoDbTable<dev.linhvu.news_aggregator.catalog.Article> table =
				enhancedClient.table(articlesTable, TableSchema.fromBean(
						dev.linhvu.news_aggregator.catalog.Article.class));
		table.scan().items().stream().toList().forEach(table::deleteItem);
		ArticleFixtures.load().forEach(table::putItem);
	}

	@AfterEach
	void traLaiFeatureManager() {
		TestFeatureManagerProvider.setFeatureManager(null);
		FeatureContext.clearCache();
	}

	@Test
	@AllEnabled(NewsFeature.class)
	void an_danh_thi_401() throws Exception {
		mvc.perform(get("/api/my/feed")).andExpect(status().isUnauthorized());
	}

	/**
	 * Chưa chọn gì ⇒ TẤT CẢ nguồn, và "tất cả" ở đây gồm CẢ bài chưa có
	 * `sourceId`: nhánh rỗng đi qua `gsi-recent-v2` chứ không fan-out.
	 *
	 * Một bản dựng "rỗng ⇒ fan-out mọi nguồn đang bật" trả về 4 thay vì 5 và
	 * không có lỗi nào — người dùng mới sẽ thấy feed thiếu bài so với trang
	 * công khai mà không ai giải thích được.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void chua_chon_gi_thi_thay_tat_ca_ke_ca_bai_chua_backfill() throws Exception {
		mvc.perform(get("/api/my/feed").with(oidcLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(5));
	}

	/**
	 * Lọc là LỌC THẬT ở tầng dữ liệu, không phải ở trình duyệt — đây là mục
	 * phân biệt tính năng này với một trò trang trí (walkthrough slice 4).
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void chon_mot_nguon_thi_chi_con_nguon_do() throws Exception {
		preferences.save("nguoi-loc", List.of("aws-news"));

		mvc.perform(get("/api/my/feed")
						.with(oidcLogin().idToken(t -> t.subject("nguoi-loc"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[*].sourceName")
						.value(org.hamcrest.Matchers.everyItem(
								org.hamcrest.Matchers.is("AWS News Blog"))))
				// Mới nhất trước, y hệt `/api/articles`.
				.andExpect(jsonPath("$[0].publishedAt").value("2026-07-26T11:45:00Z"));
	}

	/**
	 * `limit` dùng CHUNG cặp giới hạn với `/api/articles` (`news.catalog.*`).
	 * Hai feed cùng hình dạng mà khác trần là thứ chỉ lộ ra khi ai đó so hai
	 * response và thấy số bài lệch nhau.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void ton_trong_limit() throws Exception {
		mvc.perform(get("/api/my/feed?limit=2").with(oidcLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
	}

	/**
	 * `503`, KHÔNG `500` và tuyệt đối không phải `200` kèm danh sách thiếu.
	 *
	 * Đây là vế cuối của quyết định "không trả kết quả một phần" bên `catalog`:
	 * nó ném, và chỗ này phải dịch cú ném đó thành một mã mà SPA hiểu là TẠM
	 * THỜI. Mock `ArticleCatalog` vì không có cách nào ép một query DynamoDB
	 * thật hỏng từ phía HTTP.
	 */
	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles(RoleProfiles.WEB)
	@Import(FlociTestConfiguration.class)
	class KhiCatalogHong {

		@Autowired
		MockMvc mvc;

		@MockitoBean
		ArticleCatalog catalog;

		@Test
		@AllEnabled(NewsFeature.class)
		void fan_out_hong_thi_503_chu_khong_tra_ket_qua_mot_phan() throws Exception {
			given(catalog.recentBySources(any(), anyInt()))
					.willThrow(new CatalogUnavailableException("hỏng",
							new IllegalStateException("một query theo nguồn hỏng")));

			mvc.perform(get("/api/my/feed").with(oidcLogin()))
					.andExpect(status().isServiceUnavailable());
		}
	}
}
