/**
 * Event mà `catalog` phát ra. Cùng quy tắc như `ingestion.events`: chỉ chứa
 * record của `String` (ADR-0012).
 */
@NamedInterface("events")
package dev.linhvu.news_aggregator.catalog.events;

import org.springframework.modulith.NamedInterface;
