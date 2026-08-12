package dev.linhvu.news_aggregator.platform;

import java.util.concurrent.ExecutorService;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;

import org.springframework.stereotype.Component;

/**
 * Bắc trace context (và mọi thứ khác trong MDC) sang thread do ứng dụng tự dựng.
 *
 * Boot auto-config gắn `ContextPropagatingTaskDecorator` cho các `TaskExecutor`
 * do Spring quản. `IngestionRunner` dựng `Executors.newVirtualThreadPerTaskExecutor()`
 * BẰNG TAY nên nằm ngoài tầm đó, và hệ quả là mọi dòng log bên trong vòng fetch
 * song song mất `trace_id` — mất đúng ở chỗ hay hỏng nhất.
 *
 * Thứ thật sự được chụp là OBSERVATION đang mở, không phải span:
 * `ObservationThreadLocalAccessor` là `ThreadLocalAccessor` duy nhất có mặt trên
 * classpath này. Nên `wrap` chỉ có tác dụng khi thread nộp đang nằm trong một
 * observation — với ứng dụng này là luôn luôn, vì mọi lượt chạy vào bằng HTTP.
 *
 * Đặt ở `platform` vì master §5 giao cho nó phần observability, và vì Phase 5
 * (scraping) sẽ fetch song song theo đúng khuôn này. Cùng lý do Phase 3 §17 #15
 * đặt `ChatClient` ở đây.
 */
@Component
public class TracePropagation {

	private final ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();

	/**
	 * Bọc một executor để mọi task nộp vào nó chạy với context của thread NỘP,
	 * không phải context rỗng của thread chạy.
	 *
	 * Executor trả về vẫn đóng được bằng try-with-resources y như bản gốc, nên
	 * chỗ gọi không phải đổi hình dạng.
	 */
	public ExecutorService wrap(ExecutorService delegate) {
		return ContextExecutorService.wrap(delegate, snapshots);
	}
}
