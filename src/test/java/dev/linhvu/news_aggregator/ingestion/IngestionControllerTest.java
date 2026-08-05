package dev.linhvu.news_aggregator.ingestion;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IngestionController.class)
class IngestionControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	IngestionRunner runner;

	@Test
	void chay_duoc_khi_job_dung() throws Exception {
		given(runner.run()).willReturn(new IngestResult(42, 7, 0));

		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"job\":\"ingest-feeds\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.discovered").value(42))
				.andExpect(jsonPath("$.added").value(7))
				.andExpect(jsonPath("$.failed").value(0));
	}

	/**
	 * Payload là HỢP ĐỒNG, không phải mặc định của EventBridge (TDD §7).
	 * Phase 3 sẽ đổ message SQS vào CÙNG path này, nên `job` là thứ phân biệt
	 * nguồn. Từ chối job lạ ngay từ Phase 2 khiến hợp đồng đó có hiệu lực thật
	 * thay vì chỉ là một dòng trong spec.
	 */
	@Test
	void tu_choi_job_la() throws Exception {
		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"job\":\"rm-rf\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void tu_choi_body_rong() throws Exception {
		mockMvc.perform(post("/events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
	}
}
