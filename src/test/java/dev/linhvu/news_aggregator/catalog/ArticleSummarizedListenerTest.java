package dev.linhvu.news_aggregator.catalog;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.summarization.events.ArticleSummarized;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nửa sau của cycle `catalog ↔ summarization`: `summarization` phát fact, và
 * `catalog` — chủ sở hữu bảng `articles` — là bên duy nhất ghi vào bảng đó.
 *
 * `ArticleSummarizedListener` chỉ có một dòng thân hàm và không test nào khác
 * chạm tới nó. Không có class này thì xoá dòng đó vẫn xanh cả suite: model vẫn
 * bị gọi, vẫn trả tóm tắt, tiền vẫn tiêu — chỉ là không gì được ghi xuống và
 * trang không bao giờ đổi. Đó là chế độ hỏng ĐẮT NHẤT của cả phase.
 */
@SpringBootTest
@Import(FlociTestConfiguration.class)
class ArticleSummarizedListenerTest {

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	ArticleRepository repository;

	@Autowired
	DynamoDbEnhancedClient enhancedClient;

	@Value("${news.catalog.table-name}")
	String tableName;

	/** Bảng dùng chung giữa các test class — xem `ArticleCatalogServiceTest`. */
	@AfterEach
	void cleanTable() {
		DynamoDbTable<Article> table =
				enhancedClient.table(tableName, TableSchema.fromBean(Article.class));
		table.scan().items().stream().toList().forEach(table::deleteItem);
	}

	@Test
	void writes_the_summary_without_touching_other_fields() {
		Article a = new Article();
		a.setArticleId("summarised");
		a.setListBucket(Article.LIST_BUCKET);
		a.setPublishedAt("2026-08-10T10:00:00Z");
		a.setTitle("Tiêu đề gốc");
		a.setCanonicalUrl("https://a.test/summarised");
		a.setSourceName("Test");
		a.setExcerpt("Đoạn trích gốc.");
		repository.save(a);

		publisher.publishEvent(
				new ArticleSummarized("summarised", "Tóm tắt tiếng Việt."));

		Article after = repository.findById("summarised").orElseThrow();
		assertThat(after.getSummary()).isEqualTo("Tóm tắt tiếng Việt.");
		assertThat(after.getExcerpt()).isEqualTo("Đoạn trích gốc.");
		assertThat(after.getTitle()).isEqualTo("Tiêu đề gốc");
	}
}
