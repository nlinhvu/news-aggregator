package dev.linhvu.news_aggregator.catalog;

import java.util.List;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

@Repository
@Lazy
class ArticleRepository {

	private final DynamoDbTable<Article> table;
	private final DynamoDbIndex<Article> recentIndex;

	ArticleRepository(DynamoDbEnhancedClient client,
			@Value("${news.catalog.table-name}") String tableName) {
		this.table = client.table(tableName, TableSchema.fromBean(Article.class));
		this.recentIndex = table.index("gsi-recent");
	}

	void save(Article article) {
		table.putItem(article);
	}

	/**
	 * AP1. Query — KHÔNG phải Scan — nên chi phí tỉ lệ với số item trả về,
	 * không tỉ lệ với kích thước bảng.
	 *
	 * `publishedAt` là chuỗi ISO-8601 UTC nên thứ tự chuỗi trùng thứ tự thời
	 * gian; `scanIndexForward(false)` cho ra mới nhất trước mà không cần sắp
	 * xếp lại ở tầng ứng dụng.
	 */
	List<Article> findRecent(int limit) {
		return recentIndex.query(QueryEnhancedRequest.builder()
						.queryConditional(QueryConditional.keyEqualTo(
								Key.builder().partitionValue(Article.LIST_BUCKET).build()))
						.scanIndexForward(false)
						.limit(limit)
						.build())
				.stream()
				.flatMap(page -> page.items().stream())
				.limit(limit)
				.toList();
	}
}
