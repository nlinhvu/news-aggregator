package dev.linhvu.news_aggregator.summarization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

final class SqsBatch {

	private static final Logger log = LoggerFactory.getLogger(SqsBatch.class);

	// JsonMapper của Jackson 3 — cùng hệ Boot 4 đã dùng. KHÔNG dùng
	// com.fasterxml.jackson (Jackson 2): nó không có trên classpath.
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private SqsBatch() {
	}

	/** `traceparent` có thể `null`: message cũ và message gửi tay không có nó. */
	record Message(String messageId, String articleId, String traceparent) {
	}

	@SuppressWarnings("unchecked")
	static List<Message> parse(Map<String, Object> payload) {
		Object records = payload.get("Records");
		if (!(records instanceof List<?> list)) {
			return List.of();
		}
		List<Message> parsed = new ArrayList<>();
		for (Object raw : list) {
			if (!(raw instanceof Map<?, ?> record)) {
				continue;
			}
			String messageId = String.valueOf(record.get("messageId"));
			Object body = record.get("body");
			try {
				Map<String, Object> fields = JSON.readValue(String.valueOf(body), Map.class);
				Object articleId = fields.get("articleId");
				if (articleId == null) {
					// Bỏ chứ không đưa vào batchItemFailures: message này sẽ không
					// bao giờ đọc được, nên retry chỉ đốt tiền. Sweep là lưới an toàn.
					log.warn("message {} không có articleId — bỏ", messageId);
					continue;
				}
				// W3C Trace Context, đi bằng MESSAGE ATTRIBUTE chứ không trong body
				// (Phase 3 §17 #12 giữ body ở đúng `{articleId}`). Vắng mặt là hợp
				// lệ và phải chịu được — message cũ, và message gửi tay lúc kiểm
				// thử, đều không có nó.
				String traceparent = null;
				if (record.get("messageAttributes") instanceof Map<?, ?> attrs
						&& attrs.get("traceparent") instanceof Map<?, ?> tp) {
					Object value = tp.get("stringValue");
					traceparent = value == null ? null : String.valueOf(value);
				}
				parsed.add(new Message(messageId, String.valueOf(articleId), traceparent));
			}
			catch (RuntimeException e) {
				log.warn("message {} không parse được body — bỏ", messageId);
			}
		}
		return List.copyOf(parsed);
	}

	static Map<String, Object> failures(List<String> messageIds) {
		return Map.of("batchItemFailures", messageIds.stream()
				.map(id -> Map.<String, Object>of("itemIdentifier", id))
				.toList());
	}
}
