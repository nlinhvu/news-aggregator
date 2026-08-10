package dev.linhvu.news_aggregator.catalog;

import dev.linhvu.news_aggregator.summarization.events.ArticleSummarized;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Nửa sau của cycle. `summarization` phát fact "đã tóm tắt xong"; `catalog` —
 * chủ sở hữu bảng `articles` — là bên duy nhất ghi vào bảng đó (master §4
 * nguyên tắc 4).
 */
@Component
class ArticleSummarizedListener {

	private final ArticleRepository repository;

	ArticleSummarizedListener(ArticleRepository repository) {
		this.repository = repository;
	}

	@EventListener
	void on(ArticleSummarized event) {
		repository.attachSummary(event.articleId(), event.summary());
	}
}
