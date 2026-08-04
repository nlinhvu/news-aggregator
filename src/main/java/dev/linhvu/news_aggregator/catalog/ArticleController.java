package dev.linhvu.news_aggregator.catalog;

import java.util.List;

import dev.linhvu.news_aggregator.catalog.api.ArticleSummaryDto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/articles")
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

		return repository.findRecent(effective).stream()
				.map(a -> new ArticleSummaryDto(
						a.getArticleId(), a.getTitle(), a.getPublishedAt(),
						a.getCanonicalUrl(), a.getSourceName(),
						null))   // Task 32 nối summary vào đây theo feature flag
				.toList();
	}
}
