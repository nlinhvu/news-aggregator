package dev.linhvu.news_aggregator.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * `catalog` chỉ được thấy `ingestion :: events`, không thấy `CanonicalUrl` —
 * đó là internal của `ingestion`. Nên phép suy id được lặp lại ở đây.
 *
 * Trùng lặp có ý thức, cùng loại với việc schema DynamoDB bị chép ở ba nơi
 * (master §9): gộp lại đòi hỏi đúng cái phụ thuộc mà ranh giới module cấm.
 * Giảm nhẹ bằng test `hai_module_suy_ra_cung_mot_id`.
 */
final class CatalogIds {

	private CatalogIds() {
	}

	static String articleId(String canonicalUrl) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonicalUrl.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 32);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("JVM không có SHA-256", ex);
		}
	}
}
