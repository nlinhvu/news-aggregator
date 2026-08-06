package dev.linhvu.news_aggregator.ingestion;

/** Một item đã parse, TRƯỚC khi chuẩn hoá URL. `publishedAt` còn nguyên văn. */
record ParsedItem(String title, String link, String publishedAt) {
}
