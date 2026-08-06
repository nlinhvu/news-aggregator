package dev.linhvu.news_aggregator.sources;

import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

/**
 * Dùng client low-level chứ không Enhanced Client, vì `recordFetch` cần
 * UpdateExpression chỉ đụng ba attribute. Enhanced Client `updateItem` gửi cả
 * bean và sẽ xoá attribute nào đang null — tức là xoá `name`/`enabled` mà
 * `sourcesSync` sở hữu.
 */
@Repository
@Lazy
class SourceRepository {

	private final DynamoDbClient client;
	private final String tableName;

	SourceRepository(DynamoDbClient client,
			@Value("${news.sources.table-name}") String tableName) {
		this.client = client;
		this.tableName = tableName;
	}

	/**
	 * AP5. Scan chứ không Query: bảng bị chặn trên ~30 dòng bởi master §2.
	 * Lọc `enabled` ở tầng ứng dụng chứ không bằng FilterExpression — ở kích
	 * thước này chúng tương đương về chi phí, còn lọc ở Java thì đọc dễ hơn.
	 */
	List<Source> findAll() {
		return client.scanPaginator(ScanRequest.builder().tableName(tableName).build())
				.items().stream()
				.map(SourceRepository::toSource)
				.toList();
	}

	/** AP6. Chỉ đụng ba attribute trạng thái. */
	void updateFetchState(String sourceId, String etag, String lastModified,
			String fetchedAt) {
		StringBuilder set = new StringBuilder("SET lastFetchedAt = :f");
		Map<String, AttributeValue> values = new java.util.HashMap<>();
		values.put(":f", AttributeValue.fromS(fetchedAt));

		// DynamoDB không cho set attribute bằng null trong UpdateExpression —
		// phải REMOVE nó. Lượt đầu tiên của một nguồn luôn rơi vào nhánh này.
		StringBuilder remove = new StringBuilder();
		if (etag != null) {
			set.append(", etag = :e");
			values.put(":e", AttributeValue.fromS(etag));
		}
		else {
			remove.append(" REMOVE etag");
		}
		if (lastModified != null) {
			set.append(", lastModified = :m");
			values.put(":m", AttributeValue.fromS(lastModified));
		}
		else {
			remove.append(remove.isEmpty() ? " REMOVE lastModified" : ", lastModified");
		}

		client.updateItem(UpdateItemRequest.builder()
				.tableName(tableName)
				.key(Map.of("sourceId", AttributeValue.fromS(sourceId)))
				.updateExpression(set + remove.toString())
				.expressionAttributeValues(values)
				.build());
	}

	private static Source toSource(Map<String, AttributeValue> item) {
		Source s = new Source();
		s.setSourceId(str(item, "sourceId"));
		s.setName(str(item, "name"));
		s.setFeedUrl(str(item, "feedUrl"));
		s.setEnabled(item.containsKey("enabled") && Boolean.TRUE.equals(
				item.get("enabled").bool()));
		s.setEtag(str(item, "etag"));
		s.setLastModified(str(item, "lastModified"));
		s.setLastFetchedAt(str(item, "lastFetchedAt"));
		return s;
	}

	private static String str(Map<String, AttributeValue> item, String key) {
		AttributeValue v = item.get(key);
		return v == null ? null : v.s();
	}
}
