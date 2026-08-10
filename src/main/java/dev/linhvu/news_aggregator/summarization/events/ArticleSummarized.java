package dev.linhvu.news_aggregator.summarization.events;

/**
 * Phát khi model đã sinh xong tóm tắt. `catalog` nghe và tự ghi bảng của chính
 * nó (AP4) — `summarization` KHÔNG BAO GIỜ chạm bảng `articles`
 * (master §4 nguyên tắc 4).
 */
public record ArticleSummarized(String articleId, String summary) {
}
