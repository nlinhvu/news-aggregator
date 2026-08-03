package dev.linhvu.news_aggregator.platform;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	private final String commitSha;

	HealthController(@Value("${news.commit-sha}") String commitSha) {
		this.commitSha = commitSha;
	}

	/**
	 * `no-store` là bắt buộc, không phải phòng xa. Cache behavior `/api/*` gắn
	 * `CachingDisabled` chỉ chặn cache của CloudFront; trình duyệt vẫn được phép
	 * heuristic-cache một 200 không validator. Hai cache khác nhau, cần hai thứ
	 * chặn khác nhau — xem `cam_trinh_duyet_cache_health` trong HealthControllerTest.
	 */
	@GetMapping("/api/health")
	ResponseEntity<Map<String, String>> health() {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(Map.of("status", "UP", "commit", commitSha));
	}
}
