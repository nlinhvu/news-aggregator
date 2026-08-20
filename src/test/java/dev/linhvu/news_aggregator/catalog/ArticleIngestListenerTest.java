package dev.linhvu.news_aggregator.catalog;

import java.util.ArrayList;
import java.util.List;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.catalog.events.ArticleAdded;
import dev.linhvu.news_aggregator.ingestion.events.ArticleDiscovered;
import dev.linhvu.news_aggregator.platform.IngestionRunMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({ FlociTestConfiguration.class, ArticleIngestListenerTest.SpyConfig.class })
class ArticleIngestListenerTest {

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	DynamoDbEnhancedClient enhancedClient;

	@Autowired
	IngestionRunMetrics metrics;

	@Autowired
	AddedSpy addedSpy;

	@Value("${news.catalog.table-name}")
	String tableName;

	DynamoDbTable<Article> table;

	/**
	 * Gom `toList()` trước rồi mới xoá: `scan()` của Enhanced Client là lazy +
	 * paginated, xoá ngay trong lúc duyệt là sửa đúng cái đang được duyệt.
	 */
	@BeforeEach
	void setUp() {
		table = enhancedClient.table(tableName, TableSchema.fromBean(Article.class));
		table.scan().items().stream().toList().forEach(table::deleteItem);
		metrics.reset();
		addedSpy.events.clear();
	}

	@Test
	void writes_a_new_article_and_counts_it_into_metrics() {
		publisher.publishEvent(discovered("https://a.test/1"));

		assertThat(table.scan().items()).hasSize(1);
		assertThat(metrics.added()).isEqualTo(1);
	}

	/**
	 * Đây là test QUAN TRỌNG NHẤT của slice 2: nó chứng minh cả pipeline
	 * idempotent, và đó là thứ khiến retry của Scheduler an toàn. Không có nó
	 * thì mọi cơ chế retry ở TDD §12 đều nguy hiểm.
	 */
	@Test
	void a_duplicate_item_is_neither_rewritten_nor_counted() {
		publisher.publishEvent(discovered("https://a.test/1"));
		metrics.reset();

		publisher.publishEvent(discovered("https://a.test/1"));

		assertThat(table.scan().items()).hasSize(1);
		assertThat(metrics.added()).isZero();
	}

	@Test
	void different_urls_become_two_articles() {
		publisher.publishEvent(discovered("https://a.test/1"));
		publisher.publishEvent(discovered("https://a.test/2"));

		assertThat(table.scan().items()).hasSize(2);
		assertThat(metrics.added()).isEqualTo(2);
	}

	@Test
	void the_written_article_has_every_field_and_a_constant_listBucket() {
		publisher.publishEvent(discovered("https://a.test/1"));

		Article saved = table.scan().items().iterator().next();
		assertThat(saved.getArticleId()).hasSize(32);
		assertThat(saved.getListBucket()).isEqualTo(Article.LIST_BUCKET);
		assertThat(saved.getTitle()).isEqualTo("Tiêu đề");
		assertThat(saved.getSourceName()).isEqualTo("Nguồn Test");
		assertThat(saved.getPublishedAt()).isEqualTo("2026-08-04T10:00:00Z");
		// `excerpt` đi từ event xuống bảng, và ĐÂY là chỗ duy nhất khẳng định
		// điều đó. Quên `setExcerpt` thì không gì đỏ: bài vẫn vào bảng, chỉ là
		// `findSummarizable` trả rỗng cho mọi bài và không ai được tóm tắt.
		assertThat(saved.getExcerpt()).isEqualTo("Đoạn trích mẫu.");
		// `sourceId` cũng đi từ event xuống bảng, và triệu chứng khi quên còn kín
		// hơn `excerpt`: bài vẫn vào bảng, vẫn hiện trên feed công khai, chỉ là
		// nó nằm NGOÀI `gsi-by-source` (sparse index) nên biến mất khỏi mọi feed
		// đã lọc. Backfill của Task 21 chỉ chạy một lượt, không dọn lại phần rơi
		// ra sau đó.
		assertThat(saved.getSourceId()).isEqualTo("src-1");
		// Phase 2 KHÔNG sinh summary — Phase 3 mới điền.
		assertThat(saved.getSummary()).isNull();
	}

	/**
	 * `ArticleAdded` là seam mà Phase 3 treo toàn bộ summarization lên. Javadoc
	 * của nó tự nói "một seam chưa từng chạy là một seam chưa từng được kiểm
	 * chứng" — nên nó phải được kiểm chứng ở ĐÂY, chứ không phải ở Phase 3.
	 *
	 * `articleId` trong event phải trùng cái đã ghi xuống bảng: listener nào
	 * của Phase 3 cũng sẽ dùng nó để đọc lại article.
	 */
	@Test
	void publishes_ArticleAdded_when_the_article_is_really_new() {
		publisher.publishEvent(discovered("https://a.test/1"));

		Article saved = table.scan().items().iterator().next();
		assertThat(addedSpy.events).singleElement().satisfies(e -> {
			assertThat(e.articleId()).isEqualTo(saved.getArticleId());
			assertThat(e.canonicalUrl()).isEqualTo("https://a.test/1");
			assertThat(e.title()).isEqualTo("Tiêu đề");
			assertThat(e.sourceName()).isEqualTo("Nguồn Test");
			assertThat(e.publishedAt()).isEqualTo("2026-08-04T10:00:00Z");
		});
	}

	/**
	 * MẶT TỐN TIỀN của tính idempotent. `ArticleAdded` là event DUY NHẤT được
	 * phép kích hoạt hành động tốn tiền (master §4 nguyên tắc 5) — Phase 3 gắn
	 * AI summarization vào đây. RSS trả ~20 bài gần nhất mỗi lượt, nên phát
	 * nhầm trên item trùng nghĩa là trả tiền lại cho cùng một bài, mỗi giờ,
	 * mãi mãi. Bảng vẫn đúng một dòng nên không có triệu chứng nào nhìn thấy
	 * được ngoài hoá đơn.
	 */
	@Test
	void does_NOT_publish_ArticleAdded_for_a_duplicate_item() {
		publisher.publishEvent(discovered("https://a.test/1"));
		addedSpy.events.clear();

		publisher.publishEvent(discovered("https://a.test/1"));

		assertThat(addedSpy.events).isEmpty();
	}

	private static ArticleDiscovered discovered(String url) {
		return new ArticleDiscovered("src-1", "Nguồn Test", url, "Tiêu đề",
				"2026-08-04T10:00:00Z", "Đoạn trích mẫu.");
	}

	/**
	 * Đứng thế chỗ listener của Phase 3 để seam `ArticleAdded` được đi qua thật.
	 *
	 * Spy và `@TestConfiguration` phải là HAI class. Gộp làm một rồi cho
	 * `@Bean` trả `this` thì Spring đăng ký cùng một object dưới hai tên bean —
	 * class cấu hình tự nó cũng là bean — và `@EventListener` được gắn HAI lần,
	 * nên mọi event vào list hai bản. Đã cắn thật lúc viết test này.
	 */
	static class AddedSpy {

		final List<ArticleAdded> events = new ArrayList<>();

		@EventListener
		void on(ArticleAdded event) {
			events.add(event);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SpyConfig {

		@Bean
		AddedSpy addedSpy() {
			return new AddedSpy();
		}
	}
}
