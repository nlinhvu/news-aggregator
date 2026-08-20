package dev.linhvu.news_aggregator.identity;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lỗi trên đường đọc CÔNG KHAI phải giữ đúng status của chính nó.
 *
 * <p><b>Vì sao đây phải là IT chạy trên Tomcat THẬT, không phải MockMvc.</b>
 * Cơ chế hỏng nằm ở ERROR dispatch: handler ném lỗi ⇒ container FORWARD sang
 * `/error` ⇒ Spring Security lọc cả dispatch type đó (mặc định từ Boot 3) ⇒
 * `/error` không ai cấp quyền ⇒ request ẩn danh bị entry point nuốt thành 401.
 *
 * <p>MockMvc KHÔNG thực hiện forward đó — nó trả thẳng 400 từ dispatcher. Đã
 * kiểm bằng mutation: một test MockMvc cho đúng tình huống này XANH cả khi gỡ
 * `dispatcherTypeMatchers(ERROR).permitAll()`, tức nó mù hoàn toàn với lỗi mà
 * nó mang tên. Test đó đã bị xoá thay vì giữ lại cho yên tâm.
 *
 * <p><b>Vế nguy hiểm không phải 400.</b> Một lỗi 500 trên đường đọc công khai
 * cũng hiện ra là 401 — sự cố trông y hệt vấn đề đăng nhập. Phase này đã mất
 * thời gian đúng một lần vì nhìn 401 rồi truy sai chỗ.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles(RoleProfiles.WEB)
@Import(FlociTestConfiguration.class)
class ErrorStatusIT {

	@Autowired
	TestRestTemplate rest;

	@Test
	void errors_on_permitAll_paths_keep_their_status_and_do_not_turn_into_401() {
		// `/api/articles` đã permitAll; `limit=abc` không parse được ⇒ 400.
		assertThat(rest.getForEntity("/api/articles?limit=abc", String.class).getStatusCode())
				.as("400 bị nuốt thành 401 nghĩa là ERROR dispatch đang bị chặn")
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void an_unknown_path_still_returns_401_and_that_is_deliberate() {
		// KHÔNG phải lỗi: `/api/does-not-exist` rơi vào `anyRequest().authenticated()`,
		// tức default-deny. Khẳng định nó ra đây để lần sau không ai "sửa" 401
		// này thành 404 và vô tình nới ranh giới — 404 cho người ẩn danh là nói
		// cho họ biết đường nào TỒN TẠI.
		assertThat(rest.getForEntity("/api/does-not-exist", String.class).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
