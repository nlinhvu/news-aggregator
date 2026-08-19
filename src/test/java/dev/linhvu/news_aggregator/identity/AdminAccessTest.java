package dev.linhvu.news_aggregator.identity;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ba đường vào `/admin/*`, và mỗi đường trả một mã KHÁC NHAU — khác biệt đó là
 * toàn bộ giá trị của cổng `ops`.
 *
 * <p><b>Profile `admin`, không phải `web`.</b> Console chạy trên function
 * `admin` (ADR-0020), nên kiểm nó ở profile khác là kiểm một thứ không tồn tại
 * ở nơi nó sống. `SecurityConfig` là `@Profile(HTTP)` nên chain giống nhau ở cả
 * hai — điều đó làm test này DỄ viết nhầm profile mà vẫn xanh, chứ không làm
 * profile trở nên không quan trọng.
 *
 * <p><b>MockMvc KHÔNG chạm được tới servlet của console.</b> Togglz console là
 * một `ServletRegistrationBean`, tức một servlet của CONTAINER, còn MockMvc chỉ
 * chạy filter chain + `DispatcherServlet`. Nên ở đây kiểm được đúng phần do
 * FILTER CHAIN quyết định — chuyển hướng, 403 — còn `200` thật của trang console
 * nằm ở {@code AdminConsoleIT}. Viết `andExpect(status().isOk())` ở đây là viết
 * một khẳng định không bao giờ đúng được, bất kể code đúng hay sai.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(RoleProfiles.ADMIN)
@Import(FlociTestConfiguration.class)
class AdminAccessTest {

	@Autowired
	MockMvc mvc;

	/**
	 * Người ẩn danh mở console bằng TRÌNH DUYỆT, nên ở đây 302 mới đúng — ngược
	 * hẳn với `/api/**`, nơi 401 mới đúng vì người gọi là `fetch` của SPA.
	 *
	 * <p>Kiểm ĐÍCH ĐẾN chứ không chỉ "là 3xx", cùng lý do đã ghi ở
	 * `SecurityConfigTest#dang_nhap_dan_sang_idp_chu_khong_sang_trang_cua_chinh_ta`:
	 * chính người ĐÃ có quyền `ops` cũng nhận 3xx ở đúng URL này (console tự
	 * redirect `/admin/togglz` → `/admin/togglz/index`), nên một assertion chỉ
	 * đòi 3xx sẽ xanh kể cả khi cổng mở toang.
	 *
	 * <p>URL TUYỆT ĐỐI theo `public-base-url`: sau CloudFront, host của request
	 * là Function URL với `AuthType=AWS_IAM`, nên một `Location` tương đối đẩy
	 * trình duyệt vào chỗ nó nhận 403.
	 */
	@Test
	void an_danh_bi_chuyen_sang_dang_nhap() throws Exception {
		mvc.perform(get("/admin/togglz"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("http://localhost:8080/api/auth/login/cognito"));
	}

	/**
	 * 403 chứ không 404: người vận hành cần phân biệt "sai quyền" với "sai đường
	 * dẫn". Giấu sự tồn tại của console không mua được gì — nó đứng sau cùng một
	 * CloudFront với site công khai.
	 */
	@Test
	void dang_nhap_nhung_khong_thuoc_ops_tra_403_khong_phai_404() throws Exception {
		mvc.perform(get("/admin/togglz").with(oidcLogin().authorities()))
				.andExpect(status().isForbidden());
	}

	/**
	 * `404` ở đây là BẰNG CHỨNG CỔNG ĐÃ MỞ, không phải một lỗi.
	 *
	 * <p>Filter chain cho qua ⇒ request đi tiếp tới `DispatcherServlet`, mà
	 * MockMvc không có servlet của console nên không handler nào nhận. Ba mã có
	 * thể xảy ra và chúng phân biệt được ba trạng thái khác nhau:
	 * <pre>
	 *   302 → cổng chưa nhận ra người này đã đăng nhập
	 *   403 → ánh xạ `cognito:groups` → `ROLE_ops` hỏng
	 *   404 → đã qua cổng (trang thật được chứng minh ở AdminConsoleIT)
	 * </pre>
	 */
	@Test
	void thuoc_ops_thi_qua_duoc_cong() throws Exception {
		mvc.perform(get("/admin/togglz")
						.with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ops"))))
				.andExpect(status().isNotFound());
	}

	/**
	 * HỒI QUY, và là lý do test này quan trọng hơn ba test trên.
	 *
	 * <p>Cổng `ops` bắt entry point phải phân nhánh theo đường dẫn. Một bản sửa
	 * cẩu thả — đổi thẳng entry point mặc định sang redirect — làm mọi endpoint
	 * `/api/**` trả 302 kèm HTML thay vì 401, và SPA khi đó nhận một trang đăng
	 * nhập nhét vào chỗ chờ JSON. Cả `AnonymousReadTest` lẫn `SecurityConfigTest`
	 * đều canh vế này ở profile `web`; ở đây canh nó tại CHÍNH nơi thay đổi diễn
	 * ra.
	 */
	@Test
	void api_van_tra_401_chu_khong_bi_keo_theo_chuyen_huong() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
	}
}
