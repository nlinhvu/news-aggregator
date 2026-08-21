package dev.linhvu.news_aggregator.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import dev.linhvu.news_aggregator.catalog.api.InvalidCursorException;

/**
 * Vị trí của người đọc trong một danh sách, dưới dạng cặp
 * `(publishedAt, articleId)` — xem [ADR-0022].
 *
 * PACKAGE-PRIVATE có chủ ý. `personalization` nhận cursor từ query param và đưa
 * thẳng vào `ArticleCatalog.recentBySources` mà không bao giờ mở ra; nếu nó tự
 * mã hoá được thì hai đường đọc feed sẽ trôi khỏi nhau đúng như `ArticleSummaries`
 * đã ngăn được ở Phase 7 — cùng một lỗi, cùng một cách chữa.
 *
 * `articleId` KHÔNG phải trang trí: `FeedDates` cắt `publishedAt` về giây và một
 * số feed chỉ ghi ngày, nên bài trùng dấu thời gian là chuyện đang xảy ra thật
 * (đo trên prod 2026-08-20: cụm lớn nhất 3 bài). Thiếu khoá phụ này thì ranh
 * giới trang rơi vào giữa cụm trùng sẽ làm bài lặp hoặc mất — im lặng.
 *
 * Mã hoá base64url là để SPA không bị cám dỗ tự dựng cursor, KHÔNG phải để giấu:
 * cả hai thành phần đều đã nằm trong response.
 */
record ArticleCursor(String publishedAt, String articleId) {

	/**
	 * An toàn làm ký tự phân cách: `articleId` là 32 ký tự hex (`CatalogIds` cắt
	 * SHA-256) và `publishedAt` là ISO-8601 UTC. Không giá trị nào chứa `|`.
	 */
	private static final String SEPARATOR = "|";

	String encode() {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
				(publishedAt + SEPARATOR + articleId).getBytes(StandardCharsets.UTF_8));
	}

	/** Cursor VẮNG MẶT ⇒ `null` (từ đầu danh sách). Cursor CÓ MẶT nhưng hỏng ⇒ ném. */
	static ArticleCursor decodeOrNull(String raw) {
		return (raw == null || raw.isBlank()) ? null : decode(raw);
	}

	static ArticleCursor decode(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new InvalidCursorException("cursor rỗng");
		}
		String decoded;
		try {
			decoded = new String(Base64.getUrlDecoder().decode(raw),
					StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidCursorException("cursor không phải base64url", ex);
		}
		int sep = decoded.indexOf(SEPARATOR);
		// `sep <= 0` phủ cả "không có dấu phân cách" lẫn "phân cách ở vị trí 0"
		// (publishedAt rỗng). Vế sau phủ "phân cách ở cuối" (articleId rỗng).
		// Thiếu cả khối này thì `substring` ném StringIndexOutOfBoundsException —
		// một 500 thay vì một 400.
		if (sep <= 0 || sep == decoded.length() - 1) {
			throw new InvalidCursorException("cursor thiếu dấu phân cách");
		}
		return new ArticleCursor(decoded.substring(0, sep), decoded.substring(sep + 1));
	}

	/** `null` khi trang rỗng — không có vị trí nào để nối tiếp. */
	static String fromLastArticle(List<Article> page) {
		if (page.isEmpty()) {
			return null;
		}
		Article last = page.getLast();
		return new ArticleCursor(last.getPublishedAt(), last.getArticleId()).encode();
	}
}
