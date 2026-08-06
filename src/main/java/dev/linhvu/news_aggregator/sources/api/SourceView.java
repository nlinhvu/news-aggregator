package dev.linhvu.news_aggregator.sources.api;

/**
 * Cái `ingestion` nhìn thấy. Cố ý KHÔNG lộ `enabled` — nguồn đã tắt thì không
 * bao giờ ra khỏi `enabledSources()`, nên trường đó vô nghĩa với người gọi và
 * chỉ mở đường cho một lần kiểm sai chỗ.
 */
public record SourceView(String sourceId, String name, String feedUrl,
		String etag, String lastModified) {
}
