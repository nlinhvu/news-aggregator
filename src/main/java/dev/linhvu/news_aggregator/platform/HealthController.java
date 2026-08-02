package dev.linhvu.news_aggregator.platform;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	private final String commitSha;

	HealthController(@Value("${news.commit-sha}") String commitSha) {
		this.commitSha = commitSha;
	}

	@GetMapping("/api/health")
	Map<String, String> health() {
		return Map.of("status", "UP", "commit", commitSha);
	}
}
