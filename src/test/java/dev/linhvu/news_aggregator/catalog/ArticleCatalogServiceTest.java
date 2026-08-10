package dev.linhvu.news_aggregator.catalog;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(FlociTestConfiguration.class)
class ArticleCatalogServiceTest {

	@Autowired
	ArticleCatalog catalog;

	@Autowired
	ArticleRepository repository;

	@Autowired
	DynamoDbEnhancedClient enhancedClient;

	@Value("${news.catalog.table-name}")
	String tableName;

	/**
	 * Dọn SAU mỗi test, không chỉ trước. Bảng là của dùng chung giữa các class,
	 * và `ArticleRepositoryTest` khẳng định `findRecent` trả về ĐÚNG các bài
	 * trong fixture của nó — năm bài ở đây rò sang sẽ làm class đó đỏ, tức đỏ ở
	 * chỗ không ai vừa động vào.
	 *
	 * Gom `toList()` trước rồi mới xoá: `scan()` của Enhanced Client là lazy +
	 * paginated, xoá ngay trong lúc duyệt là sửa đúng cái đang được duyệt.
	 */
	@AfterEach
	void donBang() {
		DynamoDbTable<Article> table =
				enhancedClient.table(tableName, TableSchema.fromBean(Article.class));
		table.scan().items().stream().toList().forEach(table::deleteItem);
	}

	/**
	 * PHẢI dài hơn `min-excerpt-chars` (200) thật, không chỉ đọc như thể dài.
	 * Một chuỗi 29 ký tự đặt tên là "đủ dài" làm mọi test dưới đây xanh hoặc đỏ
	 * vì lý do sai: nặng nhất là `tra_ve_rong_khi_da_co_summary` — nó vẫn xanh
	 * kể cả khi chốt chặn idempotent bị gỡ, vì excerpt ngắn đã tự loại bài rồi.
	 */
	private static final String EXCERPT_DU_DAI = "Đoạn trích đủ dài để tóm tắt. ".repeat(7);

	private Article article(String id, String excerpt, String summary) {
		Article a = new Article();
		a.setArticleId(id);
		a.setListBucket(Article.LIST_BUCKET);
		a.setPublishedAt("2026-08-10T10:00:00Z");
		a.setTitle("Tiêu đề " + id);
		a.setCanonicalUrl("https://a.test/" + id);
		a.setSourceName("Test");
		a.setExcerpt(excerpt);
		a.setSummary(summary);
		return a;
	}

	@Test
	void tra_ve_bai_co_excerpt_va_chua_co_summary() {
		repository.save(article("can-tom-tat", EXCERPT_DU_DAI, null));

		assertThat(catalog.findSummarizable("can-tom-tat")).hasValueSatisfying(v -> {
			assertThat(v.articleId()).isEqualTo("can-tom-tat");
			assertThat(v.title()).isEqualTo("Tiêu đề can-tom-tat");
			assertThat(v.excerpt()).isEqualTo(EXCERPT_DU_DAI);
		});
	}

	/**
	 * CHỐT CHẶN IDEMPOTENT — test quan trọng nhất của cả phase.
	 *
	 * Có HAI producer đẩy message (đường tươi và sweep) và SQS còn giao lại
	 * message tới 3 lần, nên cùng một article chắc chắn sẽ được xử lý nhiều lần.
	 * Nếu chỗ này trả về giá trị thay vì rỗng, model bị gọi lại và ta trả tiền
	 * hai lần — mà triệu chứng duy nhất là hoá đơn: trang vẫn hiển thị đúng.
	 *
	 * Excerpt PHẢI là `EXCERPT_DU_DAI`: chỉ khi bài đủ điều kiện về mọi mặt
	 * khác thì `isEmpty()` mới chứng minh được đúng một thứ — `summary` đã có.
	 */
	@Test
	void tra_ve_rong_khi_da_co_summary() {
		repository.save(article("da-xong", EXCERPT_DU_DAI, "Tóm tắt đã có."));

		assertThat(catalog.findSummarizable("da-xong")).isEmpty();
	}

	@Test
	void tra_ve_rong_khi_khong_co_excerpt() {
		repository.save(article("bai-phase-2", null, null));

		assertThat(catalog.findSummarizable("bai-phase-2")).isEmpty();
	}

	/**
	 * Excerpt dưới ngưỡng thì tóm tắt không ngắn hơn chính nó bao nhiêu — tốn
	 * một lời gọi model để đổi lấy gần như không gì (TDD §17 #13).
	 */
	@Test
	void tra_ve_rong_khi_excerpt_ngan_hon_nguong() {
		repository.save(article("qua-ngan", "Ngắn.", null));

		assertThat(catalog.findSummarizable("qua-ngan")).isEmpty();
	}

	@Test
	void tra_ve_rong_khi_khong_co_bai() {
		assertThat(catalog.findSummarizable("khong-ton-tai")).isEmpty();
	}

	/**
	 * AP4. `UpdateItem` chứ không `PutItem`: `PutItem` ghi đè cả item và sẽ xoá
	 * `excerpt`, `title`, `canonicalUrl` — mọi thứ `ingestion` đã ghi. Cùng loại
	 * bẫy mà `sourcesSync` của Phase 2 đã gặp với `etag`.
	 */
	@Test
	void attach_summary_khong_dung_toi_field_khac() {
		repository.save(article("gan-summary", EXCERPT_DU_DAI, null));

		repository.attachSummary("gan-summary", "Tóm tắt tiếng Việt.");

		Article after = repository.findById("gan-summary").orElseThrow();
		assertThat(after.getSummary()).isEqualTo("Tóm tắt tiếng Việt.");
		assertThat(after.getExcerpt()).isEqualTo(EXCERPT_DU_DAI);
		assertThat(after.getTitle()).isEqualTo("Tiêu đề gan-summary");
		assertThat(after.getCanonicalUrl()).isEqualTo("https://a.test/gan-summary");
	}
}
