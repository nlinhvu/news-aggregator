package dev.linhvu.news_aggregator.platform;

import java.util.concurrent.TimeUnit;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.servlet.Filter;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpHttpSpanExporterBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Trên Lambda, execution environment ĐÓNG BĂNG ngay khi response trả.
 * `BatchSpanProcessor` mặc định ôm span trong buffer để gửi theo lô — nên span
 * của lượt invoke cuối cùng thường không bao giờ rời máy.
 *
 * Trên runtime thường, ADOT extension giải quyết bằng cách flush cuối mỗi invoke.
 * Container image KHÔNG nhận Lambda Layer (ADR-0006), và tự đóng gói lại collector
 * đã bị loại ở ADR-0016 Option C. Nên ứng dụng tự flush — và với LWA, ranh giới
 * invoke CHÍNH LÀ ranh giới HTTP response.
 *
 * Cùng họ với `.join()` bắt buộc ở `SummarizationQueue`: cả hai đều là chỗ mà
 * "gửi bất đồng bộ" im lặng không bao giờ hoàn thành vì môi trường biến mất.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

	/**
	 * Tham số là `Runnable` chứ không phải `SdkTracerProvider` để test gọi được
	 * mà không phải dựng cả SDK. Bean thật bơm vào một lambda gọi `forceFlush`.
	 */
	Filter traceFlushFilter(Runnable flush) {
		return (request, response, chain) -> {
			try {
				chain.doFilter(request, response);
			}
			finally {
				// TRONG finally: một invoke HỎNG là lúc trace đáng giá nhất.
				flush.run();
			}
		};
	}

	/**
	 * `ObjectProvider` chứ KHÔNG phải `SdkTracerProvider` trực tiếp: ADR-0016
	 * Option B tắt OTel trên prod bằng `OTEL_SDK_DISABLED=true`, Boot 4.1 map
	 * thành `management.opentelemetry.enabled=false`, và bean đó BIẾN MẤT theo.
	 * Đòi nó thẳng thì ứng dụng không khởi động được ở đúng kịch bản fallback —
	 * đo bằng `ObservabilityConfigTest.KhiOTelBiTat`.
	 *
	 * Giải một lần lúc dựng bean chứ không mỗi request: đây là đường chạy của mọi
	 * lượt invoke, và cửa chặn cold start ở Task 17 không có chỗ cho một lượt tra
	 * bean thừa.
	 */
	@Bean
	FilterRegistrationBean<Filter> traceFlushFilterRegistration(
			ObjectProvider<SdkTracerProvider> tracerProviders) {
		SdkTracerProvider tracerProvider = tracerProviders.getIfAvailable();
		FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(
				traceFlushFilter(tracerProvider == null
						? () -> { }
						: () -> tracerProvider.forceFlush().join(2, TimeUnit.SECONDS)));
		// THẤP NHẤT có thể: filter này phải bọc NGOÀI mọi filter khác để span của
		// chúng cũng nằm trong lô được flush.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	/**
	 * Cắm bộ gửi có ký SigV4 vào exporter OTLP của Boot. Không có bean này thì
	 * exporter dùng bộ gửi OkHttp mặc định, và mọi request tới X-Ray trả 403 —
	 * `BatchSpanProcessor` chỉ ghi một dòng WARN rồi vứt lô span, nên triệu chứng
	 * ở phía người đọc là X-Ray RỖNG chứ không phải một lỗi nào.
	 *
	 * Bean nhận `ObjectProvider` cùng lý do như filter ở trên: khi
	 * `management.opentelemetry.enabled=false` (ADR-0016 Option B) thì không có
	 * exporter nào để tuỳ biến, nhưng bean này vẫn được dựng.
	 */
	@Bean
	OtlpHttpSpanExporterBuilderCustomizer sigV4SpanSender(AwsCredentialsProvider credentials,
			AwsRegionProvider regions) {
		SigV4HttpSenderProvider sender = new SigV4HttpSenderProvider(credentials, regions);
		return (builder) -> builder.setComponentLoader(sender.componentLoader());
	}
}
