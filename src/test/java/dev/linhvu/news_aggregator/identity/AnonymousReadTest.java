package dev.linhvu.news_aggregator.identity;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Driver #3 của ADR-0018 viết thành test: **BFF không được làm feed công khai
 * đắt hơn**.
 *
 * <p>Nó im lặng khi hỏng. Thêm một lượt `GetItem` lên bảng `sessions` vào mỗi
 * request ẩn danh, hay một lời gọi SSM vào mỗi cold start, đều không làm test
 * nào khác đỏ — chỉ làm hoá đơn và cold start dày lên mà không ai truy ra lý do.
 *
 * <p><b>Đây cũng là chỗ đo lại một câu đã hứa ở Task 9.</b> `@Lazy` trên
 * `DynamoDbSessionRepository` KHÔNG còn tác dụng từ Task 10 — `@EnableSpringHttpSession`
 * đăng ký `springSessionRepositoryFilter`, filter inject repository qua
 * constructor nên bean bị dựng ngay lúc khởi động (đã đo:
 * `containsSingleton` = true). Thứ thật sự bảo vệ đường ẩn danh là hành vi của
 * filter: KHÔNG cookie phiên ⇒ không tra `findById` ⇒ không có lời gọi
 * DynamoDB. Test này là chỗ chứng minh vế đó, chứ không phải `@Lazy`.
 *
 * <p>`@MockitoSpyBean` chứ không `@MockitoBean`: ta cần bean THẬT chạy đúng
 * hành vi của nó, chỉ mượn khả năng đếm tương tác. Thay bằng mock sẽ làm
 * `/api/articles` trả rỗng và test xanh vì một lý do khác hẳn.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(RoleProfiles.WEB)
@Import(FlociTestConfiguration.class)
class AnonymousReadTest {

	@Autowired
	MockMvc mvc;

	@MockitoSpyBean
	DynamoDbSessionRepository sessions;

	@MockitoSpyBean
	SsmClient ssm;

	@Test
	void doc_feed_an_danh_khong_tra_phien_va_khong_goi_ssm() throws Exception {
		mvc.perform(get("/api/articles?limit=20")).andExpect(status().isOk());

		verifyNoInteractions(sessions);
		verifyNoInteractions(ssm);
	}

	@Test
	void health_an_danh_cung_khong_cham_gi() throws Exception {
		// `/api/health` là đường CloudFront và người vận hành gọi thường xuyên
		// nhất. Nó đắt lên thì không ai nhìn thấy, chỉ hoá đơn thấy.
		mvc.perform(get("/api/health")).andExpect(status().isOk());

		verifyNoInteractions(sessions);
		verifyNoInteractions(ssm);
	}

	/**
	 * `/api/me` là đường SPA gọi Ở MỌI LẦN TẢI TRANG, kể cả của người chưa từng
	 * đăng nhập — nên nó là đường ẩn danh ĐÔNG NHẤT, không phải một ngoại lệ của
	 * "đường đăng nhập".
	 *
	 * <p>Class này ra đời để canh driver #3 của ADR-0018 nhưng chỉ phủ
	 * `/api/articles` và `/api/health`, và cái lọt qua khe đó đã chạy thẳng lên
	 * prod: đo ngày 2026-08-13, mỗi lượt `/api/me` ẩn danh trả **401 kèm
	 * `Set-Cookie: SESSION`**, tức một `PutItem` và một dòng sống 30 ngày cho mỗi
	 * khách vãng lai. Một lượt QA của MỘT người để lại 7 phiên.
	 *
	 * <p>Thủ phạm không nằm ở controller mà ở filter chain:
	 * `ExceptionTranslationFilter` gọi `HttpSessionRequestCache.saveRequest()`
	 * TRƯỚC khi giao cho entry point, và việc lưu đó tạo HTTP session. Saved
	 * request ấy KHÔNG BAO GIỜ được dùng tới — đăng nhập đi qua
	 * `/api/auth/login` tường minh, còn `defaultSuccessUrl(..., true)` luôn ghi
	 * đè đích đến.
	 */
	@Test
	void me_an_danh_tra_401_ma_khong_tao_phien() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());

		verifyNoInteractions(sessions);
		verifyNoInteractions(ssm);
	}
}
