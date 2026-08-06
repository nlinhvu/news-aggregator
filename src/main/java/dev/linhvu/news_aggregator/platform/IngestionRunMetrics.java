package dev.linhvu.news_aggregator.platform;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Số đếm của MỘT lượt ingestion.
 *
 * Tồn tại ở `platform` vì `added` là fact của `catalog` trong khi response của
 * `/events` do `ingestion` trả. Cho `ingestion` nghe `catalog :: events` sẽ
 * tạo cycle NGAY Ở PHASE 2 (`catalog → ingestion` đã có), sớm hơn cả cycle mà
 * ADR-0012 chấp nhận cho Phase 3. `platform` được mọi module dùng và không phụ
 * thuộc ai, còn master §5 giao cho nó phần observability.
 *
 * `AtomicInteger` chứ không `int`: Task 17 fetch song song bằng virtual thread,
 * và listener chạy trên chính thread đó.
 *
 * BẮT BUỘC gọi `reset()` ở đầu mỗi lượt — Lambda dùng lại execution environment
 * nên bean singleton này sống qua nhiều lượt invoke.
 */
@Component
public class IngestionRunMetrics {

	private final AtomicInteger added = new AtomicInteger();

	public void reset() {
		added.set(0);
	}

	public void countAdded() {
		added.incrementAndGet();
	}

	public int added() {
		return added.get();
	}
}
