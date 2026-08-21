package dev.linhvu.news_aggregator.catalog;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Hợp đồng phân trang QUA HTTP, trên bảng RIÊNG có số bài BIẾT TRƯỚC.
 *
 * `ArticleControllerTest` cố ý không khẳng định gì về `nextCursor` vì nó dùng
 * chung bảng với test khác nên số bài phụ thuộc thứ tự chạy. Hệ quả là phần
 * "đọc thừa một" của `ArticleController` không có gì canh: đã đo, đổi
 * `effective + 1` thành `effective` đi qua trọn vẹn 298 test — một thay đổi làm
 * `nextCursor` LUÔN null, tức người đọc không bao giờ tải được trang 2 và cả
 * tính năng của Phase 11 chết, mà suite vẫn xanh.
 *
 * `ArticleSummariesTest` canh `toPage` ở tầng hàm thuần, nhưng nó không thấy
 * controller truyền limit nào xuống repository. Chỗ duy nhất thấy được cả hai vế
 * là một request thật.
 *
 * Giá phải trả là một container Floci nữa, cùng lý do và cùng cách như
 * `ArticlePagingRepositoryTest`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FlociTestConfiguration.class)
@ActiveProfiles(RoleProfiles.WEB)
@TestPropertySource(properties = "news.catalog.table-name=articles-paging-http")
class ArticlePagingEndpointTest {

	private static final int LIMIT = 20;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ArticleRepository repository;

	@BeforeEach
	void loadFixtures() {
		PagingFixtures.all().forEach(repository::save);
	}

	/**
	 * Vế QUYẾT ĐỊNH: đi theo `nextCursor` phải ra 20 bài TIẾP THEO, không lặp và
	 * không nhảy cóc.
	 *
	 * Ranh giới trang 1/2 rơi vào giữa cụm trùng `publishedAt` của
	 * `PagingFixtures`, nên nếu cursor trỏ nhầm sang phần tử đọc-thừa thì trang 2
	 * bắt đầu trễ đúng một bài — và bài bị mất nằm ngay trong cụm ấy.
	 */
	@Test
	void following_the_cursor_returns_the_NEXT_page_not_a_repeat_and_not_a_skip() throws Exception {
		List<String> expected = PagingFixtures.idsInOrder();

		String firstPage = mockMvc.perform(get("/api/articles?limit=" + LIMIT))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(ids(firstPage)).containsExactlyElementsOf(expected.subList(0, LIMIT));

		String cursor = JsonPath.read(firstPage, "$.nextCursor");
		assertThat(cursor).as("còn 25 bài nữa thì PHẢI có cursor").isNotNull();

		String secondPage = mockMvc.perform(
						get("/api/articles?limit=" + LIMIT + "&cursor=" + cursor))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(ids(secondPage))
				.containsExactlyElementsOf(expected.subList(LIMIT, LIMIT * 2));
	}

	/**
	 * Một trang chứa hết kho ⇒ `nextCursor` null. Đây là tín hiệu DUY NHẤT báo
	 * hết bài; luôn phát cursor sẽ làm SPA cuộn mãi không dừng, mỗi lượt tải về
	 * một trang rỗng.
	 */
	@Test
	void the_last_page_emits_no_cursor() throws Exception {
		String body = mockMvc.perform(get("/api/articles?limit=100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(PagingFixtures.ARTICLE_COUNT))
				.andReturn().getResponse().getContentAsString();

		assertThat((String) JsonPath.read(body, "$.nextCursor")).isNull();
	}

	private static List<String> ids(String json) {
		return JsonPath.read(json, "$.items[*].id");
	}
}
