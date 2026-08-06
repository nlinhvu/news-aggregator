/**
 * Event mà `ingestion` phát ra. Expose thành named interface riêng để listener
 * phụ thuộc ĐÚNG phần này, không phải toàn bộ API của module (ADR-0012).
 *
 * Record trong package này CHỈ được chứa `String`. Đó không phải quy ước phong
 * cách: Phase 3 sẽ loại event type khỏi phạm vi `ApplicationModules.verify()`
 * để chấp nhận cycle `catalog ↔ summarization`, và test
 * `event_record_chi_chua_string` là thứ bịt khoảng trống đó.
 */
@NamedInterface("events")
package dev.linhvu.news_aggregator.ingestion.events;

import org.springframework.modulith.NamedInterface;
