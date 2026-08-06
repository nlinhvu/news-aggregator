package dev.linhvu.news_aggregator.ingestion;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chuẩn hoá URL bài viết và suy ra `articleId`.
 *
 * Class thuần, không Spring, không state — nên nó test được bằng T1 không cần
 * container nào, và đó là chủ ý: đây là chỗ dễ sai nhất mà cũng rẻ nhất để phủ.
 *
 * Hợp đồng dùng: LUÔN `articleId(normalise(raw))`, không bao giờ
 * `articleId(raw)`. Băm thẳng URL thô làm mọi việc chuẩn hoá thành vô nghĩa và
 * dedupe thôi hoạt động — xem `CanonicalUrlTest#url_tuong_duong_cho_cung_mot_article_id`.
 */
final class CanonicalUrl {

	/**
	 * Tham số theo dõi bị bỏ. Danh sách này CỐ Ý ngắn: bỏ nhầm một param có
	 * nghĩa (`?p=123` của WordPress) sẽ gộp hai bài khác nhau thành một, và đó
	 * là mất dữ liệu vĩnh viễn vì dedupe chặn ghi lại.
	 */
	private static final Set<String> TRACKING_PARAMS = Set.of(
			"fbclid", "gclid", "mc_cid", "mc_eid", "ref", "source");

	private CanonicalUrl() {
	}

	static String normalise(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new IllegalArgumentException("URL rỗng");
		}
		URI uri;
		try {
			uri = new URI(rawUrl.trim()).normalize();
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException("URL không hợp lệ: " + rawUrl, ex);
		}
		String scheme = uri.getScheme() == null ? null
				: uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			throw new IllegalArgumentException("chỉ chấp nhận http/https: " + rawUrl);
		}
		if (uri.getHost() == null) {
			throw new IllegalArgumentException("URL không có host: " + rawUrl);
		}

		String host = uri.getHost().toLowerCase(Locale.ROOT);
		int port = uri.getPort();
		boolean defaultPort = port == -1
				|| ("http".equals(scheme) && port == 80)
				|| ("https".equals(scheme) && port == 443);

		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		if (path.isEmpty()) {
			path = "/";
		}

		String query = stripTracking(uri.getRawQuery());

		StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
		if (!defaultPort) {
			sb.append(':').append(port);
		}
		sb.append(path);
		if (query != null && !query.isEmpty()) {
			sb.append('?').append(query);
		}
		// Fragment bị bỏ hoàn toàn — nó không bao giờ tới máy chủ.
		return sb.toString();
	}

	private static String stripTracking(String rawQuery) {
		if (rawQuery == null || rawQuery.isEmpty()) {
			return null;
		}
		return Arrays.stream(rawQuery.split("&"))
				.filter(pair -> !pair.isEmpty())
				.filter(pair -> {
					String key = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
					return !key.startsWith("utm_") && !TRACKING_PARAMS.contains(key);
				})
				.collect(Collectors.joining("&"));
	}

	/**
	 * 32 ký tự hex đầu của sha256 = 128 bit. Va chạm birthday ở ~2^64 item;
	 * trần master §2 cho ~5 triệu item sau 68 năm. 128 bit đưa va chạm ra khỏi
	 * danh sách rủi ro hoàn toàn với giá 16 byte trên item ~2KB.
	 */
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
