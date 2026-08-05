package dev.linhvu.news_aggregator.ingestion;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class IngestionController {

	static final String PASS_THROUGH_PATH = "/events";
	static final String INGEST_FEEDS_JOB = "ingest-feeds";

	private final IngestionRunner runner;

	IngestionController(IngestionRunner runner) {
		this.runner = runner;
	}

	@PostMapping(PASS_THROUGH_PATH)
	IngestResult handle(@RequestBody Map<String, Object> payload) {
		Object job = payload.get("job");
		if (!INGEST_FEEDS_JOB.equals(job)) {
			throw new UnknownJobException(String.valueOf(job));
		}
		return runner.run();
	}

	@ExceptionHandler(UnknownJobException.class)
	ProblemDetail handleUnknownJob(UnknownJobException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	static class UnknownJobException extends RuntimeException {
		UnknownJobException(String job) {
			super("job không được hỗ trợ: " + job);
		}
	}
}
