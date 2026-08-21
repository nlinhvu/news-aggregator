package dev.linhvu.news_aggregator.catalog.api;

import java.time.Duration;
import java.util.Collection;
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

	/**
	 * AP11. `sourceIds` RỖNG nghĩa là **tất cả nguồn**, không phải "không nguồn
	 * nào" — hợp đồng này phải viết ra vì hai cách hiểu đều tự nhiên và chọn sai
	 * cho ra một trang trống thay vì một feed đầy đủ.
	 *
	 * `cursor` là chuỗi ĐỤC do chính module này phát ra ở lần gọi trước; `null`
	 * hoặc rỗng nghĩa là từ đầu danh sách. Người gọi KHÔNG được tự dựng nó.
	 *
	 * Ném {@link InvalidCursorException} khi cursor có mặt nhưng không giải mã
	 * được — người gọi ánh xạ thành `400`.
	 *
	 * Ném {@link CatalogUnavailableException} khi bất kỳ query nào hỏng. KHÔNG
	 * trả kết quả một phần: feed thiếu bài mà không báo gì khiến người đọc tưởng
	 * nguồn đó không có bài mới.
	 *
	 * Bài chưa có `sourceId` (mọi bài Phase 1–3, cho tới khi backfill xong)
	 * KHÔNG nằm trong kết quả khi `sourceIds` khác rỗng — `gsi-by-source` là
	 * sparse index.
	 *
	 * Cùng hình dạng với `/api/articles` (TDD §7), kể cả việc `summary` vắng mặt
	 * khi `AI_SUMMARIZATION` tắt và kể cả hợp đồng cursor.
	 */
	ArticlePage recentBySources(Collection<String> sourceIds, int limit, String cursor);
}
