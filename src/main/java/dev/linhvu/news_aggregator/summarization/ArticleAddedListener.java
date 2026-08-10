package dev.linhvu.news_aggregator.summarization;

import dev.linhvu.news_aggregator.catalog.events.ArticleAdded;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * PRODUCER #1 — đường tươi. Chạy in-process ngay trong lượt ingest, nên tóm tắt
 * xuất hiện trong vòng vài phút thay vì chờ lượt sweep.
 *
 * Đây là cạnh tạo ra nửa đầu của cycle `catalog ↔ summarization` mà ADR-0012 đã
 * chấp nhận. Nửa sau là `catalog.ArticleSummarizedListener`.
 */
@Component
class ArticleAddedListener {

	private final SummarizationQueue queue;

	ArticleAddedListener(SummarizationQueue queue) {
		this.queue = queue;
	}

	@EventListener
	void on(ArticleAdded event) {
		queue.enqueue(event.articleId());
	}
}
