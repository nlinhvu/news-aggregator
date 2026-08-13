package dev.linhvu.news_aggregator.platform;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
// `web` không có `AWS_LWA_PASS_THROUGH_PATH`, nên endpoint này ở đó là một
// đường vào không ai dùng.
@Profile(RoleProfiles.EVENT_DRIVEN)
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

	/**
	 * 500 chứ KHÔNG 400, và đó là quyết định của ADR-0015 chứ không phải sơ suất.
	 *
	 * `/events` là endpoint NỘI BỘ: người gọi duy nhất là EventBridge Scheduler và
	 * SQS ESM, cả hai gửi payload do chính `AppStack` sinh ra. Một `job` không
	 * routed được ở đây nghĩa là IaC và code đã lệch nhau — bug của deploy.
	 *
	 * `AWS_LWA_ERROR_STATUS_CODES=500-599` biến response này thành một lượt invoke
	 * THẤT BẠI thật, kéo theo async retry, `onFailure` destination và metric
	 * `Errors`. Dải đó cố ý không lấy 4xx (bot quét sinh 404 sẽ đầu độc alarm),
	 * nên nếu đổi dòng này về `BAD_REQUEST` thì toàn bộ lưới an toàn của Phase 4
	 * câm với chính chế độ hỏng nó sinh ra để bắt — và không test nào ở tầng infra
	 * đỏ. `payload_khong_ai_nhan_tra_500` là chốt chặn duy nhất.
	 */
	@ExceptionHandler(UnknownEventException.class)
	ProblemDetail handleUnknown(UnknownEventException e) {
		log.error("payload không handler nào nhận: {}", e.getMessage());
		return ProblemDetail.forStatusAndDetail(
				HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
	}

	static class UnknownEventException extends RuntimeException {
		UnknownEventException(String keys) {
			super("không handler nào nhận payload có key: " + keys);
		}
	}
}
