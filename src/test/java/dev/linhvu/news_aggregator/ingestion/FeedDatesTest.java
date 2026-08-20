package dev.linhvu.news_aggregator.ingestion;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * So với giá trị TUYỆT ĐỐI, không so tương đối.
 *
 * Đây là chế độ hỏng nguy hiểm nhất của cả Phase 2 (TDD §18): parse sai múi giờ
 * KHÔNG ném exception nào — nó chỉ làm bài nằm sai chỗ trong danh sách, một
 * cách trông rất hợp lý. Test kiểu `assertThat(parsed).isBefore(now())` sẽ xanh
 * với một parser lệch 7 tiếng.
 */
class FeedDatesTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			// RFC-822 / RFC-1123 — RSS 2.0
			"Tue, 04 Aug 2026 14:40:43 GMT      | 2026-08-04T14:40:43Z",
			"Tue, 04 Aug 2026 14:40:43 +0000    | 2026-08-04T14:40:43Z",
			"Tue, 04 Aug 2026 21:40:43 +0700    | 2026-08-04T14:40:43Z",
			"Tue, 04 Aug 2026 09:40:43 -0500    | 2026-08-04T14:40:43Z",
			// RFC-822 cho phép ngày MỘT chữ số và cho phép bỏ giây. Feed thật
			// dùng cả hai; không nhận được thì bài mất `publishedAt` một cách
			// im lặng, mà im lặng ở đây nghĩa là bài không bao giờ lên trang.
			"Tue, 4 Aug 2026 14:40:43 GMT       | 2026-08-04T14:40:43Z",
			"Tue, 04 Aug 2026 14:40 GMT         | 2026-08-04T14:40:00Z",
			// RFC-3339 / ISO-8601 — Atom
			"2026-08-04T14:40:43Z               | 2026-08-04T14:40:43Z",
			"2026-08-04T21:40:43+07:00          | 2026-08-04T14:40:43Z",
			"2026-08-04T14:40:43.123Z           | 2026-08-04T14:40:43Z",
	})
	void parses_both_standards_correctly(String input, String expected) {
		assertThat(FeedDates.parse(input.trim())).contains(expected.trim());
	}

	@ParameterizedTest
	@ValueSource(strings = { "hôm qua", "04/08/2026", "", "   ", "not a date" })
	void returns_empty_when_it_cannot_parse(String bad) {
		assertThat(FeedDates.parse(bad)).isEmpty();
	}

	@Test
	void returns_empty_when_null() {
		assertThat(FeedDates.parse(null)).isEqualTo(Optional.empty());
	}

	/**
	 * LÝ DO TỒN TẠI của việc quy về UTC, phát biểu thành assertion.
	 *
	 * `publishedAt` là sort key của `gsi-recent`, và DynamoDB so sánh sort key
	 * kiểu String theo THỨ TỰ BYTE — nó không biết gì về múi giờ. Chừng nào mọi
	 * giá trị đều là ISO-8601 UTC thì thứ tự chuỗi trùng thứ tự thời gian; lưu
	 * nguyên văn `+0700` vào đó thì AP1 ("N bài mới nhất") trả sai thứ tự mà
	 * không có lỗi nào ở đâu cả.
	 *
	 * Ba mốc dưới đây cố ý viết ở ba múi giờ khác nhau và cố ý KHÔNG cùng thứ tự
	 * với phần giờ nguyên văn của chúng.
	 */
	@Test
	void string_order_matches_chronological_order() {
		String earliest = FeedDates.parse("Tue, 04 Aug 2026 21:40:43 +0700").orElseThrow();
		String middle = FeedDates.parse("2026-08-04T15:00:00Z").orElseThrow();
		String latest = FeedDates.parse("Tue, 04 Aug 2026 11:00:00 -0500").orElseThrow();

		assertThat(earliest).isLessThan(middle);
		assertThat(middle).isLessThan(latest);
	}
}
