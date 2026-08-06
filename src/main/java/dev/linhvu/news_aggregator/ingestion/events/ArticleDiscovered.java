package dev.linhvu.news_aggregator.ingestion.events;

/**
 * Phát khi thấy một item trong feed — CHƯA BIẾT là mới hay trùng (master §11).
 * RSS luôn trả ~20 bài gần nhất nên từ lượt thứ hai trở đi đa số là trùng.
 *
 * Event này KHÔNG BAO GIỜ được kích hoạt hành động tốn tiền (master §4 nguyên
 * tắc 5). Việc đó thuộc về `ArticleAdded`.
 */
public record ArticleDiscovered(String sourceId, String sourceName,
		String canonicalUrl, String title, String publishedAt) {
}
