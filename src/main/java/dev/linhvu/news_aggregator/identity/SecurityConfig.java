package dev.linhvu.news_aggregator.identity;

import dev.linhvu.news_aggregator.platform.RoleProfiles;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

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
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
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
					.defaultSuccessUrl("/", true)
					.failureUrl("/?login=failed"))
			// 401 chứ KHÔNG 302 cho mọi request chưa xác thực. SPA gọi bằng
			// `fetch`; một redirect sang HTML là thứ nó không xử lý được.
			.exceptionHandling(e -> e.authenticationEntryPoint(
					new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.csrf(csrf -> csrf
					// httpOnly=false là CỐ Ý và chỉ cho cookie NÀY: SPA phải đọc
					// được để gửi lại qua header `X-XSRF-TOKEN`. Cookie phiên thì
					// ngược lại — httpOnly, và đó là toàn bộ giá trị của BFF.
					.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
			.logout(logout -> logout.disable());   // đăng xuất do AuthController lo

		return http.build();
	}
}
