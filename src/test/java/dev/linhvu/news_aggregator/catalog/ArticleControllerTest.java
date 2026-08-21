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
import static org.hamcrest.Matchers.hasKey;
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

	/**
	 * Hình dạng envelope, không phải mảng trần. Đây là breaking change của
	 * endpoint — test cũ khẳng định `$` là mảng đã được SỬA chứ không bị xoá:
	 * nó đang làm đúng việc của nó, chỉ là hợp đồng đổi.
	 *
	 * KHÔNG khẳng định `nextCursor` bằng null ở đây: class này dùng chung Spring
	 * context (và container Floci) với `MyFeedControllerTest`, nên số bài trong
	 * bảng phụ thuộc thứ tự chạy. Tính chất phân trang thật được kiểm ở
	 * `ArticlePagingRepositoryTest`, nơi có bảng riêng.
	 *
	 * `hasKey` chứ không phải `isEmpty`/`doesNotExist`: `nextCursor` phải CÓ MẶT
	 * trong JSON kể cả khi null, vì client cần phân biệt "hết bài" với "server
	 * đời cũ không biết phân trang".
	 */
	@Test
	void returns_an_envelope_with_items_and_nextCursor() throws Exception {
		mockMvc.perform(get("/api/articles"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isArray())
				.andExpect(jsonPath("$").value(hasKey("nextCursor")));
	}

	@Test
	void clamps_limit_into_the_valid_range() throws Exception {
		// limit vô lý không được làm hỏng request, chỉ bị kẹp lại.
		mockMvc.perform(get("/api/articles?limit=99999"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/articles?limit=-1"))
				.andExpect(status().isOk());
	}

	/**
	 * `400`, KHÔNG `500` và KHÔNG âm thầm rơi về trang đầu.
	 *
	 * Trả trang đầu cho một cursor hỏng làm người đọc thấy danh sách nhảy về
	 * đỉnh mà không hiểu vì sao — sai lệch im lặng, đúng loại mà cả phase này
	 * cố tránh.
	 */
	@Test
	void a_garbage_cursor_returns_400() throws Exception {
		mockMvc.perform(get("/api/articles?cursor=noseparator"))
				.andExpect(status().isBadRequest());
	}

	/** `?cursor=` rỗng nghĩa là KHÔNG có cursor, không phải cursor hỏng. */
	@Test
	void an_empty_cursor_still_returns_the_first_page() throws Exception {
		mockMvc.perform(get("/api/articles?cursor="))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isArray());
	}
}
