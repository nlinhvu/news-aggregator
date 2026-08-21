package dev.linhvu.news_aggregator.catalog;

import java.util.List;

import dev.linhvu.news_aggregator.catalog.api.ArticlePage;
import dev.linhvu.news_aggregator.catalog.api.ArticleSummaryDto;
import dev.linhvu.news_aggregator.platform.NewsFeature;

/**
 * Chỗ DUY NHẤT quyết định một `Article` hiện ra ngoài trông như thế nào.
 *
 * Tồn tại vì từ Phase 7 có HAI đường đọc feed — `/api/articles` (công khai,
 * `ArticleController`) và `/api/my/feed` (đã đăng nhập, qua
 * `ArticleCatalog.recentBySources`) — và TDD §7 nói chúng trả CÙNG một hình
 * dạng để frontend chỉ đổi URL. Hai bản sao của phép ánh xạ này sẽ trôi khỏi
 * nhau đúng vào ngày ai đó tắt `AI_SUMMARIZATION`: trang công khai đổi hành vi,
 * feed của người đã đăng nhập thì không — và flag mất luôn ý nghĩa nó tự nhận.
 */
final class ArticleSummaries {

	private ArticleSummaries() {
	}

	/**
	 * Fail-closed: đọc flag lỗi thì coi như OFF và request vẫn thành công. Lỗi
	 * đọc flag KHÔNG được làm hỏng cả trang (TDD §5.4).
	 *
	 * Lớp này chặn ở tầng ĐỌC và bổ sung cho — không thay thế —
	 * `FailClosedDynamoDbStateRepository`, thứ chặn ở tầng DỰNG repository. Cần
	 * cả hai: `FeatureContext.getFeatureManager()` còn ném `IllegalStateException`
	 * khi không tìm ra manager nào, và đó là lỗi nằm ngoài tầm với của state
	 * repository.
	 */
	static boolean showSummary() {
		try {
			return NewsFeature.AI_SUMMARIZATION.isActive();
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * `summary` để null khi flag tắt, và `ArticleSummaryDto` khai
	 * `@JsonInclude(NON_NULL)` nên nó VẮNG MẶT hoàn toàn khỏi JSON — tín hiệu rõ
	 * ràng hơn cho frontend so với một giá trị rỗng.
	 */
	static ArticleSummaryDto toDto(Article article, boolean showSummary) {
		return new ArticleSummaryDto(
				article.getArticleId(), article.getTitle(), article.getPublishedAt(),
				article.getCanonicalUrl(), article.getSourceName(),
				showSummary ? article.getSummary() : null);
	}

	/**
	 * Đóng gói một trang — chỗ DUY NHẤT quyết định `nextCursor`, đặt ngay cạnh
	 * chỗ duy nhất quyết định hình dạng DTO, và vì cùng một lý do.
	 *
	 * `overRead` là kết quả đọc `limit + 1` phần tử. Phần tử thứ `limit + 1` KHÔNG
	 * bao giờ được trả về — nó chỉ tồn tại để trả lời câu "còn nữa không". Đọc
	 * đúng `limit` rồi luôn phát cursor sẽ tạo một lượt tải thừa ở cuối: người
	 * đọc thấy "Đang tải…" rồi "Đã hết bài" cho một request trả về rỗng.
	 *
	 * Đọc flag MỘT lần cho cả trang, không phải mỗi item: giá trị đổi giữa chừng
	 * sẽ cho ra một trang nửa có summary nửa không.
	 */
	static ArticlePage toPage(List<Article> overRead, int limit) {
		boolean hasMore = overRead.size() > limit;
		List<Article> page = hasMore ? overRead.subList(0, limit) : overRead;
		boolean showSummary = showSummary();
		return new ArticlePage(
				page.stream().map(a -> toDto(a, showSummary)).toList(),
				hasMore ? ArticleCursor.fromLastArticle(page) : null);
	}
}
