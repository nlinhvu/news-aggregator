package dev.linhvu.news_aggregator.personalization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.jayway.jsonpath.JsonPath;
import dev.linhvu.news_aggregator.ArticleFixtures;
import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.catalog.Article;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
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
	void loadArticles() {
		DynamoDbTable<Article> table = enhancedClient.table(
				articlesTable, TableSchema.fromBean(Article.class));
		table.scan().items().stream().toList().forEach(table::deleteItem);
		ArticleFixtures.load().forEach(table::putItem);
	}

	@AfterEach
	void restoreFeatureManager() {
		TestFeatureManagerProvider.setFeatureManager(null);
		FeatureContext.clearCache();
	}

	@Test
	@AllEnabled(NewsFeature.class)
	void anonymous_gets_401() throws Exception {
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
	void with_nothing_selected_you_see_everything_including_unbackfilled_articles() throws Exception {
		mvc.perform(get("/api/my/feed").with(oidcLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(5));
	}

	/**
	 * Lọc là LỌC THẬT ở tầng dữ liệu, không phải ở trình duyệt — đây là mục
	 * phân biệt tính năng này với một trò trang trí (walkthrough slice 4).
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void selecting_one_source_leaves_only_that_source() throws Exception {
		preferences.save("filterer", List.of("aws-news"));

		mvc.perform(get("/api/my/feed")
						.with(oidcLogin().idToken(t -> t.subject("filterer"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[*].sourceName")
						.value(org.hamcrest.Matchers.everyItem(
								org.hamcrest.Matchers.is("AWS News Blog"))))
				// Mới nhất trước, y hệt `/api/articles`.
				.andExpect(jsonPath("$.items[0].publishedAt").value("2026-07-26T11:45:00Z"));
	}

	/**
	 * `limit` dùng CHUNG cặp giới hạn với `/api/articles` (`news.catalog.*`).
	 * Hai feed cùng hình dạng mà khác trần là thứ chỉ lộ ra khi ai đó so hai
	 * response và thấy số bài lệch nhau.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void respects_limit() throws Exception {
		mvc.perform(get("/api/my/feed?limit=2").with(oidcLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2));
	}

	/**
	 * VẾ QUYẾT ĐỊNH của slice 2: `cursor` phải đi HẾT đường — từ query param, qua
	 * `ArticleCatalog`, xuống repository — rồi quay ra thành trang TIẾP THEO.
	 *
	 * Mọi assertion khác trong class này chỉ nhìn TRANG ĐẦU, mà trang đầu thì
	 * đúng ở cả bản dựng có truyền cursor lẫn bản quên truyền. Thiếu hai test
	 * cuộn này, một bản dựng bỏ quên `cursor` ở chỗ gọi `catalog.recentBySources`
	 * đi qua trọn vẹn cả suite trong khi người đọc cuộn mãi trên cùng hai bài —
	 * đúng chế độ hỏng mà `ArticlePagingEndpointTest` phải dựng riêng một class
	 * để canh ở đường công khai.
	 *
	 * Nhánh KHÔNG lọc: tập nguồn rỗng ⇒ `findRecent` ⇒ `ExclusiveStartKey`.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void scrolling_the_unfiltered_feed_walks_the_whole_catalog() throws Exception {
		List<String> expected = idsNewestFirst(a -> true);
		assertThat(expected).as("fixture phải đủ dài cho nhiều hơn một trang")
				.hasSizeGreaterThan(2);

		assertThat(scrollAll(oidcLogin(), 2)).containsExactlyElementsOf(expected);
	}

	/**
	 * Nhánh CÓ lọc: fan-out qua `gsi-by-source`, watermark `<=` rồi lọc lại. Đây
	 * là đường mà `ExclusiveStartKey` không cứu được, và là chỗ DUY NHẤT nó bị
	 * chạy qua HTTP thật.
	 *
	 * `limit = 1` để một kho 4 bài vẫn thành bốn trang: càng nhiều vòng thì một
	 * cursor không tiến càng khó lọt.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void scrolling_the_filtered_feed_walks_every_article_of_the_selected_sources()
			throws Exception {
		preferences.save("scroller", List.of("spring-blog", "aws-news"));
		// Bài chưa backfill `sourceId` KHÔNG nằm trong feed đã lọc — `gsi-by-source`
		// là sparse index. Suy tập mong đợi từ chính tính chất đó.
		List<String> expected = idsNewestFirst(a -> a.getSourceId() != null);
		assertThat(expected)
				.as("fixture phải đủ dài cho nhiều hơn một trang")
				.hasSizeGreaterThan(1)
				.as("và phải THIẾU bài chưa backfill, nếu không thì test này "
						+ "trùng hệt test không lọc")
				.hasSizeLessThan(idsNewestFirst(a -> true).size());

		assertThat(scrollAll(oidcLogin().idToken(t -> t.subject("scroller")), 1))
				.containsExactlyElementsOf(expected);
	}

	/**
	 * `400` chứ không `503`.
	 *
	 * Đây là cái bẫy thật của Task 7: `ArticleCatalogService` bọc mọi
	 * `RuntimeException` thành `CatalogUnavailableException` để `personalization`
	 * phân biệt được "không đọc được bài" với lỗi lập trình. Giải mã cursor BÊN
	 * TRONG khối `try` đó sẽ biến một cursor hỏng thành 503 — mời người đọc thử
	 * lại một request không bao giờ thành công.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void a_garbage_cursor_returns_400_not_503() throws Exception {
		mvc.perform(get("/api/my/feed?cursor=noseparator").with(oidcLogin()))
				.andExpect(status().isBadRequest());
	}

	/** `?cursor=` rỗng nghĩa là KHÔNG có cursor, không phải cursor hỏng. */
	@Test
	@AllEnabled(NewsFeature.class)
	void an_empty_cursor_still_returns_the_first_page() throws Exception {
		mvc.perform(get("/api/my/feed?cursor=").with(oidcLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isArray());
	}

	/**
	 * Cuộn hết bằng đúng cơ chế của SPA: đi theo `nextCursor` cho tới khi nó null.
	 *
	 * Chốt chặn 20 vòng biến một cursor không tiến thành test ĐỎ thay vì một lần
	 * build treo.
	 */
	private List<String> scrollAll(RequestPostProcessor user, int limit) throws Exception {
		List<String> collected = new ArrayList<>();
		String cursor = null;
		for (int round = 0; round < 20; round++) {
			String url = "/api/my/feed?limit=" + limit
					+ (cursor == null ? "" : "&cursor=" + cursor);
			String body = mvc.perform(get(url).with(user))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();

			collected.addAll(JsonPath.read(body, "$.items[*].id"));
			cursor = JsonPath.read(body, "$.nextCursor");
			if (cursor == null) {
				return collected;
			}
		}
		throw new IllegalStateException("cuộn quá 20 trang — cursor không tiến");
	}

	/** Thứ tự mong đợi suy TỪ FIXTURE, không viết cứng — đúng lối `ArticleRepositoryTest`. */
	private static List<String> idsNewestFirst(Predicate<Article> keep) {
		return ArticleFixtures.load().stream()
				.filter(keep)
				.sorted(Comparator.comparing(Article::getPublishedAt).reversed())
				.map(Article::getArticleId)
				.toList();
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
	class WhenCatalogFails {

		@Autowired
		MockMvc mvc;

		@MockitoBean
		ArticleCatalog catalog;

		@Test
		@AllEnabled(NewsFeature.class)
		void a_broken_fan_out_returns_503_instead_of_partial_results() throws Exception {
			given(catalog.recentBySources(any(), anyInt(), any()))
					.willThrow(new CatalogUnavailableException("hỏng",
							new IllegalStateException("một query theo nguồn hỏng")));

			mvc.perform(get("/api/my/feed").with(oidcLogin()))
					.andExpect(status().isServiceUnavailable());
		}
	}
}
