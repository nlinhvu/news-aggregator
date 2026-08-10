package dev.linhvu.news_aggregator.summarization;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

// Số đếm của MỘT lượt. Khác `IngestionRunMetrics` ở chỗ nó nội bộ trong module
// này — không module nào khác cần đọc, nên nó không cần ở `platform`.
//
// BẮT BUỘC gọi `reset()` ở đầu mỗi lượt: Lambda dùng lại execution environment
// nên bean singleton này sống qua nhiều lượt invoke. Phase 2 đã trả giá này với
// `IngestionRunMetrics`.
@Component
class SummarizationRunMetrics {

	private final AtomicInteger enqueued = new AtomicInteger();

	void reset() {
		enqueued.set(0);
	}

	int countEnqueued() {
		return enqueued.incrementAndGet();
	}

	int enqueued() {
		return enqueued.get();
	}
}
