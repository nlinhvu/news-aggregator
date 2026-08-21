package dev.linhvu.news_aggregator.catalog.api;

import java.util.List;

/**
 * Một trang của danh sách article. Hình dạng response của CẢ HAI endpoint —
 * `/api/articles` và `/api/my/feed` — nên frontend dùng đúng một đoạn code phân
 * trang cho cả hai.
 *
 * `nextCursor` null nghĩa là **HẾT BÀI**, và đó là tín hiệu duy nhất. Đừng suy
 * ra từ `items.size() < limit`: đường fan-out lọc lại ở tầng ứng dụng nên một
 * trang còn đầy dữ liệu vẫn có thể trả ít hơn `limit`, và suy ra từ số lượng sẽ
 * cắt cụt danh sách giữa chừng — im lặng.
 *
 * KHÔNG `@JsonInclude(NON_NULL)` trên `nextCursor`: client cần phân biệt "hết
 * bài" với "server phiên bản cũ không biết phân trang", và một trường VẮNG MẶT
 * không nói được điều đó. Khác hẳn `ArticleSummaryDto.summary`, nơi vắng mặt
 * mới là tín hiệu đúng.
 */
public record ArticlePage(List<ArticleSummaryDto> items, String nextCursor) {
}
