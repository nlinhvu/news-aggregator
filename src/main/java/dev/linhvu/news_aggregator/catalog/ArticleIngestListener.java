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
		// `ArticleDiscovered` mang `sourceId` từ Phase 2; `catalog` chỉ đang bỏ
		// đi. Thiếu dòng này thì bài MỚI cũng rơi ra ngoài `gsi-by-source` y hệt
		// bài chưa backfill — và backfill của Task 21 chỉ chạy một lượt, nên
		// không có gì dọn lại phần rơi ra sau đó.
		article.setSourceId(event.sourceId());
		article.setExcerpt(event.excerpt());
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
