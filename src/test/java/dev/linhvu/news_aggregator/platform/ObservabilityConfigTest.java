package dev.linhvu.news_aggregator.platform;

import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityConfigTest {

	/**
	 * Chế độ hỏng này KHÔNG có triệu chứng nhìn thấy được: không lỗi, không log,
	 * chỉ là trace lỗ chỗ hoặc mất hẳn — và người đọc trace sẽ tưởng hệ thống
	 * không làm gì ở đoạn đó.
	 *
	 * Vì thế nghiệm thu của Task 17 là *"span CUỐI CÙNG có mặt trong X-Ray"*,
	 * không phải *"không thấy lỗi"*. Test này chỉ canh phần kiểm được ở local:
	 * filter có được gọi đúng một lần mỗi request và có gọi flush không.
	 */
	@Test
	void the_filter_flushes_exactly_once_per_request() throws Exception {
		AtomicInteger flushes = new AtomicInteger();
		Filter filter = new ObservabilityConfig().traceFlushFilter(flushes::incrementAndGet);

		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
				(req, res) -> { });

		assertThat(flushes.get()).isEqualTo(1);
	}

	/**
	 * Flush phải chạy KỂ CẢ khi chuỗi filter ném. Một invoke hỏng là lúc trace
	 * đáng giá nhất — mất nó ở đúng chỗ đó là mất toàn bộ giá trị.
	 */
	@Test
	void the_filter_still_flushes_when_the_chain_throws() {
		AtomicInteger flushes = new AtomicInteger();
		Filter filter = new ObservabilityConfig().traceFlushFilter(flushes::incrementAndGet);

		assertThatThrownBy(() -> filter.doFilter(
				new MockHttpServletRequest(), new MockHttpServletResponse(),
				(req, res) -> { throw new IllegalStateException("bùm"); }))
				.isInstanceOf(IllegalStateException.class);

		assertThat(flushes.get()).isEqualTo(1);
	}

	/**
	 * ADR-0016 Option B tắt export trên prod bằng `OTEL_SDK_DISABLED=true`, thứ mà
	 * Boot 4.1 map thẳng thành `management.opentelemetry.enabled=false`. Nếu bean
	 * `SdkTracerProvider` biến mất theo cờ đó thì một `@Bean` ĐÒI nó sẽ làm ứng
	 * dụng KHÔNG KHỞI ĐỘNG ĐƯỢC — đúng vào kịch bản fallback, tức lúc không ai
	 * muốn thêm một sự cố nữa.
	 */
	@Nested
	@SpringBootTest(properties = "management.opentelemetry.enabled=false")
	class KhiOTelBiTat {

		@Autowired
		ApplicationContext context;

		@Test
		void the_application_still_starts_up() {
			assertThat(context.getBeanNamesForType(SdkTracerProvider.class))
					.as("cờ tắt phải thật sự gỡ bean, nếu không test này không canh gì cả")
					.isEmpty();
			assertThat(context.getBean("traceFlushFilterRegistration")).isNotNull();
		}
	}

	/**
	 * Mặt còn lại của cùng một câu hỏi: khi OTel BẬT thì `getIfAvailable()` phải
	 * trả về tracer provider thật, nếu không filter im lặng thành no-op và chế độ
	 * hỏng mà cả task này sinh ra để chặn quay lại nguyên vẹn.
	 */
	@Nested
	@SpringBootTest
	class WhenOTelEnabled {

		@Autowired
		ApplicationContext context;

		@Test
		void a_real_tracer_provider_exists_so_flush_can_work() {
			assertThat(context.getBeanNamesForType(SdkTracerProvider.class)).hasSize(1);
			assertThat(context.getBean("traceFlushFilterRegistration")).isNotNull();
		}
	}
}
