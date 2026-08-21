package dev.linhvu.news_aggregator.catalog;

import java.util.List;

import dev.linhvu.news_aggregator.catalog.api.InvalidCursorException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ArticleCursorTest {

	@Test
	void encode_then_decode_returns_the_original_pair() {
		ArticleCursor original = new ArticleCursor("2026-07-30T00:00:00Z", "3f2ac91");

		assertThat(ArticleCursor.decode(original.encode())).isEqualTo(original);
	}

	/**
	 * Base64URL, KHÔNG base64 thường: cursor đi trong query string, và `+` với
	 * `/` của bảng chữ cái thường sẽ bị hiểu thành khoảng trắng và dấu phân cách
	 * đường dẫn. Không padding vì `=` cũng phải escape.
	 */
	@Test
	void the_encoded_string_is_safe_in_a_query_string() {
		String encoded = new ArticleCursor("2026-07-30T00:00:00Z", "3f2ac91").encode();

		assertThat(encoded).matches("[A-Za-z0-9_-]+");
	}

	@Test
	void throws_when_the_cursor_is_not_base64() {
		assertThatThrownBy(() -> ArticleCursor.decode("@@@"))
				.isInstanceOf(InvalidCursorException.class);
	}

	/**
	 * Chuỗi base64url HỢP LỆ nhưng giải ra không có dấu phân cách. Đây là nhánh
	 * mà một test chỉ ném rác không-base64 vào sẽ bỏ sót: `noseparator`
	 * decode được sạch sẽ, và nếu thiếu vế kiểm `|` thì `substring` ném
	 * `StringIndexOutOfBoundsException` — một 500 thay vì một 400.
	 */
	@Test
	void throws_when_the_cursor_has_no_separator() {
		assertThatThrownBy(() -> ArticleCursor.decode("noseparator"))
				.isInstanceOf(InvalidCursorException.class);
	}

	@Test
	void an_absent_cursor_is_not_an_error() {
		assertThat(ArticleCursor.decodeOrNull(null)).isNull();
		assertThat(ArticleCursor.decodeOrNull("")).isNull();
	}

	/**
	 * `?cursor=` với giá trị rác vẫn phải là 400. Nếu `decodeOrNull` nuốt luôn
	 * mọi thứ hỏng thành `null` thì người đọc âm thầm bị đưa về trang đầu — danh
	 * sách nhảy về đỉnh mà không ai hiểu vì sao.
	 */
	@Test
	void decodeOrNull_still_throws_when_the_cursor_is_present_but_garbage() {
		assertThatThrownBy(() -> ArticleCursor.decodeOrNull("@@@"))
				.isInstanceOf(InvalidCursorException.class);
	}

	@Test
	void from_last_article_takes_exactly_the_final_element() {
		Article first = article("p-002", "2026-08-20T12:00:00Z");
		Article last = article("p-001", "2026-08-20T11:00:00Z");

		String encoded = ArticleCursor.fromLastArticle(List.of(first, last));

		assertThat(ArticleCursor.decode(encoded))
				.isEqualTo(new ArticleCursor("2026-08-20T11:00:00Z", "p-001"));
	}

	@Test
	void from_last_article_returns_null_for_an_empty_page() {
		assertThat(ArticleCursor.fromLastArticle(List.of())).isNull();
	}

	private static Article article(String id, String publishedAt) {
		Article a = new Article();
		a.setArticleId(id);
		a.setPublishedAt(publishedAt);
		return a;
	}
}
