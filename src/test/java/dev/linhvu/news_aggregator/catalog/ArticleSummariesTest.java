package dev.linhvu.news_aggregator.catalog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

import dev.linhvu.news_aggregator.catalog.api.ArticlePage;
import dev.linhvu.news_aggregator.catalog.api.ArticleSummaryDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * `toPage` là chỗ DUY NHẤT quyết định `nextCursor`, và nó suýt không có test
 * nào. Đã đo trước khi viết class này: BA mutation đi qua trọn vẹn 298 test mà
 * không làm đỏ một dòng — luôn phát cursor, bỏ đọc thừa một, và lấy cursor từ
 * phần tử thừa. Mutation thứ hai làm người đọc KHÔNG BAO GIỜ tải được trang 2,
 * tức giết sạch tính năng của cả phase, mà vẫn ship xanh.
 *
 * Test đơn vị trần — không Spring, không container: `toPage` là hàm thuần. Cho
 * nó một Spring context là đổi 20 mili giây lấy 10 giây mà không kiểm thêm gì.
 *
 * KHÔNG khẳng định gì về `summary` ở đây: ngoài Togglz context thì
 * `showSummary()` fail-closed về false, nhưng đó là hợp đồng của
 * `TogglzGateTest`, không phải của phân trang.
 */
class ArticleSummariesTest {

	private static final int LIMIT = 3;

	@Test
	void an_over_read_element_means_there_is_a_next_page() {
		ArticlePage page = ArticleSummaries.toPage(articles(LIMIT + 1), LIMIT);

		// Phần tử thứ `limit + 1` KHÔNG được trả về — nó chỉ trả lời câu "còn nữa
		// không". Rò nó ra ngoài là trang nào cũng thừa một bài.
		assertThat(page.items()).extracting(ArticleSummaryDto::id)
				.containsExactly("a-0", "a-1", "a-2");
		assertThat(page.nextCursor()).isNotNull();
	}

	/**
	 * Cursor trỏ vào phần tử CUỐI CÙNG ĐƯỢC TRẢ VỀ, không phải phần tử thừa.
	 * Trỏ nhầm sang phần tử thừa thì trang sau bắt đầu SAU nó — mất đúng một bài
	 * mỗi trang, và mất im lặng.
	 */
	@Test
	void the_cursor_points_at_the_last_RETURNED_article_not_the_over_read_one() {
		ArticlePage page = ArticleSummaries.toPage(articles(LIMIT + 1), LIMIT);

		assertThat(ArticleCursor.decode(page.nextCursor()).articleId())
				.isEqualTo("a-2");
	}

	/** Không có phần tử thừa ⇒ HẾT BÀI. Đây là tín hiệu duy nhất báo hết. */
	@Test
	void without_an_over_read_element_there_is_no_next_cursor() {
		ArticlePage page = ArticleSummaries.toPage(articles(LIMIT), LIMIT);

		assertThat(page.items()).hasSize(LIMIT);
		assertThat(page.nextCursor()).isNull();
	}

	/** Trang chưa đầy cũng là hết bài, không phải lỗi. */
	@Test
	void a_partial_page_is_the_last_page() {
		ArticlePage page = ArticleSummaries.toPage(articles(1), LIMIT);

		assertThat(page.items()).hasSize(1);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void an_empty_page_has_no_next_cursor() {
		ArticlePage page = ArticleSummaries.toPage(List.of(), LIMIT);

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	private static List<Article> articles(int count) {
		Instant base = Instant.parse("2026-08-20T12:00:00Z");
		return IntStream.range(0, count).mapToObj(i -> {
			Article a = new Article();
			a.setArticleId("a-" + i);
			a.setPublishedAt(base.minus(i, ChronoUnit.MINUTES).toString());
			a.setTitle("Article " + i);
			a.setCanonicalUrl("https://example.test/" + i);
			a.setSourceName("Source");
			return a;
		}).toList();
	}
}
