package dev.linhvu.news_aggregator.identity;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trọn luồng đăng nhập chạy trên một IdP THẬT (mock-oauth2-server) — cùng code
 * `oauth2Login()` với prod, chỉ khác issuer.
 *
 * <p><b>Vì sao test này đòi được `ClientRegistrations.fromIssuerLocation`.</b>
 * Bản Task 10 tự dựng URI theo `issuer + "/oauth2/authorize"`. Mock server đặt
 * endpoint ở `/cognito/authorize`, nên với bản hardcode thì test này không thể
 * chạy. Và chính lúc đối chiếu mới lộ ra bản hardcode SAI luôn với Cognito
 * thật: ba trong bốn endpoint nằm trên managed login domain.
 *
 * <p><b>Mọi request đi bằng `java.net.URI`, KHÔNG bằng String.</b> Đã trả giá
 * để biết: `RestTemplate.exchange(String, …)` coi chuỗi là URI *template* và
 * mã hoá lại nó, nên `state=…%3D` thành `…%253D`. Callback khi đó chết bằng
 * `authorization_request_not_found` — một lỗi trỏ thẳng vào session store,
 * trong khi session store hoàn toàn vô can. Chẩn đoán sai chỗ này tốn nhiều
 * thời gian hơn bản thân lỗi.
 *
 * <p><b>Không follow redirect.</b> Từng chặng 302 là một khẳng định riêng.
 */
// `@AutoConfigureTestRestTemplate` là BẮT BUỘC ở Boot 4: bean `TestRestTemplate`
// không còn được đăng ký chỉ nhờ `webEnvironment = RANDOM_PORT` như Boot 3 —
// thiếu nó thì context dựng xong nhưng `@Autowired` chết bằng
// `NoSuchBeanDefinitionException`.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles(RoleProfiles.WEB)
@Import({ FlociTestConfiguration.class, MockOAuth2ServerConfiguration.class })
class LoginFlowIT {

	@Autowired
	TestRestTemplate autowired;

	/**
	 * `DONT_FOLLOW` là điều kiện để test này có nghĩa. `TestRestTemplate` của
	 * Boot 4 ĐI THEO redirect mặc định (Boot 3 thì không) — mà từng chặng 302 ở
	 * đây là một khẳng định riêng, và ứng dụng dựng `Location` TUYỆT ĐỐI theo
	 * `public-base-url` (`localhost:8080`) nên đi theo là gõ nhầm cổng của
	 * RANDOM_PORT.
	 */
	private TestRestTemplate rest;

	@BeforeEach
	void khongDiTheoRedirect() {
		this.rest = autowired.withRedirects(HttpRedirects.DONT_FOLLOW);
	}

