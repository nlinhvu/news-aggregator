package dev.linhvu.news_aggregator.ingestion;

/**
 * `discovered` — số item parse được, KỂ CẢ trùng (master §11).
 * `added`      — số article thật sự mới. Do `catalog` đếm, xem Task 11.
 * `failed`     — số NGUỒN lỗi, không phải số item lỗi.
 */
public record IngestResult(int discovered, int added, int failed) {
}

