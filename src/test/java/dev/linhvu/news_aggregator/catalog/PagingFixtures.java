package dev.linhvu.news_aggregator.catalog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixture RIÊNG cho test phân trang — cố ý KHÔNG dùng `/fixtures/articles.json`.
 *
 * Fixture chung có 5 bài và được dựng để nói một điều khác: xen kẽ hai nguồn
 * theo thời gian, và giữ MỘT bài chưa backfill `sourceId`. Nhồi 45 bài vào đó sẽ
 * xoá mất ý định ấy và làm chậm mọi test T2 khác.
 *
 * Sinh bằng code chứ không bằng JSON vì thứ test cần là một TÍNH CHẤT — "có một
 * cụm 3 bài trùng `publishedAt` vắt qua ranh giới trang" — và tính chất đó biến
 * mất khi viết thành 45 phần tử literal: người sửa sau sẽ không biết chỉ số 19,
 * 20, 21 có gì đặc biệt.
 */
final class PagingFixtures {

	/** Đủ ba trang khi `limit = 20`: 20 + 20 + 5. */
	static final int ARTICLE_COUNT = 45;

	/** Ranh giới trang 1/2 (limit = 20) rơi vào GIỮA cụm này. */
	static final int CLUSTER_FROM = 19;
	static final int CLUSTER_TO = 21;

	static final String SOURCE_A = "paging-a";
	static final String SOURCE_B = "paging-b";

	private static final Instant BASE_INSTANT = Instant.parse("2026-08-20T12:00:00Z");

	private PagingFixtures() {
	}

	/**
	 * Danh sách theo thứ tự MỚI → CŨ, tức đúng thứ tự một trang phải trả về.
	 *
	 * `articleId` đếm NGƯỢC (`p-999` xuống `p-955`) để `articleId` giảm dần trùng
	 * với chỉ số tăng dần. Nhờ đó thứ tự mong đợi `(publishedAt desc, articleId
	 * desc)` bằng đúng thứ tự của list này — kể cả BÊN TRONG cụm trùng, nơi
	 * `publishedAt` không phân biệt được gì. Đặt id thuận chiều (`p-000`, `p-001`)
	 * sẽ làm cụm trùng đảo ngược so với phần còn lại và mọi assertion thứ tự
	 * thành ra khó đọc mà không thêm được gì.
	 */
	static List<Article> all() {
		List<Article> result = new ArrayList<>(ARTICLE_COUNT);
		for (int i = 0; i < ARTICLE_COUNT; i++) {
			boolean inCluster = i >= CLUSTER_FROM && i <= CLUSTER_TO;
			// Cả cụm mang CÙNG một dấu thời gian — đúng cái mà `FeedDates` sinh ra
			// khi một feed chỉ ghi ngày.
			int minutesBack = inCluster ? CLUSTER_FROM : i;

			Article a = new Article();
			a.setArticleId("p-%03d".formatted(999 - i));
			a.setListBucket(Article.LIST_BUCKET);
			a.setPublishedAt(BASE_INSTANT.minus(minutesBack, ChronoUnit.MINUTES).toString());
			a.setTitle("Paging article #" + i);
			a.setCanonicalUrl("https://example.test/paging/" + i);
			// Cả cụm trùng nằm trong MỘT nguồn: đó là kịch bản mà đường fan-out
			// phải dùng `<=` rồi lọc lại, và là rủi ro có biên của ADR-0022 §7.
			a.setSourceId(inCluster || i % 2 == 0 ? SOURCE_A : SOURCE_B);
			a.setSourceName(SOURCE_A.equals(a.getSourceId()) ? "Source A" : "Source B");
			result.add(a);
		}
		return List.copyOf(result);
	}

	static List<String> idsInOrder() {
		return all().stream().map(Article::getArticleId).toList();
	}

	static List<String> idsOfSources(String... sourceIds) {
		List<String> wanted = List.of(sourceIds);
		return all().stream()
				.filter(a -> wanted.contains(a.getSourceId()))
				.map(Article::getArticleId)
				.toList();
	}
}
