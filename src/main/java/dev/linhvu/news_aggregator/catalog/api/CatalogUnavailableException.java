package dev.linhvu.news_aggregator.catalog.api;

/**
 * `catalog` không trả lời được — hỏng TẠM THỜI, không phải dữ liệu sai.
 *
 * Tồn tại để chỗ gọi phân biệt được "không đọc được bài" với mọi lỗi lập trình
 * khác, mà không phải bắt `RuntimeException` trần. `MyFeedController` dịch nó
 * thành `503`; bắt rộng hơn sẽ biến một NullPointerException thành "dịch vụ
 * tạm ngừng" và giấu mất một bug thật.
 */
public class CatalogUnavailableException extends RuntimeException {

	public CatalogUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
