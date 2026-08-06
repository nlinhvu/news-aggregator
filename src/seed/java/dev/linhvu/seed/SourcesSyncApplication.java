package dev.linhvu.seed;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Đồng bộ `sources.yaml` vào bảng `sources`. Chạy bằng credential của NGƯỜI
 * VẬN HÀNH, không đi qua execution role của Lambda — role đó cố ý không có
 * `PutItem` trên bảng này.
 *
 * Dùng **UpdateItem**, không PutItem. Đây không phải chi tiết: PutItem sẽ ghi
 * đè cả item và xoá sạch `etag`/`lastModified`/`lastFetchedAt` mỗi lần đồng bộ,
 * khiến conditional GET của slice 4 vô hiệu MỘT CÁCH ÂM THẦM — nó vẫn chạy,
 * chỉ là luôn tải full và không ai nhận ra.
 *
 * Đây là vế đối xứng của `SourceCatalog.recordFetch`: bên kia được sửa trạng
 * thái mà không đụng cấu hình, bên này được sửa cấu hình mà không đụng trạng
 * thái. Cả hai đều mất tính chất đó nếu chuyển sang PutItem.
 */
public final class SourcesSyncApplication {

	private SourcesSyncApplication() {
	}

	public static void main(String[] args) {
		String tableName = System.getenv()
				.getOrDefault("NEWS_SOURCES_TABLE", "sources");

		List<Map<String, Object>> sources = readSources();

		try (DynamoDbClient client = DynamoDbClient.create()) {
			for (Map<String, Object> s : sources) {
				String id = (String) s.get("sourceId");
				client.updateItem(UpdateItemRequest.builder()
						.tableName(tableName)
						.key(Map.of("sourceId", AttributeValue.fromS(id)))
						.updateExpression("SET #n = :n, feedUrl = :u, enabled = :e")
						// `name` là reserved word của DynamoDB — dùng thẳng trong
						// UpdateExpression sẽ ném ValidationException.
						.expressionAttributeNames(Map.of("#n", "name"))
						.expressionAttributeValues(Map.of(
								":n", AttributeValue.fromS((String) s.get("name")),
								":u", AttributeValue.fromS((String) s.get("feedUrl")),
								":e", AttributeValue.fromBool(
										Boolean.TRUE.equals(s.get("enabled")))))
						.build());
				System.out.printf("đồng bộ %s%n", id);
			}
		}

		System.out.printf("Đã đồng bộ %d nguồn vào bảng %s%n", sources.size(), tableName);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> readSources() {
		Map<String, Object> root;
		try (InputStream in = SourcesSyncApplication.class
				.getClassLoader().getResourceAsStream("sources.yaml")) {
			if (in == null) {
				throw new IllegalStateException("không tìm thấy sources.yaml trên classpath");
			}
			root = new Yaml().load(in);
		}
		catch (Exception ex) {
			throw new IllegalStateException("đọc sources.yaml thất bại", ex);
		}

		List<Map<String, Object>> sources =
				(List<Map<String, Object>>) root.get("sources");
		if (sources == null || sources.isEmpty()) {
			throw new IllegalStateException("sources.yaml không có mục `sources` nào");
		}
		return sources;
	}
}

