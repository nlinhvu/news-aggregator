package dev.linhvu.news_aggregator.ingestion;

/**
 * Kết quả một lần fetch. `NotModified` chưa dùng ở slice 2 — conditional GET
 * là Task 16 — nhưng kiểu dữ liệu khai sẵn để Task 16 không phải sửa chữ ký.
 */
sealed interface FetchOutcome {

	record Body(byte[] content, String etag, String lastModified) implements FetchOutcome {
	}

	record NotModified(String etag, String lastModified) implements FetchOutcome {
	}
}
