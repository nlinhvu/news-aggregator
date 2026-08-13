package dev.linhvu.news_aggregator.ingestion;

import java.util.Map;

import dev.linhvu.news_aggregator.platform.EventJobHandler;
import dev.linhvu.news_aggregator.platform.RoleProfiles;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile(RoleProfiles.INGEST)
class IngestFeedsHandler implements EventJobHandler {

	static final String JOB = "ingest-feeds";

	private final IngestionRunner runner;

	IngestFeedsHandler(IngestionRunner runner) {
		this.runner = runner;
	}

	@Override
	public boolean supports(Map<String, Object> payload) {
		return JOB.equals(payload.get("job"));
	}

	@Override
	public Object handle(Map<String, Object> payload) {
		return runner.run();
	}
}
