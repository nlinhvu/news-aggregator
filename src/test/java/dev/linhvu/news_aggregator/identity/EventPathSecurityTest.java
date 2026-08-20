package dev.linhvu.news_aggregator.identity;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Đường vào của `ingest`/`summarize` KHÔNG được Spring Security chặn.
 *
 * <p><b>Vì sao test này phải tồn tại.</b> `SecurityConfig` là `@Profile(HTTP)`,
 * nên hai function event-driven KHÔNG có filter chain của ta — chúng rơi về
 * chain MẶC ĐỊNH của Boot, thứ khoá `anyRequest().authenticated()` và bật CSRF.
 * `POST /events` khi đó trả 403/401 và ingestion chết.
 *
 * <p><b>Vì sao nó chết trong im lặng tuyệt đối.</b> EventBridge Scheduler gọi
 * Lambda BẤT ĐỒNG BỘ và Lambda Web Adapter nuốt HTTP status — invoke vẫn tính
 * là thành công. Không alarm nào kêu, DLQ không nhận gì, `AsyncEventsDropped`
 * không nhúc nhích. Triệu chứng duy nhất là bảng `articles` ngừng có bài mới,
 * và phải vài ngày mới có người nhận ra.
 *
 * <p><b>Vì sao `EventsControllerTest` không thay được test này.</b> Nó là
 * `@WebMvcTest` — slice không nạp security auto-config, nên nó xanh y hệt dù
 * chain có khoá hay không. Đã đo ở Task 9: thêm `oauth2-client` làm
 * `ArticleControllerTest` (full context) đỏ, `EventsControllerTest` (slice) xanh.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(RoleProfiles.INGEST)
@Import(FlociTestConfiguration.class)
class EventPathSecurityTest {

	@Autowired
	MockMvc mvc;

	@Test
	void events_never_returns_401_or_403() throws Exception {
		// Payload rỗng nên KHÔNG handler nào nhận — `EventsController` ném
		// `UnknownEventException` và trả 500. Đó là kết quả MONG ĐỢI ở đây: nó
		// chứng minh request đã ĐI QUA được filter chain và tới tận controller.
		// Khẳng định "không phải 401/403" chứ không phải "bằng 200", vì 200 đòi
		// một payload hợp lệ và test này không nói về dispatch.
		int status = mvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andReturn().getResponse().getStatus();

		assertThat(status)
				.as("401/403 ở đây là ingestion chết trong im lặng — LWA nuốt "
						+ "status nên không gì báo động")
				.isNotIn(401, 403);
	}

	@Test
	void health_stays_alive_on_the_event_driven_function() throws Exception {
		// `HealthController` không mang `@Profile` nào nên nó có mặt ở cả bốn
		// vai. Chain của hai vai event-driven không được vô tình khoá nó — đây
		// là đường một người vận hành dùng để hỏi "function này còn sống không".
		mvc.perform(get("/api/health")).andExpect(status().isOk());
	}
}
