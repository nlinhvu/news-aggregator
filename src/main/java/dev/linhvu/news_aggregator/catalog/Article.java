package dev.linhvu.news_aggregator.catalog;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

@DynamoDbBean
public class Article {

	/** Partition key hằng số của gsi-recent — xem TDD §6. */
	public static final String LIST_BUCKET = "ALL";

	private String articleId;
	private String listBucket;
	private String publishedAt;
	private String title;
	private String canonicalUrl;
	private String sourceName;
	private String summary;

	@DynamoDbPartitionKey
	public String getArticleId() { return articleId; }
	public void setArticleId(String articleId) { this.articleId = articleId; }

	@DynamoDbSecondaryPartitionKey(indexNames = "gsi-recent")
	public String getListBucket() { return listBucket; }
	public void setListBucket(String listBucket) { this.listBucket = listBucket; }

	@DynamoDbSecondarySortKey(indexNames = "gsi-recent")
	public String getPublishedAt() { return publishedAt; }
	public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }

	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }

	public String getCanonicalUrl() { return canonicalUrl; }
	public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }

	public String getSourceName() { return sourceName; }
	public void setSourceName(String sourceName) { this.sourceName = sourceName; }

	public String getSummary() { return summary; }
	public void setSummary(String summary) { this.summary = summary; }
}
