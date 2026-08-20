// KHÔNG khai `ingestion :: events` hay `summarization :: events` ở đây, dù
// `catalog` có listener cho cả hai. Từ Phase 3, predicate ở `ModuleBoundaryTest`
// loại mọi package `.events` khỏi phạm vi phân tích để chấp nhận cycle
// `catalog ↔ summarization` (ADR-0012 §5, §7). Type bị loại khỏi phạm vi thì
// named interface của nó KHÔNG TỒN TẠI nữa, nên khai `… :: events` làm
// `verify()` chết bằng "No named interface named 'events' found!" — chết lúc
// dựng ApplicationModules, trước cả khi kiểm được cạnh nào.
//
// Cạnh do event tạo ra vì thế là VÔ HÌNH với `verify()`. Thứ duy nhất còn canh
// nó là `event_record_contains_only_strings`; không được nới test đó.
@ApplicationModule(displayName = "catalog", allowedDependencies = { "platform" })
package dev.linhvu.news_aggregator.catalog;

import org.springframework.modulith.ApplicationModule;
