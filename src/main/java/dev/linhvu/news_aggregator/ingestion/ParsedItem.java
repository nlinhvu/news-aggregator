package dev.linhvu.news_aggregator.ingestion;

/**
 * Một item đã parse, TRƯỚC khi chuẩn hoá URL. `publishedAt` còn nguyên văn.
 * `excerpt` đã qua `FeedExcerpt.clean` và có thể là `null`.
 */
record ParsedItem(String title, String link, String publishedAt, String excerpt) {
}
