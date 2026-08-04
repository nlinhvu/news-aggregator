package dev.linhvu.seed;

import java.util.List;

import dev.linhvu.news_aggregator.catalog.Article;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Source set riêng, KHÔNG vào boot jar.
 *
 * Vì sao không dùng file JSON + `aws dynamodb batch-write-item`: file đó
 * phải tự lặp lại tên attribute VÀ kiểu DynamoDB. Ngày nào Article đổi
 * `title` thành `headline`, file đó KHÔNG hỏng — nó vẫn ghi thành công một
 * attribute mà không ai đọc nữa. Lỗi im lặng.
 *
 * Đi qua `Article` và Jackson thì cùng thay đổi đó làm vỡ deserialization
 * ngay — xem {@link ArticleFixtures} về điều kiện để tính chất đó thành thật.
 *
 * Cùng file fixture này được test T2 dùng lại qua {@link ArticleFixtures}, nên
 * dữ liệu ở local test và dữ liệu đã seed trong dev/qa/prod là GIỐNG HỆT NHAU.
 *
 * Đây là đường GHI duy nhất vào bảng article, và nó chạy bằng credential của
 * người vận hành — không đi qua execution role của Lambda (role đó chỉ có
 * `dynamodb:Query`, xem AppStack).
 */
public final class SeedApplication {

	private SeedApplication() {
	}

	public static void main(String[] args) {
		String tableName = System.getenv()
				.getOrDefault("NEWS_ARTICLES_TABLE", "articles");

		List<Article> articles = ArticleFixtures.load();

		try (DynamoDbClient dynamoDbClient = DynamoDbClient.create()) {
			DynamoDbTable<Article> table = DynamoDbEnhancedClient.builder()
					.dynamoDbClient(dynamoDbClient)
					.build()
					.table(tableName, TableSchema.fromBean(Article.class));

			articles.forEach(table::putItem);
		}

		System.out.printf("Đã ghi %d article vào bảng %s%n",
				articles.size(), tableName);
	}
}
