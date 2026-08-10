package dev.linhvu.news_aggregator.ingestion;

import java.util.regex.Pattern;

/**
 * Biến nội dung thô của một item feed thành đoạn văn xuôi để đưa vào prompt.
 *
 * Không thêm thư viện sanitizer: excerpt KHÔNG BAO GIỜ được render — nó chỉ đi
 * vào prompt. Yêu cầu thật chỉ là bỏ tag để model không đọc phải rác, không
 * phải làm sạch để an toàn khi render. Nếu sau này excerpt được hiển thị thẳng
 * cho người đọc thì kết luận này phải xét lại (TDD §19.1).
 */
final class FeedExcerpt {

	// `<script>`/`<style>` phải bỏ CẢ NỘI DUNG. Bỏ mỗi tag thì JavaScript nằm
	// lại và model đi tóm tắt một đoạn code.
	private static final Pattern SCRIPT_OR_STYLE =
			Pattern.compile("(?is)<(script|style)\\b.*?</\\1>");

	private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private FeedExcerpt() {
	}

	static String clean(String raw, int maxChars) {
		if (raw == null) {
			return null;
		}
		String text = SCRIPT_OR_STYLE.matcher(raw).replaceAll("");
		text = TAG.matcher(text).replaceAll("");
		text = decodeEntities(text);
		text = WHITESPACE.matcher(text).replaceAll(" ").trim();

		if (text.isEmpty()) {
			// null, KHÔNG chuỗi rỗng: enhanced client bỏ hẳn attribute khi null,
			// nên `attribute_exists(excerpt)` của sweep phân biệt được "không có
			// excerpt" với "có excerpt rỗng". Chuỗi rỗng sẽ khiến sweep nhặt về
			// những bài không bao giờ tóm tắt được, mỗi lượt, mãi mãi.
			return null;
		}
		return truncateAtWordBoundary(text, maxChars);
	}

	private static String truncateAtWordBoundary(String text, int maxChars) {
		if (text.length() <= maxChars) {
			return text;
		}
		String head = text.substring(0, maxChars);
		int lastSpace = head.lastIndexOf(' ');
		// Không có khoảng trắng nào trong maxChars ký tự đầu (một "từ" dài bất
		// thường, thường là URL) thì đành cắt cứng — thà cụt còn hơn trả về rỗng.
		return lastSpace > 0 ? head.substring(0, lastSpace) : head;
	}

	// Chỉ những entity thật sự gặp trong feed. Không dựng bảng đầy đủ HTML5:
	// excerpt không được render nên entity lạ sót lại chỉ là nhiễu nhỏ trong
	// prompt, không phải lỗ hổng.
	private static String decodeEntities(String text) {
		return text.replace("&nbsp;", " ")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&apos;", "'")
				// `&amp;` PHẢI đứng cuối: giải mã nó trước sẽ biến `&amp;lt;`
				// thành `&lt;` rồi thành `<` — sai hai lần.
				.replace("&amp;", "&");
	}
}
