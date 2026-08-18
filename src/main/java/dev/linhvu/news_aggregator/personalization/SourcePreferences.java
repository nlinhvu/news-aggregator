package dev.linhvu.news_aggregator.personalization;

import java.util.List;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Item của bảng `user-preferences`. Ba trường, không hơn.
 *
 * `userId` là Cognito `sub` — KHÔNG phải email. Bảng này không lưu PII: xoá
 * tài khoản là một thao tác trên Cognito, không phải một migration ở đây
 * (master §8.4, sửa 2026-08-13).
 *
 * KHÔNG có TTL: lựa chọn của người dùng không tự hết hạn — khác hẳn `sessions`,
 * xem `DataStack`.
 */
@DynamoDbBean
public class SourcePreferences {

	private String userId;
	private List<String> sourceIds;
	private String updatedAt;

	@DynamoDbPartitionKey
	public String getUserId() { return userId; }
	public void setUserId(String userId) { this.userId = userId; }

	/**
	 * RỖNG hoặc VẮNG MẶT nghĩa là **tất cả nguồn**, không phải "không nguồn
	 * nào" (TDD §17 #10). Hợp đồng này sống ở đây, ở
	 * `ArticleCatalog.recentBySources`, và ở `MyFeedController` — ba chỗ, một
	 * cách hiểu.
	 */
	public List<String> getSourceIds() { return sourceIds; }
	public void setSourceIds(List<String> sourceIds) { this.sourceIds = sourceIds; }

	public String getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
