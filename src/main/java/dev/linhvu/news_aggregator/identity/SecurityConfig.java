package dev.linhvu.news_aggregator.identity;

import java.io.IOException;

import dev.linhvu.news_aggregator.platform.NewsFeature;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import jakarta.servlet.DispatcherType;
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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatchers;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
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
			@Value("${news.identity.public-base-url}") String publicBaseUrl,
			@Value("${news.identity.secure-cookies}") boolean secureCookies)
			throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
					// ERROR dispatch phải đi lọt, nếu không MỌI lỗi trên đường ẩn
					// danh biến thành 401. Spring Security lọc cả dispatch type
					// này (mặc định từ Boot 3), mà `/error` thì không ai cấp
					// quyền, nên request ẩn danh bị entry point nuốt.
					//
					// ĐÃ ĐO trên dev 2026-08-13, sau khi Task 10+11 đưa Spring
					// Security vào:
					//   /api/khong-ton-tai      → 401  (phải là 404)
					//   /api/articles?limit=abc → 401  (phải là 400)
					//
					// Vế nguy hiểm không phải 404: một lỗi 500 trên đường đọc công
					// khai cũng sẽ hiện ra là 401, tức một sự cố trông y hệt vấn
					// đề đăng nhập. Phase này đã mất thời gian đúng một lần vì
					// nhìn 401 rồi truy sai chỗ (xem `CsrfCookieFilter`).
					.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
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
			// TẮT request cache, và đây là một quyết định về CHI PHÍ.
			//
			// `ExceptionTranslationFilter` gọi `HttpSessionRequestCache.saveRequest()`
			// TRƯỚC khi giao cho entry point, mà việc lưu đó TẠO HTTP session. SPA
			// gọi `/api/me` ở mọi lần tải trang, nên mỗi khách vãng lai — người
			// chưa từng đăng nhập — sinh một `PutItem` lên bảng `sessions` và một
			// dòng sống 30 ngày. Đã đo trên prod 2026-08-13: một lượt QA của MỘT
			// người để lại 7 phiên ẩn danh.
			//
			// Saved request đó KHÔNG BAO GIỜ được dùng tới: đăng nhập đi qua
			// `/api/auth/login` tường minh, và `defaultSuccessUrl(..., true)` có
			// `alwaysUse=true` nên luôn ghi đè đích đến. Ta trả tiền cho một cơ
			// chế không ai đọc.
			//
			// Đây là driver #3 của ADR-0018 — "BFF không được làm feed công khai
			// đắt hơn" — và `AnonymousReadTest` canh nó.
			.requestCache(cache -> cache.disable())
			.csrf(csrf -> csrf
					// httpOnly=false là CỐ Ý và chỉ cho cookie NÀY: SPA phải đọc
					// được để gửi lại qua header `X-XSRF-TOKEN`. Cookie phiên thì
					// ngược lại — httpOnly, và đó là toàn bộ giá trị của BFF.
					.csrfTokenRepository(csrfTokenRepository(secureCookies))
					.csrfTokenRequestHandler(csrfRequestHandler()))
			// Bắt buộc đi kèm hai dòng trên, không phải trang trí. Xem
			// `CsrfCookieFilter`.
			.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
			// ĐẦU chain, trước cả `DisableEncodeUrlFilter`. Xem `UserAccountsGate`
			// — vị trí này là một phần của hành vi, không phải chi tiết sắp xếp.
			.addFilterBefore(new UserAccountsGate(), DisableEncodeUrlFilter.class)
			.logout(logout -> logout.disable());   // đăng xuất do AuthController lo

		return http.build();
	}

	/**
	 * Cookie PHIÊN — `Secure` phải được BẬT TƯỜNG MINH, không suy ra từ request.
	 *
	 * `DefaultCookieSerializer` để `useSecureCookie = null`, tức rơi về
	 * `request.isSecure()`. Sau CloudFront → Lambda Function URL → LWA, request
	 * tới Tomcat là HTTP trần nên lời gọi đó trả `false` và thuộc tính `Secure`
	 * BIẾN MẤT. Đo được trên prod 2026-08-13:
	 *
	 * <pre>
	 *   set-cookie: SESSION=…; Path=/; HttpOnly; SameSite=Lax
	 * </pre>
	 *
	 * `EdgeStack` dùng `REDIRECT_TO_HTTPS`, nghĩa là request `http://` VẪN tới
	 * CloudFront kèm cookie plaintext rồi mới bị 301 — cookie đã lên đường trước
	 * lúc redirect. Với mô hình BFF thì cookie phiên chính là credential.
	 *
	 * Chỉ gọi `setUseSecureCookie` khi bật: để `false` tường minh sẽ VÔ HIỆU cơ
	 * chế mặc định, tức một lần chạy local qua HTTPS cũng mất `Secure`. Ngoài
	 * AWS thì "theo request" vẫn là hành vi đúng.
	 *
	 * Mọi thuộc tính khác giữ nguyên mặc định của Spring Session —
	 * `HttpOnly`, `SameSite=Lax` — và `CookieSecurityIT` canh cả cụm.
	 */
	@Bean
	CookieSerializer cookieSerializer(
			@Value("${news.identity.secure-cookies}") boolean secureCookies) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		if (secureCookies) {
			serializer.setUseSecureCookie(true);
		}
		return serializer;
	}

	/**
	 * Cookie CSRF đi đường HOÀN TOÀN KHÁC cookie phiên — `CookieCsrfTokenRepository`
	 * của Spring Security, không phải `CookieSerializer` của Spring Session — nên
	 * nó cần lời sửa riêng. Sửa một đường rồi tưởng xong là cách bỏ sót đường kia.
	 *
	 * `httpOnly=false` giữ nguyên và vẫn CỐ Ý: SPA phải đọc được token để gửi
	 * lại qua header `X-XSRF-TOKEN`.
	 */
	private static CookieCsrfTokenRepository csrfTokenRepository(boolean secureCookies) {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		if (secureCookies) {
			repository.setCookieCustomizer(cookie -> cookie.secure(true));
		}
		return repository;
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
	 * `USER_ACCOUNTS` OFF ⇒ toàn bộ bề mặt đăng nhập KHÔNG TỒN TẠI.
	 *
	 * <p><b>404, không phải 403.</b> Flag tắt nghĩa là tính năng không có mặt,
	 * không phải "có mặt nhưng anh không được vào" — cùng cách Phase 3 giấu
	 * `summary`: vắng mặt hoàn toàn, không phải null. SPA cũng đọc đúng hai mã
	 * này: `401` = ẩn danh nên hiện nút "Đăng nhập", `404` = tắt nên không hiện
	 * nút nào.
	 *
	 * <p><b>Đứng ĐẦU chain là một phần của hành vi.</b> Đứng sau `CsrfFilter`
	 * thì `POST /api/auth/logout` trả 403 vì thiếu token — một câu trả lời khẳng
	 * định endpoint CÓ TỒN TẠI. Đứng sau `SecurityContextHolderFilter` thì mỗi
	 * request vào bề mặt đã tắt còn kéo theo một lượt `GetItem` lên bảng
	 * `sessions`, tức trả tiền cho thứ đã tắt.
	 *
	 * <p>`shouldNotFilter` lọc theo đường dẫn TRƯỚC, nên flag chỉ được đọc trên
	 * `/api/auth/**` và `/api/me`. Đọc flag là một `GetItem` (`TogglzConfig` cố ý
	 * không cache), và `/api/articles` với `/api/health` không được đắt thêm vì
	 * một tính năng chúng không dùng — xem `AnonymousReadTest`.
	 *
	 * <p>`setStatus` chứ KHÔNG `sendError`: `sendError` forward sang `/error`,
	 * tức phụ thuộc vào `dispatcherTypeMatchers(ERROR).permitAll()` ở trên để
	 * không bị nuốt thành 401 — mà MockMvc không thực hiện forward đó nên test sẽ
	 * không bao giờ nói thật về nó (xem `ErrorStatusIT`). Thân rỗng cũng là hình
	 * dạng `HttpStatusEntryPoint` và `/api/me` đang dùng cho 401.
	 */
	private static final class UserAccountsGate extends OncePerRequestFilter {

		// Hai đường của slice 4 nằm ở đây vì chúng KHÔNG TỒN TẠI nếu không có
		// tài khoản: `USER_ACCOUNTS` tắt thì SPA không có ai để cá nhân hoá, và
		// TDD §5.4 quy định chúng trả 404 chứ không 401 — `404` là "tính năng
		// không có", `401` là "anh chưa đăng nhập", và SPA phân biệt hai cái đó
		// để quyết định có hiện hàng chip hay không.
		//
		// `/api/sources` KHÔNG nằm ở đây: hàng chip hiện dạng mờ cho người ẩn
		// danh, nên danh sách nguồn phải sống kể cả khi đăng nhập bị tắt.
		private static final RequestMatcher SURFACE = RequestMatchers.anyOf(
				PathPatternRequestMatcher.pathPattern("/api/auth/**"),
				PathPatternRequestMatcher.pathPattern("/api/me"),
				PathPatternRequestMatcher.pathPattern("/api/my/**"),
				PathPatternRequestMatcher.pathPattern("/api/preferences/**"));

		@Override
		protected boolean shouldNotFilter(HttpServletRequest request) {
			return !SURFACE.matches(request);
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request,
				HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if (enabled()) {
				chain.doFilter(request, response);
				return;
			}
			response.setStatus(HttpStatus.NOT_FOUND.value());
		}

		/**
		 * Fail-closed, y hệt `ArticleController` của Phase 3 (TDD §5.4). Lỗi đọc
		 * flag KHÔNG được làm hỏng cả trang — và ở đây "closed" nghĩa là không có
		 * đăng nhập, chứ không phải không có site.
		 */
		private static boolean enabled() {
			try {
				return NewsFeature.USER_ACCOUNTS.isActive();
			}
			catch (RuntimeException e) {
				return false;
			}
		}
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
