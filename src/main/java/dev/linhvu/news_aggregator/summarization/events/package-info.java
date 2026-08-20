/**
 * Event mà `summarization` phát ra. Cùng quy tắc như `ingestion.events` và
 * `catalog.events`: chỉ chứa record của `String` (ADR-0012).
 *
 * Từ Phase 3, `ApplicationModules.verify()` KHÔNG còn phân tích event type —
 * đó là cái giá đã chấp nhận để giữ cycle `catalog ↔ summarization`. Test
 * `ModuleBoundaryTest#event_record_contains_only_strings` là thứ DUY NHẤT còn canh
 * quy tắc này. Không nới nó.
 */
@NamedInterface("events")
package dev.linhvu.news_aggregator.summarization.events;

import org.springframework.modulith.NamedInterface;
