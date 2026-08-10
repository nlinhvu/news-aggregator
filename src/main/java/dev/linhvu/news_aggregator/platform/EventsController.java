package dev.linhvu.news_aggregator.platform;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EventsController {

	private static final Logger log = LoggerFactory.getLogger(EventsController.class);

	private final List<EventJobHandler> handlers;

	EventsController(List<EventJobHandler> handlers) {
		this.handlers = handlers;
	}

	// Path lấy từ property chứ không hằng số chôn trong class: nó phải khớp
	// `AWS_LWA_PASS_THROUGH_PATH` do CDK đặt, và hai repo không thấy nhau nên
	// compiler không bắt được lệch. Một chỗ duy nhất để grep.
	@PostMapping("${news.platform.pass-through-path}")
	Object dispatch(@RequestBody Map<String, Object> payload) {
		return handlers.stream()
				.filter(h -> h.supports(payload))
				.findFirst()
				.orElseThrow(() -> new UnknownEventException(describe(payload)))
				.handle(payload);
	}

	// KHÔNG log cả payload: message SQS mang articleId, nhưng payload của một
	// nguồn tương lai có thể mang gì đó nhạy cảm. Log đủ để chẩn đoán, không hơn.
	private static String describe(Map<String, Object> payload) {
		return payload.isEmpty() ? "(rỗng)" : String.join(",", payload.keySet());
	}

	@ExceptionHandler(UnknownEventException.class)
	ProblemDetail handleUnknown(UnknownEventException e) {
		log.warn("payload không handler nào nhận: {}", e.getMessage());
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	static class UnknownEventException extends RuntimeException {
		UnknownEventException(String keys) {
			super("không handler nào nhận payload có key: " + keys);
		}
	}
}
