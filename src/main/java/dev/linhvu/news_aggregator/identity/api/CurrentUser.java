package dev.linhvu.news_aggregator.identity.api;

import java.util.Optional;

/**
 * Thứ DUY NHẤT module khác được phép biết về danh tính: một `sub`.
 *
 * Cố ý KHÔNG lộ email, token, hay đối tượng `OidcUser`. Module nào cần biết
 * người dùng là ai thì cần đúng một khoá để tra bảng của chính nó — cho thêm
 * là mở đường cho PII rò sang chỗ master §8.4 không cho phép.
 */
public interface CurrentUser {

	/** `Optional.empty()` khi request là ẩn danh — KHÔNG ném exception. */
	Optional<String> sub();
}
