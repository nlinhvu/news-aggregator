// `catalog :: api` cho `ArticleCatalog`/`SummarizableArticle` — đây là cạnh
// THẬT, không phải event, nên nó vẫn bị `verify()` kiểm. Khai tên module trần
// KHÔNG mở được named interface của nó (bài học Phase 2 Task 10).
//
// KHÔNG khai `catalog :: events` dù `ArticleAddedListener` nghe `ArticleAdded`:
// predicate ở `ModuleBoundaryTest` đã loại mọi package `.events` khỏi phạm vi,
// nên named interface đó không tồn tại và khai nó làm `verify()` chết — xem
// `catalog/package-info.java`.
@ApplicationModule(displayName = "summarization",
		allowedDependencies = { "platform", "catalog :: api" })
package dev.linhvu.news_aggregator.summarization;

import org.springframework.modulith.ApplicationModule;
