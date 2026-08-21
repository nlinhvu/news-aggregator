package dev.linhvu.news_aggregator.catalog.api;

/**
 * Cursor không giải mã được. Nằm ở `catalog.api` chứ không ở package gốc vì
 * `personalization` phải bắt được nó để trả 400 — nó coi cursor là chuỗi đục
 * nên không tự kiểm tra được, và một cursor hỏng đi qua nó phải thành 400 chứ
 * không phải 500.
 *
 * KHÔNG gộp vào {@link CatalogUnavailableException}: cái kia là "phụ thuộc hỏng,
 * thử lại sau" (503), cái này là "người gọi gửi sai" (400). Trộn hai thứ lại là
 * mời người đọc thử lại một request không bao giờ thành công.
 */
public class InvalidCursorException extends RuntimeException {

	public InvalidCursorException(String message) {
		super(message);
	}

	public InvalidCursorException(String message, Throwable cause) {
		super(message, cause);
	}
}
