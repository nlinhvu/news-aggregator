package dev.linhvu.news_aggregator.platform;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthController.class)
@TestPropertySource(properties = "news.commit-sha=abc1234")
class HealthControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void returns_UP_and_the_right_commit_sha() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.commit").value("abc1234"));
	}

	/**
	 * `CachingDisabled` ở CloudFront chỉ chặn cache của CDN — nó không nói gì với
	 * trình duyệt, mà trình duyệt được phép heuristic-cache một 200 không validator
	 * (RFC 9111 §4.2.2). Thiếu header này thì sau khi deploy bản mới, người vừa
	 * deploy tải lại trang có thể thấy commit sha CŨ, và triệu chứng trông y hệt
	 * "CloudFront cache nhầm /api/*" — tức là sẽ đi tìm sai chỗ.
	 */
	@Test
	void forbids_browsers_from_caching_health() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(header().string("Cache-Control", "no-store"));
	}
}