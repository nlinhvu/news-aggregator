package dev.linhvu.news_aggregator.catalog;

import java.util.List;

import dev.linhvu.news_aggregator.catalog.api.ArticleSummaryDto;
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

		// Quyết định "có kèm summary không" nằm ở `ArticleSummaries` chứ không ở
		// đây: `/api/my/feed` phải trả CÙNG hình dạng, và hai bản sao của phép
		// ánh xạ sẽ trôi khỏi nhau đúng vào ngày ai đó tắt AI_SUMMARIZATION.
		final boolean hienSummary = ArticleSummaries.hienSummary();
		return repository.findRecent(effective).stream()
				.map(a -> ArticleSummaries.toDto(a, hienSummary))
				.toList();
	}
}
