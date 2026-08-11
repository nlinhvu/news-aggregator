package dev.linhvu.news_aggregator.catalog.api;

import java.time.Duration;
import java.util.List;
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

	/**
	 * AP9. Article trong cửa sổ `window` tính từ bây giờ, CÓ `excerpt` đủ dài
	 * và CHƯA có `summary`. Trả về nhiều nhất `limit` phần tử.
	 *
	 * Bài rơi khỏi cửa sổ thì không bao giờ được nhặt lại — đó là cơ chế chặn
	 * rò rỉ chi phí của ADR-0014, không phải thiếu sót.
	 */
	List<SummarizableArticle> findSummarizable(Duration window, int limit);
}
