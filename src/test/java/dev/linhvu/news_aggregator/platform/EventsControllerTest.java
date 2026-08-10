package dev.linhvu.news_aggregator.platform;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventsController.class)
@Import(EventsControllerTest.Handlers.class)
class EventsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	Handlers handlers;

	@TestConfiguration(proxyBeanMethods = false)
	static class Handlers {

		final AtomicInteger alphaCalls = new AtomicInteger();

		// Dùng chung cho `gamma` và `delta`: hai handler CỐ Ý chồng nhau, phục vụ
		// riêng test `moi_payload_chi_mot_handler_nhan`. Đếm chung vì thứ ai thắng
		// không phải thứ được kiểm — xem Javadoc của test đó.
		final AtomicInteger overlapCalls = new AtomicInteger();

		@Bean
		EventJobHandler alpha() {
			return new EventJobHandler() {
				@Override
				public boolean supports(Map<String, Object> payload) {
					return "alpha".equals(payload.get("job"));
				}

				@Override
				public Object handle(Map<String, Object> payload) {
					alphaCalls.incrementAndGet();
					return Map.of("ran", "alpha");
				}
			};
		}

		@Bean
		EventJobHandler beta() {
			return new EventJobHandler() {
				@Override
				public boolean supports(Map<String, Object> payload) {
					return payload.containsKey("Records");
				}

				@Override
				public Object handle(Map<String, Object> payload) {
					return Map.of("ran", "beta");
				}
			};
		}

		@Bean
		EventJobHandler gamma() {
			return overlapping("gamma");
		}

		@Bean
		EventJobHandler delta() {
			return overlapping("delta");
		}

		private EventJobHandler overlapping(String name) {
			return new EventJobHandler() {
				@Override
				public boolean supports(Map<String, Object> payload) {
					return payload.containsKey("overlap");
				}

				@Override
				public Object handle(Map<String, Object> payload) {
					overlapCalls.incrementAndGet();
					return Map.of("ran", name);
				}
			};
		}
	}

	@Test
	void chon_dung_handler_theo_job() throws Exception {
		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"job\":\"alpha\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ran").value("alpha"));

		assertThat(handlers.alphaCalls.get()).isEqualTo(1);
	}

	@Test
	void chon_dung_handler_theo_hinh_dang_payload() throws Exception {
		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"Records\":[{\"messageId\":\"m1\"}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ran").value("beta"));
	}

	/**
	 * Payload không handler nào nhận PHẢI là 4xx, không được là 200 rỗng.
	 *
	 * Chế độ hỏng mà mục này chặn: một nguồn sự kiện mới được cắm vào (Phase 5,
	 * 6, 8, 9 đều sẽ làm thế) nhưng handler tương ứng chưa lên production, hoặc
	 * `supports()` viết sai. Với 200 rỗng thì EventBridge/SQS coi là thành công,
	 * message bị xoá, và sự kiện biến mất KHÔNG dấu vết. Với 4xx thì retry chạy
	 * và DLQ nhận — hỏng nhìn thấy được.
	 */
	@Test
	void payload_khong_ai_nhan_tra_400() throws Exception {
		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"job\":\"khong-ton-tai\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void body_rong_tra_400() throws Exception {
		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * Hai `supports()` cùng nhận một payload thì ĐÚNG MỘT handler chạy, IM LẶNG —
	 * không log, không lỗi, và handler kia mất lượt vĩnh viễn.
	 *
	 * `gamma` và `delta` đều nhận payload có key `overlap`. Test đếm tổng số lần
	 * chạy chứ KHÔNG ghim ai thắng: người thắng là handler đứng trước trong
	 * `List<EventJobHandler>`, mà thứ tự đó là thứ tự đăng ký bean — Spring không
	 * hứa gì về nó, nên ghim vào là test tự bịa ra một hợp đồng không tồn tại.
	 *
	 * Đây là lý do hợp đồng của `EventJobHandler#supports` là "kiểm ĐỦ điều kiện
	 * của mình", không chỉ điều kiện phân biệt với handler đang có: một `supports()`
	 * quá rộng sẽ nuốt sự kiện của nguồn khác mà không có gì đỏ ở bất kỳ đâu.
	 */
	@Test
	void moi_payload_chi_mot_handler_nhan() throws Exception {
		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"overlap\":true}"))
				.andExpect(status().isOk());

		assertThat(handlers.overlapCalls.get())
				.as("đúng một handler được gọi, không phải cả hai và không phải không ai")
				.isEqualTo(1);
	}
}
