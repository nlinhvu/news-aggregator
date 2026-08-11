package dev.linhvu.news_aggregator.catalog;

import java.time.Duration;
import java.time.Instant;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
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

	private Article article(String id, String publishedAt, String excerpt, String summary) {
		Article a = article(id, excerpt, summary);
		a.setPublishedAt(publishedAt);
		return a;
	}

	private static String gioTruoc(int hours) {
		return Instant.now().minus(Duration.ofHours(hours)).toString();
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

	@Test
	void quet_tra_ve_bai_can_tom_tat_trong_cua_so() {
		repository.save(article("moi-can-lam", gioTruoc(2), EXCERPT_DU_DAI, null));
		repository.save(article("moi-da-xong", gioTruoc(3), EXCERPT_DU_DAI,
				"Đã có tóm tắt."));
		repository.save(article("moi-khong-excerpt", gioTruoc(4), null, null));

		assertThat(catalog.findSummarizable(Duration.ofHours(48), 25))
				.extracting(SummarizableArticle::articleId)
				.containsExactly("moi-can-lam");
	}

	/**
	 * Cửa sổ là cơ chế chặn rò rỉ chi phí của ADR-0014: bài hỏng vĩnh viễn KHÔNG
	 * được "tha thứ", nó chỉ già đi rồi rơi khỏi tầm với. Test này ghim cái ranh
	 * giới đó.
	 */
	@Test
	void quet_khong_lay_bai_ngoai_cua_so() {
		repository.save(article("qua-cu", gioTruoc(72), EXCERPT_DU_DAI, null));

		assertThat(catalog.findSummarizable(Duration.ofHours(48), 25)).isEmpty();
	}

	/**
	 * Ngưỡng độ dài KHÔNG nằm trong `FilterExpression` mà ở tầng ứng dụng, nên
	 * nó là thứ duy nhất chặn bài excerpt-ngắn khỏi đường sweep. Bài như thế vẫn
	 * thoả `attribute_exists(excerpt) AND attribute_not_exists(summary)` nên
	 * query TRẢ VỀ nó; gỡ `filter` ở service thì nó vào queue, rồi bị
	 * `SummarizeHandler` bỏ qua ở consumer — `enqueued` đếm việc không có thật.
	 */
	@Test
	void quet_khong_lay_bai_excerpt_ngan_hon_nguong() {
		repository.save(article("quet-qua-ngan", gioTruoc(2), "Ngắn.", null));

		assertThat(catalog.findSummarizable(Duration.ofHours(48), 25)).isEmpty();
	}

	/**
	 * 30 bài đã có summary đứng trước 1 bài cần làm, để ghim rằng kết quả KHÔNG
	 * dừng ở trang đầu. Bài mới nhất đứng TRƯỚC vì `gsi-recent-v2` sắp giảm dần
	 * theo `publishedAt`, nên bài cần làm được đặt CŨ NHẤT để nó nằm sau đủ xa.
	 *
	 * Xuất phát điểm là cái bẫy DynamoDB: `Limit` áp TRƯỚC `FilterExpression`,
	 * nên "đọc 25 item rồi lọc" có thể trả rỗng dù còn việc — không lỗi, log chỉ
	 * nói `enqueued=0`, trông y hệt "không có việc để làm".
	 *
	 * Nhưng ĐO THẬT bằng mutation thì bẫy đó đã bị enhanced client vô hiệu, chứ
	 * không phải bị code này chặn: `PageIterable.stream()` tự đi tiếp
	 * `lastEvaluatedKey`, nên thêm `.limit(25)` vào request MỘT MÌNH không làm
	 * test này đỏ (mutation sống sót). Test chỉ đỏ khi có THÊM việc cắt ở trang
	 * đầu — `.stream().limit(1)` hoặc chuyển sang low-level client không phân
	 * trang. Đó chính xác là phạm vi nó bảo vệ, không hơn.
	 */
	@Test
	void quet_doc_tiep_trang_khi_limit_bi_filter_an_het() {
		for (int i = 0; i < 30; i++) {
			repository.save(article("da-xong-" + i, gioTruoc(1 + i), EXCERPT_DU_DAI,
					"Tóm tắt " + i));
		}
		repository.save(article("con-sot-lai", gioTruoc(40), EXCERPT_DU_DAI, null));

		assertThat(catalog.findSummarizable(Duration.ofHours(48), 25))
				.extracting(SummarizableArticle::articleId)
				.containsExactly("con-sot-lai");
	}

	@Test
	void quet_ton_trong_limit() {
		for (int i = 0; i < 10; i++) {
			repository.save(article("can-lam-" + i, gioTruoc(1 + i), EXCERPT_DU_DAI,
					null));
		}

		assertThat(catalog.findSummarizable(Duration.ofHours(48), 4)).hasSize(4);
	}
}
