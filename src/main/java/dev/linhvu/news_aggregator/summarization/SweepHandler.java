package dev.linhvu.news_aggregator.summarization;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.SummarizableArticle;
import dev.linhvu.news_aggregator.platform.EventJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

/**
 * PRODUCER #2 — lưới an toàn (ADR-0014). Nhặt article còn thiếu tóm tắt trong
 * cửa sổ thời gian: bài về lúc flag tắt, bài mà model hỏng ở lượt trước, bài
 * mà hạn mức lượt trước chặn lại.
 *
 * Nó KHÔNG phải đường chính — `ArticleAddedListener` mới là. Vì thế nó chạy
 * thưa (prod 6h) chứ không cùng nhịp với ingest: ở nhịp 1h, một bài hỏng vĩnh
 * viễn sinh 48 message DLQ trong cửa sổ 48h và DLQ mất hẳn tác dụng làm tín hiệu.
 */
@Component
class SweepHandler implements EventJobHandler {

	private static final Logger log = LoggerFactory.getLogger(SweepHandler.class);

	static final String JOB = "summarize-sweep";

	private final ArticleCatalog catalog;
	private final SummarizationQueue queue;
	private final SummarizationRunMetrics metrics;
	private final Duration defaultWindow;
	private final int maxPerRun;

	// `@Autowired` BẮT BUỘC: class có HAI constructor nên Spring không suy ra
	// được điểm inject, và nó rơi về constructor không tham số —
	// `NoSuchMethodException`. Task 10 đã trả giá đúng chỗ này với `Summarizer`.
	// Khác một điểm: handler này KHÔNG `@Lazy` và `EventsController` nhận
	// `List<EventJobHandler>`, nên lỗi nổ ngay ở `contextLoads` thay vì chờ tới
	// lượt invoke đầu tiên.
	//
	// Cửa sổ nhận vào dạng `String` rồi tự parse, KHÔNG phải `Duration`: để
	// Spring convert thì signature trùng khít constructor dưới, và hai
	// constructor giống hệt nhau thì không compile được.
	@Autowired
	SweepHandler(ArticleCatalog catalog, SummarizationQueue queue,
			SummarizationRunMetrics metrics,
			@Value("${news.summarization.sweep-window}") String defaultWindow,
			@Value("${news.summarization.max-per-run}") int maxPerRun) {
		this(catalog, queue, metrics,
				DurationStyle.detectAndParse(defaultWindow), maxPerRun);
	}

	SweepHandler(ArticleCatalog catalog, SummarizationQueue queue,
			SummarizationRunMetrics metrics, Duration defaultWindow, int maxPerRun) {
		this.catalog = catalog;
		this.queue = queue;
		this.metrics = metrics;
		this.defaultWindow = defaultWindow;
		this.maxPerRun = maxPerRun;
	}

	@Override
	public boolean supports(Map<String, Object> payload) {
		return JOB.equals(payload.get("job"));
	}

	@Override
	public Object handle(Map<String, Object> payload) {
		// BẮT BUỘC. Lambda dùng lại execution environment, và hạn mức ở
		// `SummarizationQueue` đọc chính số đếm này — quên reset thì lượt thứ hai
		// trở đi enqueue được ít dần rồi về 0, im lặng.
		metrics.reset();

		Duration window = window(payload);
		List<SummarizableArticle> pending = catalog.findSummarizable(window, maxPerRun);
		int enqueued = queue.enqueueAll(
				pending.stream().map(SummarizableArticle::articleId).toList());

		log.info("sweep xong: window={} scanned={} enqueued={} skipped={}",
				window, pending.size(), enqueued, pending.size() - enqueued);
		return Map.of("scanned", pending.size(),
				"enqueued", enqueued,
				"skipped", pending.size() - enqueued);
	}

	// `sinceHours` mở rộng cửa sổ cho lần chạy này. Đây là cách chữa DUY NHẤT
	// khi flag tắt lâu hơn cửa sổ mặc định — và nó phải chạy được bằng một lần
	// `aws lambda invoke`, không phải một lần deploy.
	//
	// Khớp `Number` chứ không `Integer`: payload đi qua Jackson, và Jackson dựng
	// `Integer` hay `Long` tuỳ độ lớn. Bắt hụt kiểu ở đây thì cửa sổ lặng lẽ về
	// mặc định — đúng lúc người ta cần nó rộng nhất.
	private Duration window(Map<String, Object> payload) {
		Object since = payload.get("sinceHours");
		if (since instanceof Number hours) {
			return Duration.ofHours(hours.longValue());
		}
		return defaultWindow;
	}
}
