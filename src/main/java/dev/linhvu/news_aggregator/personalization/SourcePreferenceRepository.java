package dev.linhvu.news_aggregator.personalization;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

/**
 * AP13 — `GetItem` theo `userId`. Đúng một access pattern, nên bảng không có
 * GSI nào (xem `DataStackTest#bang_user_preferences_khoa_bang_userId_...`).
 *
 * `PutItem` chứ không `UpdateItem`: item chỉ có hai trường ngoài khoá và lần
 * ghi nào cũng ghi cả hai, nên ghi đè là hình dạng ĐÚNG ở đây — khác `sources`
 * và `articles`, nơi nhiều tác nhân ghi những phần khác nhau của cùng một item.
 * Đó cũng là lý do `web` KHÔNG được cấp `dynamodb:UpdateItem` trên bảng này.
 */
@Repository
@Lazy
class SourcePreferenceRepository {

	private final DynamoDbTable<SourcePreferences> table;

	SourcePreferenceRepository(DynamoDbEnhancedClient client,
			@Value("${news.personalization.preferences-table}") String tableName) {
		this.table = client.table(tableName,
				TableSchema.fromBean(SourcePreferences.class));
	}

	/** `Optional.empty()` = người dùng chưa từng chọn gì ⇒ TẤT CẢ nguồn. */
	Optional<SourcePreferences> findByUserId(String userId) {
		return Optional.ofNullable(table.getItem(
				Key.builder().partitionValue(userId).build()));
	}

	void save(String userId, List<String> sourceIds) {
		SourcePreferences item = new SourcePreferences();
		item.setUserId(userId);
		item.setSourceIds(sourceIds);
		item.setUpdatedAt(Instant.now().toString());
		table.putItem(item);
	}
}
