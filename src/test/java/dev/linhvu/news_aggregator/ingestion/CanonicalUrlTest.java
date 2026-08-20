package dev.linhvu.news_aggregator.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalUrlTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"https://Spring.io/blog/post          | https://spring.io/blog/post",
			"https://spring.io/blog/post/         | https://spring.io/blog/post",
			"https://spring.io/                   | https://spring.io/",
			"https://spring.io                    | https://spring.io/",
			"https://spring.io/blog/post#intro    | https://spring.io/blog/post",
			"https://spring.io/blog/post?utm_source=rss&utm_medium=feed | https://spring.io/blog/post",
			"https://spring.io/blog/post?fbclid=x | https://spring.io/blog/post",
			"https://spring.io/blog?p=123         | https://spring.io/blog?p=123",
			"https://spring.io:443/blog/post      | https://spring.io/blog/post",
			"http://spring.io:80/blog/post        | http://spring.io/blog/post",
			"https://spring.io:8443/blog/post     | https://spring.io:8443/blog/post",
			"https://spring.io/blog/../post       | https://spring.io/post",
	})
	void normalises_correctly(String input, String expected) {
		assertThat(CanonicalUrl.normalise(input.trim())).isEqualTo(expected.trim());
	}

	/**
	 * KHÔNG ép http → https (TDD §17 #14). Chọn theo cái hỏng nào NHÌN THẤY
	 * ĐƯỢC: một nguồn chuyển sang https sinh bản trùng hiện thành hai dòng cùng
	 * tiêu đề trên trang; còn ép https với một nguồn http-only tạo link 404 chỉ
	 * lộ khi có người bấm vào.
	 */
	@Test
	void does_not_force_http_into_https() {
		assertThat(CanonicalUrl.normalise("http://example.test/a"))
				.isEqualTo("http://example.test/a");
	}

	@Test
	void keeps_params_that_are_not_tracking() {
		assertThat(CanonicalUrl.normalise("https://a.test/x?p=1&utm_source=rss&q=2"))
				.isEqualTo("https://a.test/x?p=1&q=2");
	}

	/**
	 * Tên param được so KHÔNG PHÂN BIỆT HOA THƯỜNG. Feed thật gửi cả
	 * `?UTM_SOURCE=` lẫn `?utm_source=`; nếu chỉ bắt chữ thường thì cùng một bài
	 * qua hai đường viết hoa khác nhau sẽ ra hai `articleId` khác nhau — tức là
	 * trùng lặp hiện lên trang, đúng thứ chuẩn hoá sinh ra để chặn.
	 */
	@Test
	void strips_tracking_params_case_insensitively() {
		assertThat(CanonicalUrl.normalise("https://a.test/x?UTM_Source=rss&FBCLID=y&p=1"))
				.isEqualTo("https://a.test/x?p=1");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/blog/post", "not-a-url", "ftp://a.test/x", "" })
	void rejects_invalid_urls(String bad) {
		assertThatThrownBy(() -> CanonicalUrl.normalise(bad))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void the_article_id_is_32_hex_chars_and_stable() {
		String id = CanonicalUrl.articleId("https://spring.io/blog/post");

		assertThat(id).hasSize(32).matches("[0-9a-f]{32}");
		assertThat(CanonicalUrl.articleId("https://spring.io/blog/post"))
				.isEqualTo(id);
	}

	@Test
	void different_urls_give_different_ids() {
		assertThat(CanonicalUrl.articleId("https://a.test/1"))
				.isNotEqualTo(CanonicalUrl.articleId("https://a.test/2"));
	}

	/**
	 * TÍNH CHẤT MÀ CẢ CLASS NÀY SINH RA ĐỂ CÓ, và là thứ duy nhất `ingestion`
	 * thật sự dựa vào: hai URL thô KHÁC NHAU nhưng cùng trỏ một bài phải cho
	 * cùng một `articleId`, để lượt ingest thứ hai bị dedupe chặn thay vì đẻ
	 * dòng trùng lên trang.
	 *
	 * Các test ở trên KHÔNG bắt được nếu nối hai hàm lại sai: `normalises_correctly`
	 * chỉ kiểm `normalise`, còn `article_id_...` chỉ kiểm `articleId` trên
	 * chuỗi đã chuẩn. Không cái nào nói rằng người gọi phải chuẩn hoá TRƯỚC khi
	 * băm — mà quên đúng bước đó là cách hỏng dễ xảy ra nhất.
	 */
	@Test
	void equivalent_urls_give_the_same_article_id() {
		String canonical = CanonicalUrl.articleId(
				CanonicalUrl.normalise("https://spring.io/blog/post"));

		for (String equivalent : new String[] {
				"https://Spring.io/blog/post",
				"https://spring.io/blog/post/",
				"https://spring.io/blog/post#intro",
				"https://spring.io:443/blog/post",
				"https://spring.io/blog/post?utm_source=rss",
				"https://spring.io/blog/sub/../post",
		}) {
			assertThat(CanonicalUrl.articleId(CanonicalUrl.normalise(equivalent)))
					.as("phải cùng articleId với bản chuẩn: %s", equivalent)
					.isEqualTo(canonical);
		}
	}
}
