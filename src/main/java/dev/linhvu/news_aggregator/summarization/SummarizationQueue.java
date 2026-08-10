package dev.linhvu.news_aggregator.summarization;

import java.util.List;

import dev.linhvu.news_aggregator.platform.NewsFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * CỔNG DUY NHẤT vào queue. Cả hai producer — `ArticleAddedListener` (đường
 * tươi) và `SweepHandler` (lưới an toàn) — đều phải đi qua đây, vì đây là nơi
 * DUY NHẤT đặt gate Togglz và hạn mức mỗi lượt (ADR-0014).
 *
 * Producer nào gọi thẳng `SqsAsyncClient` là producer đó bỏ qua cả hai, và triệu
 * chứng là hoá đơn chứ không phải lỗi.
 */
@Component
@Lazy
class SummarizationQueue {

	private static final Logger log = LoggerFactory.getLogger(SummarizationQueue.class);

	// spring-cloud-aws 4.1.0 KHÔNG có bean `SqsClient` sync (Task 3 Step 6).
	private final SqsAsyncClient sqs;

	private final SummarizationRunMetrics metrics;

	private final String queueUrl;

	private final int maxPerRun;

	SummarizationQueue(SqsAsyncClient sqs, SummarizationRunMetrics metrics,
			@Value("${news.summarization.queue-url}") String queueUrl,
			@Value("${news.summarization.max-per-run}") int maxPerRun) {
		this.sqs = sqs;
		this.metrics = metrics;
		this.queueUrl = queueUrl;
		this.maxPerRun = maxPerRun;
	}

	boolean enqueue(String articleId) {
		// Fail-closed: đọc flag lỗi thì coi như OFF. Không tiêu tiền vì một lần
		// đọc DynamoDB hỏng — cùng lập luận `ArticleController` dùng từ Phase 1.
		boolean bat;
		try {
			bat = NewsFeature.AI_SUMMARIZATION.isActive();
		}
		catch (RuntimeException e) {
			log.warn("không đọc được AI_SUMMARIZATION, coi như OFF: {}", e.toString());
			return false;
		}
		if (!bat) {
			return false;
		}
		if (metrics.enqueued() >= maxPerRun) {
			// Phần dư KHÔNG mất: article vẫn thiếu `summary` nên lượt sweep sau
			// nhặt lại. Log ở INFO chứ không WARN — đây là van hoạt động đúng.
			log.info("chạm hạn mức {} article mỗi lượt, phần dư để lượt sau", maxPerRun);
			return false;
		}

		// `.join()` BẮT BUỘC, không phải chuyện phong cách: bỏ nó thì lời gọi chỉ
		// là một CompletableFuture chưa hoàn thành, và Lambda ĐÓNG BĂNG execution
		// environment ngay khi handler trả về. Message có thể không bao giờ rời
		// máy, `metrics.countEnqueued()` vẫn đếm, và log báo đã gửi.
		sqs.sendMessage(SendMessageRequest.builder()
				.queueUrl(queueUrl)
				// CHỈ articleId. Nhét excerpt vào đây làm message tới 2 KB và
				// nó sẽ CŨ nếu article được sửa giữa chừng — id là thứ duy nhất
				// không bao giờ cũ (TDD §17 #12).
				.messageBody("{\"articleId\":\"%s\"}".formatted(articleId))
				.build()).join();
		metrics.countEnqueued();
		return true;
	}

	int enqueueAll(List<String> articleIds) {
		int sent = 0;
		for (String id : articleIds) {
			if (enqueue(id)) {
				sent++;
			}
		}
		return sent;
	}
}
