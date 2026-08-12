package dev.linhvu.news_aggregator.platform;

import java.util.Arrays;
import java.util.List;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hai bean này là nền của toàn bộ nửa sau Phase 4: Task 18 dùng `Tracer` để bắc
 * context sang virtual thread, Task 19/20 dùng `Propagator` để đưa `traceparent`
 * qua SQS. Nếu starter không được auto-config đúng, mọi task đó hỏng theo một
 * cách khó chẩn đoán — nên kiểm ngay tại đây, ở task thêm dependency.
 *
 * Và mục quan trọng nhất: `traceId` phải nằm trong MDC. Đó là toàn bộ cơ chế
 * biến `logging.structured.format.console: ecs` (có từ Phase 1) thành yêu cầu
 * master §8.2 *"mọi dòng log mang trace_id"* — không có dòng code nào của ta
 * tham gia vào việc đó, nên cũng không có gì đỏ nếu nó ngừng hoạt động.
 */
@SpringBootTest
class TracingSmokeTest {

	@Autowired
	Tracer tracer;

	@Autowired
	io.micrometer.tracing.propagation.Propagator propagator;

	@Autowired
	ApplicationContext context;

	@Test
	void trace_id_nam_trong_mdc_khi_co_span() {
		var span = tracer.nextSpan().name("test");
		try (var ignored = tracer.withSpan(span.start())) {
			assertThat(MDC.get("traceId"))
					.as("ecs structured logging lấy traceId từ MDC")
					.isNotBlank()
					.isEqualTo(span.context().traceId());
		}
		finally {
			span.end();
		}
	}

	@Test
	void propagator_ton_tai_de_task_19_va_20_dung_duoc() {
		assertThat(propagator).isNotNull();
	}

	/**
	 * Chốt chặn DUY NHẤT cho tên key OTLP trong `application.yaml`. Boot 4.1 dựng
	 * exporter chỉ khi `management.opentelemetry.tracing.export.otlp.endpoint` có
	 * giá trị; viết tên Boot 3 (`management.otlp.tracing.endpoint`, nay deprecate
	 * mức `error`) hay gõ sai một đoạn đường dẫn thì Spring KHÔNG kêu gì cả —
	 * `Tracer` vẫn có, MDC vẫn có `traceId`, hai test trên vẫn xanh, chỉ là span
	 * chết trong bộ nhớ và Tempo/X-Ray rỗng.
	 */
	@Test
	void exporter_ton_tai_nen_span_thuc_su_roi_khoi_tien_trinh() {
		assertThat(kieuCuaMoiBean())
				.as("sai tên key endpoint thì không có exporter nào, mà cũng không có lỗi nào")
				.contains("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter");
	}

	/**
	 * Starter kéo theo `micrometer-registry-otlp`, và registry đó BẬT SẴN với url
	 * mặc định `localhost:4318/v1/metrics` — trên Lambda là mỗi phút một POST vào
	 * chỗ không tồn tại. Log OTLP cũng vậy khi gặp env var
	 * `OTEL_EXPORTER_OTLP_ENDPOINT` dạng CHUNG, thứ mà Boot 4.1 map vào cả ba
	 * signal cùng lúc. Cả hai bị tắt tường minh trong `application.yaml`; test này
	 * giữ chúng tắt.
	 */
	@Test
	void chi_export_trace_khong_export_metric_va_log() {
		assertThat(kieuCuaMoiBean())
				.as("metric đi bằng metric filter trên log (Task 11), không bằng OTLP")
				.noneMatch(kieu -> kieu.endsWith("OtlpMeterRegistry"))
				.as("log đi bằng stdout ECS JSON → CloudWatch")
				.noneMatch(kieu -> kieu.contains("OtlpHttpLogRecordExporter"));
	}

	/**
	 * So bằng TÊN chuỗi chứ không bằng `getBeanNamesForType(X.class)`: cả ba class
	 * OTLP ở trên đều là runtime-scope của starter, không có trên test compile
	 * classpath — `import` chúng là lỗi biên dịch.
	 */
	private List<String> kieuCuaMoiBean() {
		return Arrays.stream(context.getBeanDefinitionNames())
				.map(context::getType)
				.filter(kieu -> kieu != null)
				.map(Class::getName)
				.toList();
	}
}
