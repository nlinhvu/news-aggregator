package dev.linhvu.news_aggregator.identity;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `@SpringBootTest` + `@AutoConfigureMockMvc` chứ KHÔNG `@WebMvcTest`, và đó là
 * điều kiện để test này có nghĩa: slice `@WebMvcTest` không nạp security
 * auto-config, nên nó xanh y hệt kể cả khi filter chain khoá sạch mọi đường.
 * Đã đo ở Task 9 — thêm `oauth2-client` làm `ArticleControllerTest` (full
 * context) đỏ 401 trong khi `HealthControllerTest` (slice) vẫn xanh.
 *
 * `@ActiveProfiles(WEB)` — plan viết `{ "test", "web" }`; `test` không tồn tại
 * (xem `RoleProfileContextTest`), còn `web` là THẬT vì `ArticleController` là
 * `@Profile(WEB)`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(RoleProfiles.WEB)
@Import(FlociTestConfiguration.class)
class SecurityConfigTest {

	@Autowired
	MockMvc mvc;

	@Test
	void duong_doc_an_danh_van_200_khong_bi_chuyen_huong() throws Exception {
		// Quy tắc quan trọng nhất của cả phase. Một `formLogin()` bỏ quên hay một
		// `anyRequest().authenticated()` là đủ để biến trang chủ thành 302 —
		// và đó là hồi quy cho TOÀN BỘ sản phẩm, không phải cho một feature.
		mvc.perform(get("/api/articles?limit=5")).andExpect(status().isOk());
		mvc.perform(get("/api/health")).andExpect(status().isOk());
	}

	@Test
	void me_tra_401_khi_chua_dang_nhap_khong_phai_302() throws Exception {
		// 302 sang trang đăng nhập là hành vi cho trình duyệt điều hướng; với
		// một lời gọi `fetch` từ SPA nó biến thành lỗi CORS khó hiểu hoặc một
		// trang HTML nhét vào chỗ chờ JSON. API luôn trả 401.
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void ghi_khong_co_csrf_token_tra_403() throws Exception {
		mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
	}

	@Test
	void cookie_phien_la_httponly() throws Exception {
		MvcResult result = mvc.perform(get("/api/articles?limit=1")).andReturn();
		result.getResponse().getHeaders("Set-Cookie")
				.forEach(c -> assertThat(c)
						.as("mọi cookie phiên phải HttpOnly — XSRF-TOKEN thì KHÔNG, "
								+ "vì SPA phải đọc được nó")
						.satisfiesAnyOf(
								v -> assertThat(v).startsWith("XSRF-TOKEN"),
								v -> assertThat(v).contains("HttpOnly")));
	}

	@Test
	void dang_nhap_dan_sang_idp_chu_khong_sang_trang_cua_chinh_ta() throws Exception {
		// `/api/auth/login` là bề mặt TDD §7 công bố; entry point thật của Spring
		// Security nằm ở `/api/auth/login/{registrationId}`. Không có endpoint
		// bắc cầu thì SPA phải tự biết chuỗi "cognito" — một chi tiết của backend
		// rò ra ngoài.
		//
		// Kiểm ĐÍCH ĐẾN chứ không chỉ "là 3xx": chain MẶC ĐỊNH của Boot cũng trả
		// 3xx ở đây (redirect sang `/login` của formLogin), nên một assertion chỉ
		// đòi 3xx sẽ xanh y hệt khi `SecurityConfig` không tồn tại. Đã đo đúng
		// như vậy trong lượt chạy đỏ.
		mvc.perform(get("/api/auth/login"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/api/auth/login/cognito"));
	}
}
