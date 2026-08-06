package dev.linhvu.news_aggregator.ingestion;

import java.util.List;

/**
 * PORT. Tồn tại để quyết định "parse bằng gì" đảo ngược được trong ~2 ngày —
 * đó cũng là lý do nó không đủ chuẩn để lên ADR (TDD §17 #4).
 */
interface FeedParser {

	List<ParsedItem> parse(byte[] body);
}
