package dev.linhvu.news_aggregator.identity;

import java.io.IOException;

import dev.linhvu.news_aggregator.platform.RoleProfiles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mô hình BFF ([ADR-0018]): backend là OAuth2 Client, trình duyệt chỉ có cookie.
 *
 * Chỉ tồn tại ở hai profile phục vụ HTTP. `ingest`/`summarize` có chain RIÊNG
 * (`EventSecurityConfig`) — chúng KHÔNG được để trống, xem lý do ở đó.
 *
 * `@EnableSpringHttpSession` là thứ THẬT SỰ bật Spring Session, và nó phải nằm
 * ở đây chứ không sớm hơn. Task 9 thêm `spring-session-core` rồi tưởng thế là
 * xong — không phải: Boot 4 tách auto-config ra module `spring-boot-session`
 * riêng, nên trước dòng này `SessionRepositoryFilter` KHÔNG tồn tại và
 * `DynamoDbSessionRepository` là một bean không ai gọi (đã đo bằng
 * `getBeanNamesForType`, kết quả rỗng).
 */
@Configuration(proxyBeanMethods = false)
@Profile(RoleProfiles.HTTP)
@EnableSpringHttpSession
class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http,
			@Value("${news.identity.public-base-url}") String publicBaseUrl)
			throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
					// Đường đọc ẩn danh — liệt kê TƯỜNG MINH, không dựa vào thứ
					// tự matcher. `/api/articles` là sản phẩm chính và nó phải
					// mở với mọi người, mãi mãi (master §3.1).
					.requestMatchers("/api/articles", "/api/health", "/api/sources")
							.permitAll()
					.requestMatchers("/api/auth/**").permitAll()
					.requestMatchers("/admin/**").hasAuthority("ROLE_ops")
					.anyRequest().authenticated())
			.oauth2Login(oauth -> oauth
					// Hai baseUri này là lý do đường callback nằm trong /api/*.
					// Mặc định của Spring Security là /oauth2/authorization/* và
					// /login/oauth2/code/*, cả hai nằm ngoài /api/* nên CloudFront
					// sẽ route chúng sang bucket SPA và trả 404 (TDD §7).
					.authorizationEndpoint(e -> e.baseUri("/api/auth/login"))
					.redirectionEndpoint(e -> e.baseUri("/api/auth/callback/*"))
					// TUYỆT ĐỐI, cùng lý do với `AuthController.login()` — và đây
					// là chỗ Task 11.5 SỬA SÓT, phải trả giá bằng một lượt QA tay:
					// người dùng đăng nhập xong nhận `{"Message":"Forbidden"}`.
					//
					// Đường dẫn tương đối được servlet container biến thành tuyệt
					// đối bằng host của REQUEST, mà sau CloudFront host đó là
					// Function URL với `AuthType=AWS_IAM`. Trình duyệt không ký
					// SigV4 được nên Function URL trả đúng câu đó — một thông báo
					// không hề nhắc tới OAuth, redirect hay host, trong khi đăng
					// nhập ĐÃ THÀNH CÔNG ở phía server.
					.defaultSuccessUrl(publicBaseUrl + "/", true)
					.failureUrl(publicBaseUrl + "/?login=failed"))
			// 401 chứ KHÔNG 302 cho mọi request chưa xác thực. SPA gọi bằng
			// `fetch`; một redirect sang HTML là thứ nó không xử lý được.
			.exceptionHandling(e -> e.authenticationEntryPoint(
					new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.csrf(csrf -> csrf
					// httpOnly=false là CỐ Ý và chỉ cho cookie NÀY: SPA phải đọc
					// được để gửi lại qua header `X-XSRF-TOKEN`. Cookie phiên thì
					// ngược lại — httpOnly, và đó là toàn bộ giá trị của BFF.
					.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
					.csrfTokenRequestHandler(csrfRequestHandler()))
			// Bắt buộc đi kèm hai dòng trên, không phải trang trí. Xem
			// `CsrfCookieFilter`.
			.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
			.logout(logout -> logout.disable());   // đăng xuất do AuthController lo

		return http.build();
	}

	/**
	 * Tắt cơ chế nạp LƯỜI token của Spring Security 6+.
	 *
	 * Mặc định, token chỉ được sinh khi có ai đó ĐỌC nó trong lúc xử lý request.
	 * Không chỗ nào trong hệ này đọc — ta không render form phía server — nên
	 * cookie `XSRF-TOKEN` KHÔNG BAO GIỜ được phát, và SPA không có gì để gửi lại.
	 * Hệ quả đo được ở `LoginFlowIT`: `POST /api/auth/logout` bị `CsrfFilter`
	 * chặn, tức **nút đăng xuất không thể hoạt động từ trình duyệt**.
	 *
	 * Triệu chứng còn gây hiểu nhầm thêm một tầng: nó trả **401**, không phải
	 * 403. `CsrfFilter` từ chối TRƯỚC khi `SecurityContext` kịp được nạp (cũng
	 * lười), nên `ExceptionTranslationFilter` thấy một request ẩn danh và gọi
	 * entry point. Nhìn vào 401 mà đi truy phiên hỏng là truy sai chỗ.
	 */
	private static CsrfTokenRequestAttributeHandler csrfRequestHandler() {
		CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
		handler.setCsrfRequestAttributeName(null);
		return handler;
	}

	/**
	 * Đọc token ở MỌI request để `CookieCsrfTokenRepository` thực sự ghi cookie
	 * ra response.
	 *
	 * `getToken()` trông như một lời gọi thừa — nó chính là việc cần làm: token
	 * là một `Supplier` lười, và chỉ lời gọi này mới kích hoạt việc sinh cùng
	 * việc phát cookie. Đây là khuôn Spring Security công bố cho SPA.
	 */
	private static final class CsrfCookieFilter extends OncePerRequestFilter {

		@Override
		protected void doFilterInternal(HttpServletRequest request,
				HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
			if (token != null) {
				token.getToken();
			}
			chain.doFilter(request, response);
		}
	}
}
