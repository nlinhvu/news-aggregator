package dev.linhvu.news_aggregator.ingestion;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Parse ngày của cả hai định dạng feed và quy về ISO-8601 UTC.
 *
 * `publishedAt` là sort key của `gsi-recent`, và chuỗi ISO-8601 UTC có thứ tự
 * chuỗi trùng thứ tự thời gian — đó là lý do phải chuẩn hoá về UTC ở đây chứ
 * không lưu nguyên văn. Xem `FeedDatesTest#thu_tu_chuoi_trung_thu_tu_thoi_gian`.
 *
 * Cắt về giây: mili giây không thêm thông tin nào cho một trang tin và chỉ làm
 * hai bài cùng giây trông như khác nhau.
 */
final class FeedDates {

	private static final List<DateTimeFormatter> FORMATTERS = List.of(
			DateTimeFormatter.RFC_1123_DATE_TIME,   // RSS 2.0
			DateTimeFormatter.ISO_OFFSET_DATE_TIME, // Atom
			DateTimeFormatter.ISO_INSTANT);

	private FeedDates() {
	}

	static Optional<String> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String trimmed = raw.trim();
		for (DateTimeFormatter formatter : FORMATTERS) {
			try {
				Instant instant = Instant.from(formatter.parse(trimmed));
				return Optional.of(instant.truncatedTo(ChronoUnit.SECONDS).toString());
			}
			// CHỈ `DateTimeException`, không multi-catch với
			// `DateTimeParseException`: cái sau là lớp con của cái trước nên
			// viết cả hai là lỗi compile. Bắt lớp cha là đủ — nó phủ cả lỗi
			// cú pháp (`formatter.parse`) lẫn lỗi thiếu trường khi dựng
			// `Instant` (`Instant.from` trên một chuỗi không có offset).
			catch (DateTimeException ignored) {
				// thử formatter tiếp theo
			}
		}
		return Optional.empty();
	}
}
