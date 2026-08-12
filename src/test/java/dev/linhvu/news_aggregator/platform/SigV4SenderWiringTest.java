package dev.linhvu.news_aggregator.platform;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `SigV4HttpSenderProviderTest` chứng minh lớp ký ĐÚNG. Test này chứng minh nó
 * ĐƯỢC CẮM VÀO — hai việc khác nhau, và cái thứ hai mới là chỗ hỏng im lặng: nếu
 * bean `OtlpHttpSpanExporterBuilderCustomizer` không được auto-config nhặt, hoặc
 * `setComponentLoader` bị bỏ, thì exporter dùng bộ gửi OkHttp mặc định và mọi
 * request tới X-Ray trả 403 — trong khi cả ba unit test kia vẫn xanh vĩnh viễn.
 *
 * ⚠️ `User-Agent` KHÔNG dùng được làm dấu vân tay, dù trực giác nói ngược lại:
 * `OTel-OTLP-Exporter-Java/1.62.0` là header do chính exporter bơm vào config, nên
 * bộ gửi nào cũng gửi đúng chuỗi đó. Đã đo. Thứ phân biệt được là
 * `componentLoader` trong `toString()` của exporter — chính nó quyết định bộ gửi.
 */
@SpringBootTest
class SigV4SenderWiringTest {

	private static HttpServer server;

	private static final List<String> authorizations = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void dungServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/v1/traces", (exchange) -> {
			authorizations.add(String.valueOf(
					exchange.getRequestHeaders().getFirst("Authorization")));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void dongServer() {
		server.stop(0);
	}

	@DynamicPropertySource
	static void endpoint(DynamicPropertyRegistry registry) {
		registry.add("management.opentelemetry.tracing.export.otlp.endpoint",
				() -> "http://localhost:" + server.getAddress().getPort() + "/v1/traces");
	}

	@Autowired
	Tracer tracer;

	@Autowired
	SdkTracerProvider tracerProvider;

	@Autowired
	OtlpHttpSpanExporter exporter;

	@Test
	void exporter_dung_component_loader_cua_ta() {
		assertThat(this.exporter.toString())
				.as("gỡ bean customizer thì đây là ComponentLoader mặc định của OTel")
				.contains("componentLoader=" + SigV4HttpSenderProvider.class.getName());
	}

	@Test
	void span_that_su_roi_khoi_tien_trinh_va_khong_ky_o_local() {
		var span = this.tracer.nextSpan().name("wiring");
		span.start().end();

		// `forceFlush` chứ không chờ batch timer: cùng cơ chế mà `ObservabilityConfig`
		// dùng ở ranh giới response, nên test này cũng là một phép thử của nó.
		assertThat(this.tracerProvider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess())
				.as("export phải thành công — server test luôn trả 200")
				.isTrue();

		assertThat(authorizations)
				.as("endpoint local KHÔNG được ký: máy dev không có credential AWS")
				.isNotEmpty()
				.containsOnly("null");
	}
}
