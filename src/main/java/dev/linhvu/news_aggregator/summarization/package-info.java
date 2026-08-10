// Slice 1 chưa chạm `catalog`. Task 8 sẽ mở rộng thành
// {"platform", "catalog :: api", "catalog :: events"} — đúng lúc cycle xuất hiện.
@ApplicationModule(displayName = "summarization", allowedDependencies = "platform")
package dev.linhvu.news_aggregator.summarization;

import org.springframework.modulith.ApplicationModule;
