package dev.linhvu.news_aggregator.catalog;

import dev.linhvu.news_aggregator.catalog.events.ArticleAdded;
import dev.linhvu.news_aggregator.ingestion.events.ArticleDiscovered;
import dev.linhvu.news_aggregator.platform.IngestionRunMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Đồng bộ, in-process. Master §6.5 đã loại Spring Modulith event publication
 * registry vì không có bản cho DynamoDB — nên đây là `@EventListener` thường,
 * và exception nổi ngược lên `IngestionRunner` để cô lập ở mức item.
 */
@Component
class ArticleIngestListener {

	private static final Logger log = LoggerFactory.getLogger(ArticleIngestListener.class);

	private final ArticleRepository repository;
	private final ApplicationEventPublisher events;
	private final IngestionRunMetrics metrics;

	ArticleIngestListener(ArticleRepository repository, ApplicationEventPublisher events,
			IngestionRunMetrics metrics) {
		this.repository = repository;
		this.events = events;
		this.metrics = metrics;
	}

	@EventListener
	void on(ArticleDiscovered event) {
		Article article = new Article();
		article.setArticleId(CatalogIds.articleId(event.canonicalUrl()));
		article.setListBucket(Article.LIST_BUCKET);
		article.setPublishedAt(event.publishedAt());
		article.setTitle(event.title());
		article.setCanonicalUrl(event.canonicalUrl());
		article.setSourceName(event.sourceName());
		// summary để null — Phase 3 mới điền.

		if (!repository.saveIfAbsent(article)) {
			// Trùng là luồng bình thường: RSS luôn trả ~20 bài gần nhất.
			return;
		}

		metrics.countAdded();
		events.publishEvent(new ArticleAdded(article.getArticleId(),
				event.sourceName(), event.canonicalUrl(), event.title(),
				event.publishedAt()));
		log.debug("article mới: {}", article.getArticleId());
	}
}
