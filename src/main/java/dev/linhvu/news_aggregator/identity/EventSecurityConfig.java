package dev.linhvu.news_aggregator.identity;

import dev.linhvu.news_aggregator.platform.RoleProfiles;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Filter chain của hai function event-driven. **Không có nó, ingestion chết
 * trong im lặng.**
 *
 * <p><b>Vì sao phải khai tường minh thay vì "không cấu hình gì".</b> Spring
 * Security trên classpath mà không `SecurityFilterChain` nào khớp profile thì
 * Boot dựng chain MẶC ĐỊNH: `anyRequest().authenticated()` + CSRF. Đã ĐO ở
 * profile `ingest`: `POST /events` trả **403** (CSRF chặn trước), `GET
 * /api/health` trả **401**. `SecurityConfig` là `@Profile(HTTP)` nên nó không
 * che hai vai này.
 *
 * <p><b>Vì sao đó là chế độ hỏng tệ nhất trong cả phase.</b> EventBridge
 * Scheduler gọi Lambda BẤT ĐỒNG BỘ và Lambda Web Adapter nuốt HTTP status —
 * invoke vẫn được tính là thành công. Không alarm nào kêu, DLQ không nhận gì,
 * `AsyncEventsDropped` không nhúc nhích. Triệu chứng duy nhất là bảng
 * `articles` ngừng có bài mới.
 *
 * <p><b>Vì sao `permitAll` ở đây KHÔNG phải là mở toang.</b> Hai function này
 * không có Function URL và không nằm sau CloudFront; đường vào duy nhất là
 * `lambda:InvokeFunction`, tức xác thực đã xảy ra ở tầng IAM trước khi có
 * request HTTP nào. LWA dựng request HTTP ở localhost TRONG chính container —
 * thêm một tầng xác thực ở đây là kiểm tra một biên giới không tồn tại. Ngưỡng
 * phải đọc lại dòng này: ngày ai đó gắn Function URL cho `ingest`/`summarize`.
 *
 * <p>CSRF tắt vì không có trình duyệt nào ở đầu kia — payload đến từ Scheduler
 * và SQS. STATELESS vì hai vai này không có session store: `AppStack` chỉ cấp
 * quyền ghi bảng `sessions` cho `webRole`.
 */
@Configuration(proxyBeanMethods = false)
@Profile(RoleProfiles.EVENT_DRIVEN)
class EventSecurityConfig {

	@Bean
	SecurityFilterChain eventFilterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.csrf(csrf -> csrf.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

		return http.build();
	}
}
