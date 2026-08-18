package dev.linhvu.news_aggregator.sources;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `@ActiveProfiles(WEB)` là điều kiện để test này có nghĩa: `SourceController`
 * mang `@Profile(WEB)`, và chain security của vai `web` là nơi `/api/sources`
 * được `permitAll`. Chạy không profile thì bean không tồn tại và mọi assertion
 * dưới đây đo một cái 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(RoleProfiles.WEB)
@Import(FlociTestConfiguration.class)
class SourceControllerTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DynamoDbEnhancedClient enhancedClient;

	@Value("${news.sources.table-name}")
	String tableName;

	DynamoDbTable<Source> table;

	/**
	 * Dọn rồi nạp lại: cả lớp dùng chung một container với mọi test khác, nên
	 * nguồn của test trước sống sót sang test sau.
	 *
	 * Tên cố ý KHÔNG cùng thứ tự với `sourceId` (`z-…`/`a-…`) để phép sắp theo
	 * `name` phân biệt được với "trả về theo thứ tự Scan".
	 */
	@BeforeEach
	void setUp() {
		table = enhancedClient.table(tableName, TableSchema.fromBean(Source.class));
		table.scan().items().stream().toList().forEach(table::deleteItem);
		table.putItem(source("a-id", "Zulu Blog", true));
		table.putItem(source("z-id", "Alpha Blog", true));
		table.putItem(source("tat-id", "Nguồn Đã Tắt", false));
	}

	/**
	 * Công khai vì hàng chip phải hiển thị (dạng mờ) cho cả người ẩn danh — đó
	 * là lời mời đăng nhập, và nó phải render được trước khi biết người dùng là
	 * ai.
	 *
	 * Nguồn đã TẮT không được lọt ra: người dùng chọn nó thì fan-out sinh một
	 * query vĩnh viễn không có bài mới, và không có gì báo cho họ biết lý do.
	 */
	@Test
	void danh_sach_nguon_la_cong_khai_va_chi_gom_nguon_dang_bat() throws Exception {
		mvc.perform(get("/api/sources"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[*].sourceId")
						.value(org.hamcrest.Matchers.containsInAnyOrder("a-id", "z-id")))
				.andExpect(jsonPath("$[*].name")
						.value(org.hamcrest.Matchers.not(
								org.hamcrest.Matchers.hasItem("Nguồn Đã Tắt"))));
	}

	/**
	 * Trạng thái vận hành KHÔNG ra Internet. Soi CẢ THÂN response chứ không
	 * `$[0].feedUrl` như bản plan gốc viết: vế đó chỉ kiểm phần tử đầu và đúng
	 * một khoá, nên `etag` rò ở phần tử thứ hai vẫn xanh.
	 *
	 * `feedUrl` là thứ mở đường cho người khác dội request lên nguồn dưới danh
	 * nghĩa của ta; `etag`/`lastFetchedAt` thì lộ lịch chạy của chương trình.
	 */
	@Test
	void khong_lo_trang_thai_van_hanh_cua_nguon() throws Exception {
		String body = mvc.perform(get("/api/sources"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andReturn().getResponse().getContentAsString();

		assertThat(body)
				.as("chỉ `sourceId` và `name` — xem SourceOptionDto")
				.doesNotContain("feedUrl", "etag", "lastModified", "lastFetchedAt",
						"enabled");
		// Chốt chống test rỗng: một response `[]` cũng thoả mọi vế phủ định trên.
		assertThat(body).contains("a-id", "z-id");
	}

	/**
	 * Thứ tự phải ỔN ĐỊNH và theo `name`. Scan của DynamoDB trả item theo thứ
	 * tự nội bộ, nên không sắp thì hàng chip đổi chỗ giữa các lần tải trang và
	 * người dùng bấm nhầm nguồn.
	 *
	 * `sourceId` cố ý ngược chiều với `name` trong fixture: một bản dựng "sắp
	 * theo sourceId" sẽ đỏ ở đây thay vì xanh nhờ trùng hợp.
	 */
	@Test
	void thu_tu_on_dinh_theo_ten() throws Exception {
		mvc.perform(get("/api/sources"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Alpha Blog"))
				.andExpect(jsonPath("$[1].name").value("Zulu Blog"));
	}

	private static Source source(String id, String name, boolean enabled) {
		Source s = new Source();
		s.setSourceId(id);
		s.setName(name);
		s.setFeedUrl("https://example.test/" + id + ".xml");
		s.setEtag("etag-" + id);
		s.setLastFetchedAt("2026-08-18T00:00:00Z");
		s.setEnabled(enabled);
		return s;
	}
}
