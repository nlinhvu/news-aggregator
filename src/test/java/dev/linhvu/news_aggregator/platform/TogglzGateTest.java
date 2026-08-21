package dev.linhvu.news_aggregator.platform;

import dev.linhvu.news_aggregator.ArticleFixtures;
import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.catalog.Article;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.togglz.core.context.FeatureContext;
import org.togglz.core.manager.FeatureManager;
import org.togglz.junit5.AllEnabled;
import org.togglz.testing.TestFeatureManagerProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.List;

import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FlociTestConfiguration.class)
// Flag được kiểm QUA `GET /api/articles`, tức qua `ArticleController` —
// `@Profile(WEB)`.
@ActiveProfiles(RoleProfiles.WEB)
class TogglzGateTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	DynamoDbEnhancedClient enhancedClient;

	/**
	 * FeatureManager THẬT do `TogglzAutoConfiguration` dựng — cái đọc DynamoDB qua
	 * {@code FailClosedDynamoDbStateRepository}. Phải giữ tham chiếu ở đây vì
	 * trong test nó KHÔNG tự đến tay `FeatureContext`; xem
	 * {@link #useRealFeatureManager()}.
	 */
	@Autowired
	FeatureManager featureManager;

	@Autowired
	ArticleCatalog catalog;

	@Value("${news.catalog.table-name}")
	String tableName;

	/**
	 * Không có bước này thì cả hai test đều vô nghĩa: bảng do
	 * `FlociTestConfiguration` tạo là bảng RỖNG, nên `$[0]` không tồn tại —
	 * `exists()` đỏ vì thiếu article chứ không phải vì thiếu `summary`, còn
	 * `doesNotExist()` thì xanh trong mọi hoàn cảnh, kể cả khi controller hỏng.
	 *
	 * Ghi thẳng qua `DynamoDbEnhancedClient` chứ không qua `ArticleRepository`:
	 * repository là package-private của `catalog`, không nhìn thấy từ `platform`.
	 * Dùng lại `ArticleFixtures` để dữ liệu test và dữ liệu seed không trôi khỏi
	 * nhau — mọi article trong fixture đều có `summary` khác null, đúng tiền đề
	 * mà hai test dưới cần.
	 */
	@BeforeEach
	void loadFixtures() {
		DynamoDbTable<Article> table =
				enhancedClient.table(tableName, TableSchema.fromBean(Article.class));
		ArticleFixtures.load().forEach(table::putItem);
	}

	/**
	 * `FeatureContext` cache FeatureManager theo context class loader trong một
	 * field static, và Gradle chạy mọi test class trong cùng một JVM. Không dọn
	 * thì manager của test này rò sang test class khác — kèm cả Spring context đã
	 * đóng. `@AllEnabled` cũng tự dọn y hệt ở `afterEach` của nó.
	 */
	@AfterEach
	void restoreFeatureManager() {
		TestFeatureManagerProvider.setFeatureManager(null);
		FeatureContext.clearCache();
	}

	@Test
	@AllEnabled(NewsFeature.class)
	void flag_on_yields_a_summary() throws Exception {
		mockMvc.perform(get("/api/articles?limit=1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].summary").exists());
	}

	/**
	 * Mặc định của enum là OFF nên không cần TẮT gì thêm — nhưng phải chỉ đích
	 * danh FeatureManager thật, xem {@link #useRealFeatureManager()}.
	 */
	@Test
	void flag_off_makes_summary_ABSENT_rather_than_null() throws Exception {
		useRealFeatureManager();

		mockMvc.perform(get("/api/articles?limit=1"))
				.andExpect(status().isOk())
				// Chốt chống test rỗng: thiếu dòng này thì một bảng rỗng cũng làm
				// `$.items[0].summary` "không tồn tại" và test xanh mà chẳng kiểm gì.
				.andExpect(jsonPath("$.items[0].id").exists())
				.andExpect(jsonPath("$.items[0].summary").doesNotExist());
	}

	/**
	 * Đường đọc THỨ HAI — `ArticleCatalog.recentBySources`, thứ `/api/my/feed`
	 * dùng từ Task 23 — phải phản ứng với flag y hệt `/api/articles`.
	 *
	 * Đây là lý do phép ánh xạ Article → DTO nằm ở `ArticleSummaries` chứ không
	 * bị chép hai bản: hai bản sao sẽ trôi khỏi nhau đúng vào ngày người vận
	 * hành tắt `AI_SUMMARIZATION` — trang công khai đổi hành vi, feed của người
	 * đã đăng nhập thì không, và flag mất luôn ý nghĩa nó tự nhận.
	 *
	 * Gọi thẳng API của module chứ không qua HTTP: controller của
	 * `personalization` chưa tồn tại ở Task 20, còn hợp đồng thì đã có.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void flag_on_gives_the_source_filtered_feed_a_summary_too() {
		assertThat(catalog.recentBySources(List.of("spring-blog", "aws-news"), 10))
				.isNotEmpty()
				.allSatisfy(dto -> assertThat(dto.summary()).isNotNull());
	}

	@Test
	void flag_off_leaves_the_source_filtered_feed_WITHOUT_a_summary_too() {
		useRealFeatureManager();

		assertThat(catalog.recentBySources(List.of("spring-blog", "aws-news"), 10))
				// Chốt chống test rỗng: danh sách rỗng cũng làm `allSatisfy` xanh.
				.isNotEmpty()
				.allSatisfy(dto -> assertThat(dto.summary()).isNull());
	}

	/**
	 * Ép `FeatureContext` trả về FeatureManager của Spring.
	 *
	 * Vì sao cần: `togglz-testing` — kéo vào theo `togglz-junit`, không thể tách
	 * vì `@AllEnabled` nằm cùng jar — đăng ký SPI
	 * `FallbackTestFeatureManagerProvider` với priority **20**, trong khi provider
	 * của Spring (`BeanFinderFeatureManagerProvider`) priority **60**. Togglz lấy
	 * provider trả non-null ĐẦU TIÊN theo thứ tự priority tăng dần, mà fallback
	 * thì luôn trả non-null. Hệ quả đọc được thẳng trong bytecode:
	 * `FallbackTestFeatureManager.isActive` là `iconst_1; ireturn` — **TRUE cho
	 * mọi feature**. Nói cách khác, trong test mà không làm gì thì flag không phải
	 * "mặc định OFF" mà là "BẬT HẾT", và test này đỏ vì thư viện test chứ không
	 * phải vì code sai.
	 *
	 * Gán qua `TestFeatureManagerProvider` (priority 10) là đường chặn duy nhất
	 * đứng trước fallback. Nó là API public của `togglz-testing` sinh ra để test
	 * lắp manager, chỉ khác ở chỗ manager lắp vào đây là hàng THẬT — nên test này
	 * vẫn đi qua `FailClosedDynamoDbStateRepository` và bảng `feature-toggles`
	 * rỗng trong Floci, đúng điều cần chứng minh: không có item ⇒ Togglz rơi về
	 * mặc định của enum ⇒ OFF.
	 *
	 * Gọi trong THÂN test chứ không phải `@BeforeEach`: callback của `@AllEnabled`
	 * chạy TRƯỚC `@BeforeEach`, nên đặt ở đó sẽ đè mất TestFeatureManager và giết
	 * luôn test kia.
	 */
	private void useRealFeatureManager() {
		TestFeatureManagerProvider.setFeatureManager(featureManager);
		FeatureContext.clearCache();
	}
}
