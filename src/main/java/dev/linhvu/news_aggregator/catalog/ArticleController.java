package dev.linhvu.news_aggregator.catalog;

import java.util.List;

import dev.linhvu.news_aggregator.catalog.api.ArticleSummaryDto;
import dev.linhvu.news_aggregator.platform.NewsFeature;
import dev.linhvu.news_aggregator.platform.RoleProfiles;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/articles")
@Profile(RoleProfiles.WEB)
class ArticleController {

	private final ArticleRepository repository;
	private final int defaultLimit;
	private final int maxLimit;

	ArticleController(ArticleRepository repository,
			@Value("${news.catalog.default-limit}") int defaultLimit,
			@Value("${news.catalog.max-limit}") int maxLimit) {
		this.repository = repository;
		this.defaultLimit = defaultLimit;
		this.maxLimit = maxLimit;
	}

	@GetMapping
	List<ArticleSummaryDto> recent(@RequestParam(required = false) Integer limit) {
		int effective = Math.clamp(
				limit == null ? defaultLimit : limit, 1, maxLimit);

		// Fail-closed: nếu đọc flag lỗi thì coi như OFF và request vẫn thành
		// công. Lỗi đọc flag KHÔNG được làm hỏng cả trang (TDD §5.4).
		//
		// Lớp này chặn ở tầng ĐỌC và bổ sung cho — không thay thế —
		// `FailClosedDynamoDbStateRepository`, thứ chặn ở tầng DỰNG repository.
		// Cần cả hai: `FeatureContext.getFeatureManager()` còn ném
		// `IllegalStateException` khi không tìm ra manager nào, và đó là lỗi nằm
		// ngoài tầm với của state repository.
		boolean showSummary;
		try {
			showSummary = NewsFeature.AI_SUMMARIZATION.isActive();
		}
		catch (RuntimeException e) {
			showSummary = false;
		}

		final boolean include = showSummary;
		return repository.findRecent(effective).stream()
				.map(a -> new ArticleSummaryDto(
						a.getArticleId(), a.getTitle(), a.getPublishedAt(),
						a.getCanonicalUrl(), a.getSourceName(),
						include ? a.getSummary() : null))
				.toList();
	}
}
