// `sources` cho `SourceCatalog` (nằm ở package gốc của module), `sources :: api`
// cho `SourceView` (nằm ở sub-package, là NỘI BỘ nếu không khai named interface).
// Thiếu vế thứ hai thì gọi được service nhưng không đụng được kiểu nó trả về.
@ApplicationModule(displayName = "ingestion",
		allowedDependencies = { "platform", "sources", "sources :: api" })
package dev.linhvu.news_aggregator.ingestion;

import org.springframework.modulith.ApplicationModule;