package dev.linhvu.news_aggregator.catalog.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Public surface của module `catalog`.
 *
 * `summary` VẮNG MẶT hoàn toàn khi AI_SUMMARIZATION tắt — không phải null.
 * Vắng mặt là tín hiệu rõ ràng hơn cho frontend so với một giá trị rỗng.
 */
public record ArticleSummaryDto(
		String id,
		String title,
		String publishedAt,
		String canonicalUrl,
		String sourceName,
		@JsonInclude(JsonInclude.Include.NON_NULL) String summary
) {}