	@Test
	void login_dan_toi_idp_chu_khong_dan_ve_trang_cua_chinh_ta() {
		ResponseEntity<Void> toEntryPoint = get(at("/api/auth/login"), null);
		assertThat(toEntryPoint.getStatusCode()).isEqualTo(HttpStatus.FOUND);

		ResponseEntity<Void> toIdp = get(
				onTestServer(toEntryPoint.getHeaders().getLocation()), null);

		assertThat(toIdp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(toIdp.getHeaders().getLocation().toString())
				.as("phải dẫn sang IdP, không phải sang một trang của chính ta")
				.startsWith(MockOAuth2ServerConfiguration.issuerUri() + "/authorize")
				.contains("code_challenge");   // PKCE bật
	}

	@Test
	void sau_khi_dang_nhap_me_tra_sub_va_khong_tra_token() {
		String cookie = loginThroughMockIdp();

		ResponseEntity<String> me = exchange(at("/api/me"), HttpMethod.GET, cookie,
				String.class);

		assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(me.getBody()).contains("\"sub\"");
		// Vế NÀY là lý do cả ADR-0018 tồn tại. Một response `/api/me` lỡ mang
		// token là toàn bộ mô hình BFF sụp mà không test nào khác biết.
		assertThat(me.getBody())
				.as("không endpoint nào được trả token ra ngoài")
				.doesNotContain("id_token").doesNotContain("access_token")
				.doesNotContain("eyJ");
	}

	@Test
	void cookie_phien_la_httponly_con_xsrf_thi_khong() {
		// Đọc `Set-Cookie` THÔ, không đọc cái jar đã cắt attribute: `HttpOnly`
		// nằm trong phần attribute, nên kiểm trên jar là kiểm một chuỗi không
		// bao giờ chứa thứ ta hỏi.
		ResponseEntity<Void> toEntryPoint = get(at("/api/auth/login"), null);
		ResponseEntity<Void> toIdp = get(
				onTestServer(toEntryPoint.getHeaders().getLocation()), null);

		List<String> setCookies = toIdp.getHeaders().get(HttpHeaders.SET_COOKIE);
		assertThat(setCookies).isNotEmpty();
		assertThat(setCookies)
				.as("cookie phiên PHẢI HttpOnly — đó là toàn bộ giá trị của BFF")
				.allSatisfy(c -> assertThat(c).satisfiesAnyOf(
						v -> assertThat(v).startsWith("XSRF-TOKEN"),
						v -> assertThat(v).contains("HttpOnly")));
		assertThat(setCookies).anySatisfy(c -> assertThat(c).startsWith("SESSION="));
	}

	@Test
	void dang_xuat_xoa_phien_that_khong_chi_xoa_cookie() {
		String cookie = loginThroughMockIdp();

		// Khẳng định phiên ĐANG SỐNG trước đã. Thiếu dòng này thì test xanh y hệt
		// khi đăng nhập chưa bao giờ thành công — đúng cái bẫy đã sập một lần
		// trong lúc dựng test này.
		assertThat(exchange(at("/api/me"), HttpMethod.GET, cookie, Void.class)
				.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Kiểm status của CHÍNH lời gọi đăng xuất. Thiếu nó thì một 401/403 ở
		// đây trôi qua im lặng và vế "phiên đã chết" bên dưới xanh vì lý do sai.
		assertThat(exchange(at("/api/auth/logout"), HttpMethod.POST, cookie, Void.class)
				.getStatusCode())
				.as("đăng xuất phải đi lọt CSRF — SPA gửi lại token qua X-XSRF-TOKEN")
				.isEqualTo(HttpStatus.FOUND);

		// Dùng LẠI đúng cookie cũ. Nếu đăng xuất chỉ xoá cookie phía trình duyệt
		// thì request này vẫn 200 — và phiên bị đánh cắp vẫn sống.
		ResponseEntity<Void> after = exchange(at("/api/me"), HttpMethod.GET, cookie,
				Void.class);
		assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	/**
	 * Đi trọn luồng authorization code và trả về cookie phiên.
	 *
	 * Cookie phải được mang theo từ chặng 2: Spring Security lưu
	 * `OAuth2AuthorizationRequest` (kèm `state` và PKCE verifier) vào HTTP
	 * session, mà session đó nằm trong DynamoDB qua `DynamoDbSessionRepository`.
	 * Đánh rơi cookie ở giữa thì callback chết bằng
	 * `authorization_request_not_found` — nên luồng này kiểm luôn được session
	 * store, không chỉ kiểm OAuth.
	 */
	private String loginThroughMockIdp() {
		ResponseEntity<Void> toEntryPoint = get(at("/api/auth/login"), null);
		String cookie = sessionCookie(toEntryPoint, null);

		ResponseEntity<Void> toIdp = get(
				onTestServer(toEntryPoint.getHeaders().getLocation()), cookie);
		cookie = sessionCookie(toIdp, cookie);

		// Form của mock server: `<form method="post">` không có `action`, tức nó
		// POST về CHÍNH url authorize (kèm nguyên query string). Hai field:
		// `username` và `claims`.
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", "dev@local");
		form.add("claims", "{}");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		ResponseEntity<Void> toCallback = rest.exchange(
				toIdp.getHeaders().getLocation(), HttpMethod.POST,
				new HttpEntity<>(form, headers), Void.class);

		assertThat(toCallback.getStatusCode())
				.as("IdP phải trả code về redirect_uri của ta")
				.isEqualTo(HttpStatus.FOUND);

		ResponseEntity<Void> afterCallback = get(
				onTestServer(toCallback.getHeaders().getLocation()), cookie);
		String sessionCookie = sessionCookie(afterCallback, cookie);

		// `.failureUrl("/?login=failed")` CŨNG là 302, nên chỉ kiểm status là để
		// lọt mọi thất bại. Phải kiểm ĐÍCH ĐẾN.
		assertThat(afterCallback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(afterCallback.getHeaders().getLocation().toString())
				.as("callback phải đổi code lấy token THÀNH CÔNG, không rơi vào failureUrl")
				.doesNotContain("login=failed");
		return sessionCookie;
	}

	/** URI tuyệt đối trỏ vào server test (cổng ngẫu nhiên). */
	private URI at(String pathAndQuery) {
		return URI.create(rest.getRootUri() + pathAndQuery);
	}

	/**
	 * Đổi host của một `Location` sang server test, giữ nguyên path + query THÔ.
	 *
	 * Cần thiết vì Task 11.5: ứng dụng dựng URL tuyệt đối từ
	 * `news.identity.public-base-url` (mặc định `localhost:8080`), còn IT chạy ở
	 * RANDOM_PORT. Host tuyệt đối đã có chốt chặn riêng ở `SecurityConfigTest`
	 * và `SsmClientRegistrationRepositoryTest`; ở đây ta kiểm LUỒNG.
	 *
	 * `getRawPath`/`getRawQuery` chứ không `getPath`/`getQuery`: `state` mang
	 * `%3D` ở cuối, và giải mã rồi mã hoá lại là cách chắc chắn làm hỏng nó.
	 */
	private URI onTestServer(URI location) {
		String query = location.getRawQuery();
		return at(query == null ? location.getRawPath()
				: location.getRawPath() + "?" + query);
	}

	/** Gộp mọi `Set-Cookie` của response lên trên cookie đang giữ. */
	private static String sessionCookie(ResponseEntity<?> response, String current) {
		List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		if (setCookies == null || setCookies.isEmpty()) {
			return current;
		}
		StringBuilder merged = new StringBuilder();
		for (String setCookie : setCookies) {
			if (!merged.isEmpty()) {
				merged.append("; ");
			}
			merged.append(setCookie.split(";", 2)[0]);
		}
		return merged.toString();
	}

	private ResponseEntity<Void> get(URI uri, String cookie) {
		return exchange(uri, HttpMethod.GET, cookie, Void.class);
	}

	private <T> ResponseEntity<T> exchange(URI uri, HttpMethod method, String cookie,
			Class<T> type) {
		HttpHeaders headers = new HttpHeaders();
		if (cookie != null) {
			headers.add(HttpHeaders.COOKIE, cookie);
			// CSRF của ta là `CookieCsrfTokenRepository.withHttpOnlyFalse()`, nên
			// SPA đọc cookie `XSRF-TOKEN` rồi gửi lại qua header. Bắt chước đúng
			// điều đó, nếu không mọi POST đều 403.
			csrfToken(cookie).ifPresent(t -> headers.add("X-XSRF-TOKEN", t));
		}
		return rest.exchange(uri, method, new HttpEntity<>(headers), type);
	}

	private static Optional<String> csrfToken(String cookie) {
		for (String part : cookie.split(";\\s*")) {
			if (part.startsWith("XSRF-TOKEN=")) {
				return Optional.of(part.substring("XSRF-TOKEN=".length()));
			}
		}
		return Optional.empty();
	}
}
