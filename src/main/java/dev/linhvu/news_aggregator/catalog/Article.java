package dev.linhvu.news_aggregator.catalog;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

@DynamoDbBean
public class Article {

	/**
	 * Tên GSI cho AP1 và AP9. Phải KHỚP TỪNG KÝ TỰ với
	 * `DataStack.RECENT_INDEX_V2_NAME` bên module `infra` — hai module không thấy
	 * nhau nên compiler không bắt được, và lệch tên chỉ lộ ra lúc runtime dưới
	 * dạng `ResourceNotFoundException`.
	 *
	 * Hậu tố `-v2` là di sản của lần migrate: index đời đầu không project
	 * `excerpt`, mà projection của GSI là bất biến nên phải đổi tên mới thêm được.
	 * Index cũ đã bị xoá; tên này không đổi nữa vì đổi nó lại là một migrate hai
	 * lần deploy nữa.
	 */
	public static final String RECENT_INDEX_V2 = "gsi-recent-v2";

	/**
	 * Tên GSI cho AP10 + AP11 (bài của một nguồn, rồi fan-out qua tập nguồn đã
	 * chọn). Phải KHỚP TỪNG KÝ TỰ với `DataStack.BY_SOURCE_INDEX_NAME` bên module
	 * `infra`, cùng lý do với hằng số trên.
	 *
	 * KHÔNG có hậu tố phiên bản: index này dùng `ProjectionType.ALL`, nên thứ đã
	 * ép `gsi-recent` phải đẻ ra `-v2` — danh sách projection bất biến — không tồn
	 * tại ở đây.
	 */
	public static final String BY_SOURCE_INDEX = "gsi-by-source";

	/** Partition key hằng số của gsi-recent — xem TDD §6. */
	public static final String LIST_BUCKET = "ALL";

	private String articleId;
	private String listBucket;
	private String publishedAt;
	private String title;
	private String canonicalUrl;
	private String sourceName;
	private String excerpt;
	private String summary;

	@DynamoDbPartitionKey
	public String getArticleId() { return articleId; }
	public void setArticleId(String articleId) { this.articleId = articleId; }

	@DynamoDbSecondaryPartitionKey(indexNames = { RECENT_INDEX_V2 })
	public String getListBucket() { return listBucket; }
	public void setListBucket(String listBucket) { this.listBucket = listBucket; }

	@DynamoDbSecondarySortKey(indexNames = { RECENT_INDEX_V2 })
	public String getPublishedAt() { return publishedAt; }
	public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }

	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }

	public String getCanonicalUrl() { return canonicalUrl; }
	public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }

	public String getSourceName() { return sourceName; }
	public void setSourceName(String sourceName) { this.sourceName = sourceName; }

	// Attribute NGOÀI KEY nên thêm nó cần đúng KHÔNG migration (master §4
	// nguyên tắc 7). Đây là lần đầu tính chất đó được dùng tới chứ không chỉ
	// được tuyên bố. Bài của Phase 2 không có attribute này, và đó là lý do
	// `findSummarizable` trả rỗng cho chúng (TDD §17 #6).
	public String getExcerpt() { return excerpt; }
	public void setExcerpt(String excerpt) { this.excerpt = excerpt; }

	public String getSummary() { return summary; }
	public void setSummary(String summary) { this.summary = summary; }
}
