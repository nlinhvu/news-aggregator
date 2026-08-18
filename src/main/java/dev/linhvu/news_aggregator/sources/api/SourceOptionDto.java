package dev.linhvu.news_aggregator.sources.api;

/**
 * Cái NGƯỜI ĐỌC nhìn thấy — hai trường, không hơn.
 *
 * Tách khỏi {@link SourceView} chứ không tái dùng nó, dù cả hai cùng mô tả một
 * nguồn: `SourceView` mang `feedUrl`, `etag`, `lastModified` — TRẠNG THÁI VẬN
 * HÀNH. `GET /api/sources` là endpoint CÔNG KHAI, nên trả `SourceView` là đưa
 * lịch trình fetch và validator của chương trình ra Internet, và không có
 * triệu chứng nào ngoài mấy trường thừa trong JSON.
 */
public record SourceOptionDto(String sourceId, String name) {
}
