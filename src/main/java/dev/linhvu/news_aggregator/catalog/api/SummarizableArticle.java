package dev.linhvu.news_aggregator.catalog.api;

/**
 * Đúng ba thứ `summarization` cần để dựng prompt, không hơn. KHÔNG mang
 * `canonicalUrl` hay `sourceName`: chúng không vào prompt, và mọi field thừa
 * ở đây là một field mà module kia có thể bắt đầu phụ thuộc vào.
 */
public record SummarizableArticle(String articleId, String title, String excerpt) {
}
