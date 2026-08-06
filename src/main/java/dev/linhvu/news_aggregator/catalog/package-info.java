// `ingestion :: events` chứ không phải `ingestion`: listener chỉ được thấy
// event type, không thấy phần còn lại của module kia (ADR-0012). Khai tên module
// trần KHÔNG mở được named interface của nó — đã đo ở Task 10.
@ApplicationModule(displayName = "catalog",
		allowedDependencies = { "platform", "ingestion :: events" })
package dev.linhvu.news_aggregator.catalog;

import org.springframework.modulith.ApplicationModule;
