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
 * Phân trang trên CẢ HAI đường đọc: một-query (`gsi-recent-v2`) và fan-out
 * (`gsi-by-source`). Cùng một kho 45 bài, nên hai nhóm test đối chiếu được
 * với nhau — một bug chỉ có ở một đường sẽ lộ ra ở chênh lệch giữa hai nhóm.
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
	 * TEST QUYẾT ĐỊNH của đường fan-out, và là bản đối chiếu của
	 * `scrolling_to_the_end_never_repeats_never_skips_and_keeps_the_order` ở
	 * đường một-query.
	 *
	 * Chọn CẢ HAI nguồn thì tập bài phải bằng đúng toàn bộ fixture — cùng một
	 * kho, đi qua hai index khác nhau. Đây chính là phép thử mà walkthrough
	 * slice 2 bắt làm bằng tay trên prod ("hai con số phải bằng nhau"), viết lại
	 * ở tầng test.
	 *
	 * So `containsExactlyElementsOf` chứ không `containsExactlyInAnyOrder`:
	 * merge-sort sai thứ tự vẫn cho ra đúng tập phần tử.
	 */
	@Test
	void fan_out_scrolling_to_the_end_never_repeats_never_skips_and_keeps_the_order() {
		List<String> queryOrder = List.of(PagingFixtures.SOURCE_A, PagingFixtures.SOURCE_B);

		assertThat(crossSourceTie(queryOrder))
				.as("fixture phải có cụm trùng VẮT QUA hai nguồn, bài đứng trước "
						+ "thuộc nguồn được query SAU")
				.isNotEmpty();

		assertThat(scrollAllBySources(queryOrder, LIMIT))
				.containsExactlyElementsOf(PagingFixtures.idsInOrder());
	}

	/**
	 * Cặp bài TRÙNG `publishedAt` nằm ở HAI nguồn khác nhau, với bài đứng TRƯỚC
	 * trong thứ tự trang thuộc nguồn được query SAU.
	 *
	 * Đây là cấu hình DUY NHẤT phân biệt được `PAGE_ORDER` thật với một
	 * comparator chỉ so `publishedAt`: `Stream.sorted` là sort ỔN ĐỊNH, nên khi
	 * các phần tử tới tay nó đã đúng thứ tự sẵn thì khoá phụ `articleId` có hay
	 * không cũng cho ra cùng kết quả. Đã đo bằng mutation: xoá `thenComparing`
	 * mà cả suite vẫn xanh, cho tới khi fixture có cặp này.
	 *
	 * Đọc `sourceId` và `publishedAt` THẬT chứ không so hai hằng số chỉ số — một
	 * guard suy từ `CROSS_FROM`/`CROSS_TO` sẽ xanh cả khi cụm đã biến mất.
	 */
	private static List<Article> crossSourceTie(List<String> queryOrder) {
		List<Article> all = PagingFixtures.all();
		for (int i = 0; i + 1 < all.size(); i++) {
			Article first = all.get(i);
			Article second = all.get(i + 1);
			if (first.getPublishedAt().equals(second.getPublishedAt())
					&& queryOrder.indexOf(first.getSourceId())
							> queryOrder.indexOf(second.getSourceId())) {
				return List.of(first, second);
			}
		}
		return List.of();
	}

	/**
	 * MỘT nguồn duy nhất, ranh giới trang cắt ngay GIỮA cụm trùng `publishedAt`.
	 * Đây là nhánh mà `ExclusiveStartKey` không cứu được, vì nó SO SÁNH GIÁ TRỊ.
	 *
	 * Một nguồn là chỗ DUY NHẤT lộ ra chế độ hỏng thật: key condition dùng `<=`
	 * nên mỗi query đọc lại chính bài watermark, và bài đó bị lọc đi. Nhiều
	 * nguồn thì phần dư của nguồn khác lấp chỗ trống ấy; một nguồn thì lượt đọc
	 * thừa-một teo lại còn đúng `limit`, `hasMore` thành false và feed cụt giữa
	 * chừng — không lỗi, không log.
	 *
	 * `LIMIT` chung (20) KHÔNG dựng được kịch bản này: sau watermark nguồn A chỉ
	 * còn 6 bài nên query không chạm trần, và bug biến mất. `limit` ở đây suy TỪ
	 * FIXTURE để ranh giới rơi đúng sau phần tử đầu của cụm.
	 */
	@Test
	void fan_out_a_duplicate_cluster_inside_one_source_loses_no_article() {
		List<String> expected = PagingFixtures.idsOfSources(PagingFixtures.SOURCE_A);
		List<String> cluster = PagingFixtures.idsInOrder()
				.subList(PagingFixtures.CLUSTER_FROM, PagingFixtures.CLUSTER_TO + 1);

		// Đọc NỘI DUNG fixture chứ không suy từ hằng số: cụm phải nằm trọn trong
		// một nguồn, nếu không thì "cụm bên trong một nguồn" ở tên test là lời
		// nói suông và cả hai assertion dưới vẫn xanh.
		assertThat(expected)
				.as("cụm trùng phải nằm TRỌN trong SOURCE_A")
				.containsSubsequence(cluster.toArray(String[]::new));

		// Cắt trang ngay SAU phần tử đầu của cụm ⇒ phần đuôi cụm rơi sang trang sau.
		int limit = expected.indexOf(cluster.getFirst()) + 1;
		// Phải còn trang thứ BA. Gọn trong hai trang thì lượt đọc thứ hai không
		// chạm trần `limit`, và đó đúng là điều kiện làm bug hiện ra.
		assertThat(expected.size())
				.as("nguồn A phải đủ dài cho ba trang").isGreaterThan(2 * limit);

		assertThat(scrollAllBySources(List.of(PagingFixtures.SOURCE_A), limit))
				.containsExactlyElementsOf(expected);
	}

	/**
	 * Cửa sổ đọc của MỘT nguồn không bao giờ kết thúc GIỮA một cụm trùng
	 * `publishedAt` — nó nới ra tới hết cụm.
	 *
	 * Canh CƠ CHẾ chứ không canh triệu chứng, và đó là lựa chọn BẮT BUỘC chứ
	 * không phải cho tiện: triệu chứng (sót bài) chỉ xuất hiện khi thứ tự BÊN
	 * TRONG cụm khác `PAGE_ORDER`, mà Floci luôn trả cụm theo đúng `articleId`
	 * giảm dần. Trên Floci, một bản dựng cắt đúng `limit` vẫn vớ được phần ĐẦU
	 * cụm nên không sót gì; trên DynamoDB thật thứ tự trong cụm là bất kỳ nên
	 * tập lấy về là tập con BẤT KỲ, và những bài nằm TRÊN watermark mà chưa được
	 * đọc thì mất vĩnh viễn.
	 *
	 * Đo trên `dev` 2026-08-21: `spring-blog` 16 bài với cụm 7 bài cùng
	 * `2026-08-20T00:00:00Z`; `limit` 3→13 bài, 4→14, 5→15, 6→16. Sót khi
	 * `cỡ cụm > limit + 1`, tất định.
	 *
	 * So KHÔNG theo thứ tự: thứ tự bên trong cụm là thứ test này cố ý không dựa
	 * vào. Điều phải đúng là cụm về ĐỦ.
	 */
	@Test
	void the_read_window_never_ends_inside_a_duplicate_cluster() {
		List<String> fromA = PagingFixtures.idsOfSources(PagingFixtures.SOURCE_A);
		List<String> cluster = PagingFixtures.idsInOrder()
				.subList(PagingFixtures.CLUSTER_FROM, PagingFixtures.CLUSTER_TO + 1);
		int clusterStart = fromA.indexOf(cluster.getFirst());
		assertThat(clusterStart)
				.as("cụm phải nằm trong SOURCE_A, nếu không test này vô nghĩa")
				.isNotNegative();

		// `limit` cắt ngay SAU phần tử đầu của cụm ⇒ ranh giới rơi vào GIỮA cụm.
		assertThat(repository.queryOneSource(
						PagingFixtures.SOURCE_A, clusterStart + 1, null))
				.extracting(Article::getArticleId)
				.as("phải nới tới hết cụm, không dừng giữa chừng")
				.containsExactlyInAnyOrderElementsOf(
						fromA.subList(0, clusterStart + cluster.size()));
	}

	/**
	 * Ranh giới SẠCH thì KHÔNG nới.
	 *
	 * Thiếu vế này thì "nới tới hết cụm" hoá thành "đọc cả nguồn" mà vẫn xanh —
	 * và chi phí đọc mỗi trang lại phụ thuộc độ sâu, đúng thứ ADR-0022 driver #2
	 * đặt ra để tránh.
	 */
	@Test
	void a_clean_boundary_reads_exactly_limit() {
		List<String> fromA = PagingFixtures.idsOfSources(PagingFixtures.SOURCE_A);
		int clusterStart = fromA.indexOf(PagingFixtures.idsInOrder()
				.get(PagingFixtures.CLUSTER_FROM));

		// Cắt NGAY TRƯỚC cụm: phần tử cuối cửa sổ khác `publishedAt` với bài kế tiếp.
		assertThat(repository.queryOneSource(PagingFixtures.SOURCE_A, clusterStart, null))
				.extracting(Article::getArticleId)
				.containsExactlyInAnyOrderElementsOf(fromA.subList(0, clusterStart));
	}

	/**
	 * Tập rỗng = TẤT CẢ nguồn, và nó uỷ quyền cho `findRecent` — tức phân trang
	 * cũng phải đi theo. Một bản dựng quên truyền cursor xuống nhánh uỷ quyền sẽ
	 * trả mãi trang đầu, và `scrollAllBySources` sẽ chạm chốt chặn 100 vòng.
	 */
	@Test
	void fan_out_an_empty_set_can_still_scroll_to_the_end() {
		assertThat(scrollAllBySources(List.of(), LIMIT))
				.containsExactlyElementsOf(PagingFixtures.idsInOrder());
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

	/** Bản fan-out của {@link #scrollAll(int)} — cùng cơ chế đọc thừa-một. */
	private List<String> scrollAllBySources(List<String> sourceIds, int limit) {
		List<String> collected = new ArrayList<>();
		ArticleCursor watermark = null;
		for (int round = 0; round < 100; round++) {
			List<Article> overRead = repository.findRecentBySources(sourceIds, limit + 1, watermark);
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
