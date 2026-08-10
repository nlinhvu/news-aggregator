package dev.linhvu.news_aggregator.catalog;

import java.util.List;
import java.util.Optional;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

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
		this.recentIndex = table.index(Article.RECENT_INDEX);
	}

	void save(Article article) {
		table.putItem(article);
	}

	/**
	 * AP3. Ghi nếu chưa tồn tại, MỘT lời gọi, không cửa sổ race.
	 *
	 * `ConditionalCheckFailedException` là LUỒNG BÌNH THƯỜNG, không phải lỗi:
	 * từ lượt thứ hai trở đi đa số item là trùng. Log nó ở mức lỗi là cách chắc
	 * chắn để không ai đọc log nữa.
	 *
	 * Phương án `GetItem` rồi `PutItem` thực ra RẺ HƠN (~$0,02 so với ~$0,17
	 * mỗi tháng — conditional write thất bại vẫn tốn WCU), nhưng nó có cửa sổ
	 * race giữa hai lời gọi. Chênh lệch $0,15/tháng là nhiễu so với trần $5.
	 *
	 * @return true nếu article là MỚI
	 */
	boolean saveIfAbsent(Article article) {
		try {
			table.putItem(PutItemEnhancedRequest.builder(Article.class)
					.item(article)
					.conditionExpression(Expression.builder()
							.expression("attribute_not_exists(articleId)")
							.build())
					.build());
			return true;
		}
		catch (ConditionalCheckFailedException ex) {
			return false;
		}
	}

	/** AP8. `GetItem` theo PK. */
	Optional<Article> findById(String articleId) {
		return Optional.ofNullable(table.getItem(
				Key.builder().partitionValue(articleId).build()));
	}

	/**
	 * AP4. `UpdateItem` với `ignoreNulls`, KHÔNG `PutItem`.
	 *
	 * `PutItem` ghi đè cả item, tức xoá `excerpt`, `title`, `canonicalUrl`,
	 * `sourceName` — mọi thứ `ingestion` đã ghi. Enhanced client dựng item từ
	 * bean, và bean ta cầm ở đây chỉ có `articleId` + `summary`, nên phần còn
	 * lại sẽ thành null và bị xoá. `ignoreNulls(true)` là thứ biến nó thành
	 * "chỉ SET đúng attribute có giá trị".
	 *
	 * Cùng loại bẫy mà `sourcesSync` của Phase 2 đã gặp với `etag`, và triệu
	 * chứng cũng âm thầm như thế: vẫn chạy, chỉ mất dữ liệu.
	 */
	void attachSummary(String articleId, String summary) {
		Article patch = new Article();
		patch.setArticleId(articleId);
		patch.setSummary(summary);
		table.updateItem(UpdateItemEnhancedRequest.builder(Article.class)
				.item(patch)
				.ignoreNulls(true)
				.build());
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
