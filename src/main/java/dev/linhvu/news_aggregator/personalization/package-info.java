/**
 * Module `personalization` — trả lời câu hỏi "người này muốn đọc gì".
 *
 * Sở hữu bảng `user-preferences`, khoá bằng Cognito `sub`. KHÔNG sở hữu bài
 * viết và KHÔNG bao giờ query DynamoDB của `catalog`: nó hỏi `ArticleCatalog`.
 *
 * Mọi cạnh của module này chạy MỚI → CŨ ([ADR-0019] driver #1). Chiều ngược
 * lại — `catalog` biết `personalization` tồn tại — là thứ đã đẻ ra cycle
 * `catalog ↔ summarization` mà ADR-0012 phải xử lý bằng ignore-predicate, và
 * phase này cam kết KHÔNG thêm ignore-predicate nào. `ModuleBoundaryTest` canh
 * cả hai chiều.
 *
 * Ba named interface phải khai TƯỜNG MINH kèm `:: api`: tên module trần KHÔNG
 * mở được named interface của nó (bài học Phase 2 Task 10). `sources` khai cả
 * hai dạng vì `SourceCatalog` nằm ở package gốc còn `SourceOptionDto` nằm
 * trong `sources.api`.
 */
@ApplicationModule(displayName = "personalization",
		allowedDependencies = { "platform", "catalog :: api", "identity :: api",
				"sources", "sources :: api" })
package dev.linhvu.news_aggregator.personalization;

import org.springframework.modulith.ApplicationModule;
