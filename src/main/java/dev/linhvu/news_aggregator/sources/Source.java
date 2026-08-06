package dev.linhvu.news_aggregator.sources;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@DynamoDbBean
public class Source {

	private String sourceId;
	private String name;
	private String feedUrl;
	private boolean enabled;
	private String etag;
	private String lastModified;
	private String lastFetchedAt;

	@DynamoDbPartitionKey
	public String getSourceId() { return sourceId; }
	public void setSourceId(String sourceId) { this.sourceId = sourceId; }

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public String getFeedUrl() { return feedUrl; }
	public void setFeedUrl(String feedUrl) { this.feedUrl = feedUrl; }

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }

	public String getEtag() { return etag; }
	public void setEtag(String etag) { this.etag = etag; }

	public String getLastModified() { return lastModified; }
	public void setLastModified(String lastModified) { this.lastModified = lastModified; }

	public String getLastFetchedAt() { return lastFetchedAt; }
	public void setLastFetchedAt(String lastFetchedAt) { this.lastFetchedAt = lastFetchedAt; }
}
