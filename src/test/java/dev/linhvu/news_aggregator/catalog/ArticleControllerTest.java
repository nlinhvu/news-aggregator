package dev.linhvu.news_aggregator.catalog;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FlociTestConfiguration.class)
// `ArticleController` nay là `@Profile(WEB)`. Không profile ⇒ không controller
// ⇒ mọi assertion status 200 thành 404.
@ActiveProfiles(RoleProfiles.WEB)
class ArticleControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void tra_ve_mang_rong_khi_chua_co_du_lieu() throws Exception {
		mockMvc.perform(get("/api/articles"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray());
	}

	@Test
	void kep_limit_vao_khoang_hop_le() throws Exception {
		// limit vô lý không được làm hỏng request, chỉ bị kẹp lại.
		mockMvc.perform(get("/api/articles?limit=99999"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/articles?limit=-1"))
				.andExpect(status().isOk());
	}
}
