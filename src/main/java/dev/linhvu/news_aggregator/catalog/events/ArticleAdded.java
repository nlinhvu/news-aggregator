package dev.linhvu.news_aggregator.catalog.events;

/**
 * Phát khi article đã được ghi vào catalog và XÁC NHẬN là mới (master §11).
 * CHỈ event này mới được kích hoạt hành động tốn tiền — AI, scraping, dịch.
 *
 * Phase 2 chưa có ai nghe trong production. Vẫn phát, vì master §5 quy định
 * `catalog` phát nó và Phase 3 treo toàn bộ thiết kế summarization lên đây.
 * Một seam chưa từng chạy là một seam chưa từng được kiểm chứng — nên nó được
 * đi qua thật ở `ArticleIngestListenerTest`, cả nhánh phát lẫn nhánh KHÔNG
 * phát khi item trùng.
 */
public record ArticleAdded(String articleId, String sourceName, String canonicalUrl,
		String title, String publishedAt) {
}
