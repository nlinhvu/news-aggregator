package dev.linhvu.news_aggregator.platform;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API này trả JSON, kể cả cho trình duyệt.
 *
 * <p>Lỗi được chốt ở đây có từ Phase 1 và sống qua sáu phase mà không ai thấy:
 * `jackson-dataformat-xml` vào classpath để ĐỌC FEED, nhưng Spring tự đăng ký
 * `JacksonXmlHttpMessageConverter` cho tầng HTTP. Đo trên dev 2026-08-13,
 * `/api/articles` trả `<List><item><id>…` cho `Accept` của mọi trình duyệt.
 *
 * <p><b>Vì sao nó sống lâu:</b> SPA gọi bằng `fetch` gửi `Accept: *&#47;*` và
 * nhận JSON, nên không gì vỡ. Chỉ người MỞ API bằng trình duyệt mới thấy.
 *
 * <p><b>Vì sao test này phải là `@SpringBootTest`:</b> `@WebMvcTest` không nạp
 * `XmlConfig`, nên slice sẽ xanh dù converter còn nguyên — đúng loại test mù
 * đã để lọt lỗi này từ đầu.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(RoleProfiles.WEB)
@Import(FlociTestConfiguration.class)
class HttpContentTypeTest {

	/** `Accept` mà Chrome/Firefox/Safari gửi khi người dùng mở thẳng một URL. */
	private static final String ACCEPT_CUA_TRINH_DUYET =
			"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

	@Autowired
	MockMvc mvc;

	@Test
	void trinh_duyet_mo_api_van_nhan_json_khong_phai_xml() throws Exception {
		mvc.perform(get("/api/articles?limit=1").header("Accept", ACCEPT_CUA_TRINH_DUYET))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

		mvc.perform(get("/api/health").header("Accept", ACCEPT_CUA_TRINH_DUYET))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
	}

	@Test
	void doi_dich_danh_xml_thi_bi_tu_choi_chu_khong_duoc_phuc_vu() throws Exception {
		// 406 là câu trả lời TRUNG THỰC: ta không phục vụ XML. Trả XML cho người
		// đòi XML mới là thứ làm hợp đồng API thành lời nói suông.
		mvc.perform(get("/api/articles?limit=1").header("Accept", MediaType.APPLICATION_XML_VALUE))
				.andExpect(status().isNotAcceptable());
	}
}
