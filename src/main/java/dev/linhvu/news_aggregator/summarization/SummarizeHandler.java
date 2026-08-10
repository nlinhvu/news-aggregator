package dev.linhvu.news_aggregator.summarization;

import java.util.List;
import java.util.Map;

import dev.linhvu.news_aggregator.platform.EventJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
class SummarizeHandler implements EventJobHandler {

	private static final Logger log = LoggerFactory.getLogger(SummarizeHandler.class);

	@Override
	public boolean supports(Map<String, Object> payload) {
		// `Records` MỘT MÌNH không đủ: S3, DynamoDB Streams và Kinesis cùng dùng
		// key đó. Kiểm `eventSource` của phần tử đầu là thứ phân biệt thật.
		if (!(payload.get("Records") instanceof List<?> records) || records.isEmpty()) {
			return false;
		}
		return records.get(0) instanceof Map<?, ?> first
				&& "aws:sqs".equals(first.get("eventSource"));
	}

	@Override
	public Object handle(Map<String, Object> payload) {
		// SLICE 1: chỉ đếm và ACK. Task 10 thay bằng đường gọi model thật.
		List<SqsBatch.Message> messages = SqsBatch.parse(payload);
		log.info("summarize batch: nhận {} message (slice 1 — chưa gọi model)",
				messages.size());
		return SqsBatch.failures(List.of());
	}
}
