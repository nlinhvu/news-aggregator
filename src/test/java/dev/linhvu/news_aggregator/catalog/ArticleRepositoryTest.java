package dev.linhvu.news_aggregator.catalog;

import java.util.List;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(FlociTestConfiguration.class)
class ArticleRepositoryTest {

	@Autowired
	ArticleRepository repository;
	@Autowired
	DynamoDbClient dynamoDbClient;

	@BeforeEach
	void taoBangVaNapDuLieu() {
		String table = System.getenv().getOrDefault("NEWS_ARTICLES_TABLE", "articles");
		try {
			dynamoDbClient.createTable(CreateTableRequest.builder()
					.tableName(table)
					.keySchema(KeySchemaElement.builder()
							.attributeName("articleId").keyType(KeyType.HASH).build())
					.attributeDefinitions(
							AttributeDefinition.builder().attributeName("articleId")
									.attributeType(ScalarAttributeType.S).build(),
							AttributeDefinition.builder().attributeName("listBucket")
									.attributeType(ScalarAttributeType.S).build(),
							AttributeDefinition.builder().attributeName("publishedAt")
									.attributeType(ScalarAttributeType.S).build())
					.globalSecondaryIndexes(GlobalSecondaryIndex.builder()
							.indexName("gsi-recent")
							.keySchema(
									KeySchemaElement.builder().attributeName("listBucket")
											.keyType(KeyType.HASH).build(),
									KeySchemaElement.builder().attributeName("publishedAt")
											.keyType(KeyType.RANGE).build())
							.projection(Projection.builder()
									.projectionType(ProjectionType.INCLUDE)
									.nonKeyAttributes("title", "canonicalUrl",
											"sourceName", "summary")
									.build())
							.build())
					.billingMode(BillingMode.PAY_PER_REQUEST)
					.build());
		} catch (ResourceInUseException ignored) {
			// bảng đã tồn tại từ test trước
		}

		repository.save(article("a", "2026-01-01T00:00:00Z", "Bài cũ nhất"));
		repository.save(article("c", "2026-03-01T00:00:00Z", "Bài mới nhất"));
		repository.save(article("b", "2026-02-01T00:00:00Z", "Bài ở giữa"));
	}

	private Article article(String id, String publishedAt, String title) {
		Article a = new Article();
		a.setArticleId(id);
		a.setListBucket(Article.LIST_BUCKET);
		a.setPublishedAt(publishedAt);
		a.setTitle(title);
		a.setCanonicalUrl("https://example.com/" + id);
		a.setSourceName("Example Blog");
		return a;
	}

	@Test
	void tra_ve_article_moi_nhat_truoc() {
		List<Article> found = repository.findRecent(10);

		assertThat(found).extracting(Article::getTitle)
				.containsExactly("Bài mới nhất", "Bài ở giữa", "Bài cũ nhất");
	}

	@Test
	void ton_trong_limit() {
		assertThat(repository.findRecent(2)).hasSize(2);
	}
}
