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
		// Chỗ DUY NHẤT chuyển đọc sang v2, nên `findRecent` đi theo và
		// `gsi-recent` cũ hết người đọc — điều kiện để Task 17 xoá được nó.
		// Query một index chưa backfill xong trả về kết quả THIẾU chứ không lỗi,
		// nên dòng này chỉ được deploy sau khi `gsi-recent-v2` đã `ACTIVE`.
		this.recentIndex = table.index(Article.RECENT_INDEX_V2);
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

	/**
	 * AP9. `Query` trên `gsi-recent-v2` — KHÔNG `Scan`. `publishedAt` là sort key
	 * nên cửa sổ thời gian là điều kiện KEY thật, và chi phí tỉ lệ với số item
	 * TRONG CỬA SỔ, không với kích thước bảng. Đây là thứ khiến cửa sổ 48h vừa
	 * chặn rò rỉ chi phí model vừa chặn chi phí đọc.
	 *
	 * `Limit` của DynamoDB áp TRƯỚC `FilterExpression`, nên đặt `.limit(n)` vào
	 * request nghĩa là "đọc n item rồi lọc", không phải "trả về n item thoả".
	 * Ở đây `limit` chỉ áp trên stream ĐÃ lọc, và enhanced client phân trang
	 * lười, nên pipeline tự đọc tiếp trang cho tới khi đủ hoặc hết cửa sổ.
	 * Thiếu tính chất đó thì một cửa sổ đầy bài đã summarize trả về rỗng dù còn
	 * việc, và triệu chứng là sự im lặng: `enqueued=0`, không lỗi.
	 *
	 * Đánh đổi: nếu cửa sổ chứa RẤT NHIỀU item đã summarize, ta đọc hết chúng.
	 * Ở khối lượng master §2 (≤200 bài/ngày ⇒ ≤400 bài trong 48h) đó là vài chục
	 * RCU — chấp nhận được, và nó là lý do cửa sổ phải hẹp.
	 */
	List<Article> findPendingSummary(String publishedAfter, int limit) {
		return recentIndex.query(QueryEnhancedRequest.builder()
						.queryConditional(QueryConditional.sortGreaterThan(
								Key.builder()
										.partitionValue(Article.LIST_BUCKET)
										.sortValue(publishedAfter)
										.build()))
						.scanIndexForward(false)
						// Lọc được theo `excerpt` CHỈ VÌ nó nằm trong projection của
						// `gsi-recent-v2`. Trên `gsi-recent` cũ thì `excerpt` không
						// được project, và `attribute_exists(excerpt)` khớp KHÔNG
						// item nào — im lặng. Đó là lý do Task 11B tồn tại.
						.filterExpression(Expression.builder()
								.expression("attribute_exists(excerpt) "
										+ "AND attribute_not_exists(summary)")
								.build())
						.build())
				.stream()
				.flatMap(page -> page.items().stream())
				.limit(limit)
				.toList();
	}
}
