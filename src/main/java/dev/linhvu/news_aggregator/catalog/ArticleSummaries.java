package dev.linhvu.news_aggregator.catalog;

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
	static boolean hienSummary() {
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
	static ArticleSummaryDto toDto(Article article, boolean hienSummary) {
		return new ArticleSummaryDto(
				article.getArticleId(), article.getTitle(), article.getPublishedAt(),
				article.getCanonicalUrl(), article.getSourceName(),
				hienSummary ? article.getSummary() : null);
	}
}
