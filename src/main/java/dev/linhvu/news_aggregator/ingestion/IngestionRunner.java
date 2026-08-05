package dev.linhvu.news_aggregator.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

@Service
class IngestionRunner {

	private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

	IngestResult run() {
		log.info("ingestion run bắt đầu (slice 1 — chưa có nguồn nào)");
		return new IngestResult(0, 0, 0);
	}
}
