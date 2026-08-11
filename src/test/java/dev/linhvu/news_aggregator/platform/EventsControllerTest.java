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
	 * Payload không handler nào nhận PHẢI là **500**, không phải 4xx và tuyệt đối
	 * không phải 200 rỗng.
	 *
	 * Chế độ hỏng mà mục này chặn: một nguồn sự kiện mới được cắm vào (Phase 5, 6,
	 * 8, 9 đều sẽ làm thế) nhưng handler tương ứng chưa lên production, hoặc
	 * `supports()` viết sai. Đây là lệch giữa IaC và code — bug của **deploy**,
	 * không phải yêu cầu sai của người gọi, vì người gọi duy nhất là EventBridge
	 * Scheduler và SQS ESM với payload do chính `AppStack` sinh ra.
	 *
	 * VÌ SAO 500 CHỨ KHÔNG 400 — và vì sao bản đầu của test này viết sai:
	 * Javadoc cũ khẳng định *"với 4xx thì retry chạy và DLQ nhận"*. **Sai.**
	 * Phase 3 đo thật trên prod (§16): lượt `SummarizeSweepSchedule` lúc 07:11:03
	 * UTC bắn vào bản code cũ chưa có handler ⇒ 400 ⇒ `Errors` = 0, DLQ = 0, KHÔNG
	 * retry, đúng một dòng log. LWA nuốt status vào body nên với Lambda đó là một
	 * lượt invoke THÀNH CÔNG.
	 *
	 * Thứ làm nó thành tín hiệu là `AWS_LWA_ERROR_STATUS_CODES=500-599`
	 * (ADR-0015), và dải đó CỐ Ý không lấy 4xx: bot quét sinh 404 trên đường
	 * public hàng ngày, đưa 4xx vào là tự đầu độc alarm. Nên endpoint nội bộ phải
	 * tự nói đúng mức nghiêm trọng của nó.
	 */
	@Test
	void payload_khong_ai_nhan_tra_500() throws Exception {
		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"job\":\"khong-ton-tai\"}"))
				.andExpect(status().isInternalServerError());
	}

	@Test
	void body_rong_tra_500() throws Exception {
		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isInternalServerError());
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
