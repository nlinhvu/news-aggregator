package dev.linhvu.news_aggregator.identity;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.NewsFeature;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.togglz.core.context.FeatureContext;
import org.togglz.core.manager.FeatureManager;
import org.togglz.junit5.AllEnabled;
import org.togglz.testing.TestFeatureManagerProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `USER_ACCOUNTS` là kill switch cho TOÀN BỘ bề mặt đăng nhập.
 *
 * <p><b>404 chứ không 403.</b> Flag tắt nghĩa là tính năng KHÔNG TỒN TẠI, không
 * phải "tồn tại nhưng anh không được vào" — cùng cách Phase 3 giấu `summary`:
 * vắng mặt hoàn toàn, không phải null. Hai mã này cũng là thứ SPA dựa vào để
 * phân biệt: `401` = ẩn danh (hiện nút "Đăng nhập"), `404` = tắt (không hiện
 * nút nào).
 *
 * <p><b>Vế BẬT phải có mặt ở đây</b>, dù `SecurityConfigTest` đã kiểm cùng hai
 * đường: thiếu nó thì một cổng chặn *mọi* request — không thèm đọc flag — cũng
 * làm test tắt xanh. Đây là chốt chống chính của cả class.
 *
 * <p>Người ĐANG đăng nhập lúc flag bị tắt là một tình huống riêng và nó nằm ở
 * {@code LoginFlowIT}: chứng minh nó cần một phiên THẬT, mà phiên thật chỉ có
 * sau trọn luồng authorization code.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(RoleProfiles.WEB)
@Import(FlociTestConfiguration.class)
class UserAccountsToggleTest {

	@Autowired
	MockMvc mvc;

	/**
	 * FeatureManager THẬT — cái đọc bảng `feature-toggles` qua
	 * `FailClosedDynamoDbStateRepository`. Bảng của Floci rỗng, nên nó trả lời
	 * đúng thứ ta cần: không có item ⇒ Togglz rơi về mặc định của enum ⇒ OFF.
	 */
	@Autowired
	FeatureManager featureManager;

	/**
	 * `FeatureContext` cache FeatureManager trong một field static, còn Gradle
	 * chạy mọi test class trong cùng một JVM. Không dọn thì manager của class này
	 * rò sang class khác — kèm cả Spring context đã đóng.
	 */
	@AfterEach
	void traLaiFeatureManagerVeNguyenTrang() {
		TestFeatureManagerProvider.setFeatureManager(null);
		FeatureContext.clearCache();
	}

	@Test
	void flag_tat_thi_moi_endpoint_auth_tra_404_va_feed_van_200() throws Exception {
		tatFlag();

		mvc.perform(get("/api/auth/login")).andExpect(status().isNotFound());
		mvc.perform(get("/api/me")).andExpect(status().isNotFound());
		// Vế quan trọng nhất: tắt tính năng đăng nhập KHÔNG được đụng tới sản
		// phẩm chính. `/api/articles` mở với mọi người, mãi mãi (master §3.1).
		mvc.perform(get("/api/articles?limit=5")).andExpect(status().isOk());
	}

	/**
	 * Slice 4 mở hai đường mới, và chúng thuộc về kill switch này: không có tài
	 * khoản thì không có gì để cá nhân hoá.
	 *
	 * `404` chứ không `401`, và khác biệt đó là thứ SPA dựa vào: `401` = "chưa
	 * đăng nhập" (hiện nút đăng nhập), `404` = "tính năng không tồn tại" (không
	 * hiện gì cả). Thiếu vế này thì tắt flag sẽ để lại một hàng chip bấm vào
	 * không có tác dụng.
	 *
	 * `/api/sources` CỐ Ý không nằm trong danh sách: hàng chip hiện dạng mờ cho
	 * người ẩn danh, nên danh sách nguồn phải sống kể cả khi đăng nhập tắt.
	 */
	@Test
	void flag_tat_thi_ca_feed_rieng_lan_lua_chon_nguon_deu_404() throws Exception {
		tatFlag();

		mvc.perform(get("/api/my/feed")).andExpect(status().isNotFound());
		mvc.perform(get("/api/preferences/sources")).andExpect(status().isNotFound());
		mvc.perform(get("/api/sources")).andExpect(status().isOk());
	}

	@Test
	void flag_tat_thi_dang_xuat_cung_404_chu_khong_403() throws Exception {
		// Vị trí của cổng chặn viết thành khẳng định: nó phải đứng TRƯỚC
		// `CsrfFilter`. Đứng sau thì lời gọi này trả 403 vì thiếu CSRF token —
		// tức "có tồn tại nhưng anh làm sai", đúng thứ mà một tính năng KHÔNG TỒN
		// TẠI không được phép nói.
		tatFlag();

		mvc.perform(post("/api/auth/logout")).andExpect(status().isNotFound());
	}

	@Test
	@AllEnabled(NewsFeature.class)
	void flag_bat_thi_be_mat_dang_nhap_tro_lai_nguyen_ven() throws Exception {
		// Đích đến của redirect được ghim ở `SecurityConfigTest`; ở đây chỉ cần
		// nó KHÔNG phải 404 — tức cổng chặn có đọc flag thật.
		mvc.perform(get("/api/auth/login")).andExpect(status().is3xxRedirection());
		// 401 chứ không 404: tính năng CÓ, chỉ là người gọi đang ẩn danh. Đây
		// đúng là hai mã SPA dùng để chọn có hiện nút hay không.
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
		// Hai đường của slice 4 cũng phải trở lại — nếu không, một cổng chặn
		// "chặn tất" vẫn làm test tắt flag ở trên xanh.
		mvc.perform(get("/api/my/feed")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/preferences/sources"))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * Ép `FeatureContext` trả về FeatureManager của Spring.
	 *
	 * <p>Không có dòng này thì test tắt flag KHÔNG kiểm gì cả — và nó xanh theo
	 * chiều ngược lại mới đau: `togglz-testing` (kéo vào theo `togglz-junit`)
	 * đăng ký `FallbackTestFeatureManagerProvider` priority 20, đứng trước
	 * provider của Spring priority 60, và `FallbackTestFeatureManager.isActive`
	 * là `iconst_1; ireturn` — TRUE cho mọi feature. Nghĩa là trong test mà không
	 * làm gì thì flag không phải "mặc định OFF" mà là "BẬT HẾT". Xem
	 * `TogglzGateTest` để có bản đầy đủ của lời giải thích này.
	 *
	 * <p>Gọi trong THÂN test chứ không phải `@BeforeEach`: callback của
	 * `@AllEnabled` chạy TRƯỚC `@BeforeEach`, nên đặt ở đó sẽ đè mất
	 * TestFeatureManager và giết luôn test vế BẬT.
	 */
	private void tatFlag() {
		TestFeatureManagerProvider.setFeatureManager(featureManager);
		FeatureContext.clearCache();
	}
}
