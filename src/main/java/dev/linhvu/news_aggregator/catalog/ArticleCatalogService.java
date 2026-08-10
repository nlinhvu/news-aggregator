package dev.linhvu.news_aggregator.catalog;

import java.util.Optional;

import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Lazy
class ArticleCatalogService implements ArticleCatalog {

	private final ArticleRepository repository;

	private final int minExcerptChars;

	ArticleCatalogService(ArticleRepository repository,
			@Value("${news.summarization.min-excerpt-chars}") int minExcerptChars) {
		this.repository = repository;
		this.minExcerptChars = minExcerptChars;
	}

	@Override
	public Optional<SummarizableArticle> findSummarizable(String articleId) {
		return repository.findById(articleId)
				.filter(ArticleCatalogService::chuaCoSummary)
				.filter(this::excerptDuDai)
				.map(a -> new SummarizableArticle(
						a.getArticleId(), a.getTitle(), a.getExcerpt()));
	}

	// Chuỗi rỗng cũng tính là chưa có: một `summary` rỗng lọt vào bảng là dữ
	// liệu hỏng, và bỏ qua nó vĩnh viễn thì không có đường tự sửa.
	private static boolean chuaCoSummary(Article a) {
		return a.getSummary() == null || a.getSummary().isBlank();
	}

	private boolean excerptDuDai(Article a) {
		return a.getExcerpt() != null && a.getExcerpt().length() >= minExcerptChars;
	}
}
