package dev.linhvu.news_aggregator.platform;

import java.util.Map;

// PORT của ADR-0013. `platform` khai interface này; module nghiệp vụ mang
// implementation tới. Cạnh dependency vì thế chạy TỪ nghiệp vụ VỀ platform —
// chiều đã có từ Phase 1 — nên `platform.package-info` giữ nguyên
// allowedDependencies = {}. Nếu platform phải import module nghiệp vụ để
// dispatch thì nó thành hub, và đó đúng là thứ master §5 đã từ chối khi loại
// module `api`.
public interface EventJobHandler {

	// Phải kiểm ĐỦ điều kiện của mình, không chỉ điều kiện phân biệt với handler
	// hiện có. Hai supports() cùng trả true thì cái đầu tiên thắng IM LẶNG —
	// xem test `every_payload_is_taken_by_exactly_one_handler`.
	boolean supports(Map<String, Object> payload);

	Object handle(Map<String, Object> payload);
}
