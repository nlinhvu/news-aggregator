package dev.linhvu.news_aggregator.catalog;

import java.util.ArrayList;
import java.util.List;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.*;

/**
 * Phân trang trên đường MỘT-QUERY (`gsi-recent-v2`).
 *
 * Bảng RIÊNG, không dùng chung `articles` với `ArticleRepositoryTest`.
 * `FlociTestConfiguration` ghi rõ: mỗi Spring context lấy một container Floci
 * riêng, nên hai class có CÙNG cấu hình context dùng chung container VÀ dùng
 * chung dữ liệu. Đổi `news.catalog.table-name` vừa tách khoá cache context vừa
 * tách bảng, nên 45 bài ở đây không lọt vào assertion đếm chính xác của test kia
 * và ngược lại. Giá phải trả là một container Floci nữa — vài giây, đổi lấy việc
 * hai bộ test không phá nhau.
 */
@SpringBootTest
@Import(FlociTestConfiguration.class)
@TestPropertySource(properties = "news.catalog.table-name=articles-paging")
class ArticlePagingRepositoryTest {

	private static final int LIMIT = 20;

	@Autowired
	ArticleRepository repository;

	@BeforeEach
	void loadFixtures() {
		PagingFixtures.all().forEach(repository::save);
	}

	@Test
	void the_first_page_returns_exactly_limit_articles_newest_first() {
		assertThat(repository.findRecent(LIMIT, null))
				.extracting(Article::getArticleId)
				.containsExactlyElementsOf(PagingFixtures.idsInOrder().subList(0, LIMIT));
	}

	/**
	 * TEST QUYẾT ĐỊNH CỦA CẢ PHASE.
	 *
	 * Cuộn hết bằng cursor rồi so **toàn bộ chuỗi** với fixture. Một test chỉ
	 * kiểm "trang 2 có 20 bài" sẽ XANH trong khi vẫn sót bài ở ranh giới — và
	 * sót bài là chế độ hỏng duy nhất đáng sợ của phân trang, vì danh sách vẫn
	 * hiện ra bình thường, chỉ là thiếu.
	 */
	@Test
	void scrolling_to_the_end_never_repeats_never_skips_and_keeps_the_order() {
		assertThat(scrollAll(LIMIT))
				.containsExactlyElementsOf(PagingFixtures.idsInOrder());
	}

	/**
	 * Ranh giới trang 1/2 rơi vào GIỮA cụm 3 bài trùng `publishedAt` (chỉ số 19,
	 * 20, 21 — xem `PagingFixtures`). Đây là kịch bản mà một cursor chỉ mang
	 * `publishedAt` sẽ hỏng: dùng `<` thì mất hai bài đuôi cụm, dùng `<=` mà
	 * không lọc lại thì lặp lại bài đầu cụm.
	 */
	@Test
	void a_duplicate_publishedAt_cluster_across_the_page_boundary_loses_no_article() {
		List<Article> cluster = PagingFixtures.all()
				.subList(PagingFixtures.CLUSTER_FROM, PagingFixtures.CLUSTER_TO + 1);

		// Hai vế canh fixture, và cả hai đều phải nói về NỘI DUNG. `hasSize(3)`
		// một mình vô nghĩa: `subList` của hai hằng số luôn ra 3 phần tử dù cụm
		// trùng đã biến mất — đã đo bằng mutation, xoá hẳn cụm mà test vẫn xanh.
		assertThat(cluster).extracting(Article::getPublishedAt)
				.as("fixture phải giữ cụm 3 bài TRÙNG publishedAt")
				.hasSize(3)
				.containsOnly(cluster.getFirst().getPublishedAt());

		// Trùng thôi chưa đủ — cụm phải VẮT QUA ranh giới trang. Đổi `LIMIT` cho
		// cả cụm lọt gọn vào trang 1 là xoá sạch giá trị của test mà không làm
		// đỏ dòng nào.
		assertThat(PagingFixtures.CLUSTER_FROM)
				.as("cụm phải bắt đầu TRƯỚC ranh giới trang").isLessThan(LIMIT);
		assertThat(PagingFixtures.CLUSTER_TO)
				.as("cụm phải kéo qua ranh giới trang").isGreaterThanOrEqualTo(LIMIT);

		assertThat(scrollAll(LIMIT)).containsSubsequence(
				cluster.stream().map(Article::getArticleId).toArray(String[]::new));
	}

	/** Trang cuối không đầy: 45 bài, `limit` 20 ⇒ 20 + 20 + 5. */
	@Test
	void the_last_page_is_partial_and_has_no_next_page() {
		assertThat(scrollAll(LIMIT)).hasSize(PagingFixtures.ARTICLE_COUNT);
	}

	/**
	 * Cuộn hết bằng đúng cơ chế mà `ArticleSummaries.toPage` sẽ dùng ở Task 5:
	 * đọc `limit + 1`, trả `limit`, còn thừa thì phát cursor.
	 *
	 * Chốt chặn 100 vòng biến một bug vòng lặp vô hạn (cursor không tiến) thành
	 * một test ĐỎ thay vì một lần build treo cho tới khi ai đó bấm huỷ.
	 */
	private List<String> scrollAll(int limit) {
		List<String> collected = new ArrayList<>();
		ArticleCursor watermark = null;
		for (int round = 0; round < 100; round++) {
			List<Article> overRead = repository.findRecent(limit + 1, watermark);
			boolean hasMore = overRead.size() > limit;
			List<Article> page = hasMore ? overRead.subList(0, limit) : overRead;
			page.forEach(a -> collected.add(a.getArticleId()));
			if (!hasMore) {
				return collected;
			}
			Article last = page.getLast();
			watermark = new ArticleCursor(last.getPublishedAt(), last.getArticleId());
		}
		throw new IllegalStateException("cuộn quá 100 trang — cursor không tiến");
	}
}
