package dev.linhvu.news_aggregator.catalog.api;

import java.util.Optional;

public interface ArticleCatalog {

	/**
	 * AP8. Trả rỗng khi article không tồn tại, đã có `summary`, không có
	 * `excerpt`, hoặc `excerpt` ngắn hơn ngưỡng.
	 *
	 * Nhánh "đã có summary" là CHỐT CHẶN IDEMPOTENT của cả phase: hai producer
	 * cùng đẩy được một article, và SQS còn giao lại message tới 3 lần.
	 */
	Optional<SummarizableArticle> findSummarizable(String articleId);
}
