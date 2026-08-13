package dev.linhvu.news_aggregator.identity;

import java.net.URI;
import java.util.List;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mọi cookie ta phát phải có `Secure` khi chạy trên AWS — **kể cả khi request
 * tới ứng dụng không phải HTTPS**.
 *
 * <p><b>Vế in đậm là toàn bộ lý do test này tồn tại.</b> Sau CloudFront →
 * Lambda Function URL → LWA, request tới Tomcat là HTTP trần, nên
 * `request.isSecure()` trả `false`. `DefaultCookieSerializer` để
 * `useSecureCookie = null` tức rơi về đúng lời gọi đó, và
 * `CookieCsrfTokenRepository` cũng vậy. Kết quả đo được trên prod 2026-08-13:
 *
 * <pre>
 *   set-cookie: SESSION=…; Path=/; HttpOnly; SameSite=Lax
 * </pre>
 *
 * Thiếu `Secure`, trong khi `EdgeStack` dùng `REDIRECT_TO_HTTPS` — nghĩa là
 * request `http://` VẪN tới CloudFront kèm cookie ở dạng plaintext rồi mới bị
 * 301. Cookie phiên là credential của cả mô hình BFF ([ADR-0018]).
 *
 * <p><b>Vì sao không test nào cũ bắt được.</b> `SecurityConfigTest` và
 * `LoginFlowIT` chỉ khẳng định `HttpOnly`. TDD §"local vs AWS" đã ghi đúng rủi
 * ro này kèm câu *"test tích hợp phải khẳng định `Secure` bật ở profile `aws`"* —
 * và câu đó không ai cài. Đây là món nợ ấy.
 *
 * <p><b>Vì sao là IT chứ không phải MockMvc, và vì sao không kiểm bean.</b> Gọi
 * thẳng `cookieSerializer.writeCookieValue(...)` chỉ chứng minh BEAN cư xử đúng,
 * không chứng minh Spring Session DÙNG nó — đúng chế độ hỏng đã trả giá ở Task 9
 * (`spring-session-core` có bean nhưng không gì nối vào HTTP). Ở đây cookie phải
 * đi ra từ filter chain thật, trên một server HTTP thật, tức đúng điều kiện
 * `isSecure() == false` của prod.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		// Trên AWS, property này = true nhờ `application-aws.yaml`
		// (`SPRING_PROFILES_ACTIVE=aws,<role>` — xem `AppStack`). Bật bằng property
		// chứ không bằng `@ActiveProfiles("aws")`: profile đó còn kéo theo region
		// tĩnh us-east-1, thứ đá nhau với endpoint của Floci.
		properties = "news.identity.secure-cookies=true")
@AutoConfigureTestRestTemplate
@ActiveProfiles(RoleProfiles.WEB)
@Import({ FlociTestConfiguration.class, MockOAuth2ServerConfiguration.class })
class CookieSecurityIT {

	@Autowired
	TestRestTemplate autowired;

	private TestRestTemplate rest;

	@BeforeEach
	void khongDiTheoRedirect() {
		this.rest = autowired.withRedirects(HttpRedirects.DONT_FOLLOW);
	}

	/**
	 * Cookie PHIÊN — thứ mang credential, nên là vế quan trọng hơn.
	 *
	 * Nó chỉ được phát ở entry point của Spring Security, chỗ cất
	 * `OAuth2AuthorizationRequest` (state + PKCE verifier) vào phiên. Một `GET
	 * /api/articles` không tạo phiên nào, nên kiểm ở đó là kiểm vào chỗ trống.
	 */
	@Test
	void cookie_phien_co_Secure_du_request_di_bang_http() {
		// Chặng 1 chỉ là cầu nối (`AuthController.login`), chưa có phiên nào.
		ResponseEntity<Void> toEntryPoint = get(at("/api/auth/login"));

		ResponseEntity<Void> toIdp = get(
				onTestServer(toEntryPoint.getHeaders().getLocation()));

		List<String> setCookies = toIdp.getHeaders().get(HttpHeaders.SET_COOKIE);

		// Chốt chống test rỗng: thiếu dòng này thì một response KHÔNG phát cookie
		// nào cũng làm `allSatisfy` bên dưới xanh.
		assertThat(setCookies)
				.as("phải có cookie phiên thì mới có gì để kiểm")
				.isNotNull()
				.anySatisfy(c -> assertThat(c).startsWith("SESSION="));
		assertThat(setCookies)
				.as("server test chạy HTTP trần — đúng điều kiện của prod sau LWA, "
						+ "và `Secure` vẫn phải có mặt")
				.allSatisfy(c -> assertThat(c).contains("Secure"));
	}

	/**
	 * Cookie CSRF đi đường khác hẳn — `CookieCsrfTokenRepository`, không phải
	 * `CookieSerializer` của Spring Session — nên nó cần một khẳng định riêng.
	 * Sửa một đường mà quên đường kia là chuyện đã xảy ra đúng một lần ở đây.
	 *
	 * `/api/articles` là chỗ chắc chắn phát nó: `CsrfCookieFilter` chạy trên MỌI
	 * request, còn phiên thì đường này không tạo.
	 */
	@Test
	void cookie_csrf_co_Secure_du_request_di_bang_http() {
		ResponseEntity<Void> feed = get(at("/api/articles?limit=1"));

		List<String> setCookies = feed.getHeaders().get(HttpHeaders.SET_COOKIE);

		assertThat(setCookies)
				.as("`CsrfCookieFilter` phải phát XSRF-TOKEN ở mọi request")
				.isNotNull()
				.anySatisfy(c -> assertThat(c).startsWith("XSRF-TOKEN="));
		assertThat(setCookies).allSatisfy(c -> assertThat(c).contains("Secure"));
	}

	private ResponseEntity<Void> get(URI uri) {
		return rest.exchange(uri, HttpMethod.GET, null, Void.class);
	}

	/** URI tuyệt đối trỏ vào server test (cổng ngẫu nhiên). */
	private URI at(String pathAndQuery) {
		return URI.create(rest.getRootUri() + pathAndQuery);
	}

	/**
	 * Đổi host của một `Location` sang server test, giữ nguyên path + query THÔ.
	 * Cùng lý do như `LoginFlowIT`: ứng dụng dựng URL tuyệt đối từ
	 * `news.identity.public-base-url` (`localhost:8080`), còn IT chạy ở
	 * RANDOM_PORT.
	 */
	private URI onTestServer(URI location) {
		String query = location.getRawQuery();
		return at(query == null ? location.getRawPath()
				: location.getRawPath() + "?" + query);
	}
}
