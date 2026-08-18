package dev.linhvu.news_aggregator.personalization;

import java.util.List;
import java.util.Set;

import dev.linhvu.news_aggregator.identity.api.CurrentUser;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import dev.linhvu.news_aggregator.sources.SourceCatalog;
import dev.linhvu.news_aggregator.sources.api.SourceOptionDto;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * `userId` LUÔN đến từ phiên phía server qua {@link CurrentUser}, không bao giờ
 * từ query param hay body — chốt chặn IDOR của cả slice. Một endpoint nhận
 * `userId` từ người gọi là một endpoint cho phép người dùng A đọc và ghi lựa
 * chọn của người dùng B.
 */
@RestController
@RequestMapping("/api/preferences/sources")
@Profile(RoleProfiles.WEB)
class PreferencesController {

	private final SourcePreferenceRepository repository;
	private final SourceCatalog sources;
	private final CurrentUser currentUser;

	PreferencesController(SourcePreferenceRepository repository, SourceCatalog sources,
			CurrentUser currentUser) {
		this.repository = repository;
		this.sources = sources;
		this.currentUser = currentUser;
	}

	@GetMapping
	ResponseEntity<SourceSelectionDto> current() {
		return currentUser.sub()
				.map(sub -> ResponseEntity.ok(new SourceSelectionDto(
						repository.findByUserId(sub)
								.map(SourcePreferences::getSourceIds)
								.orElse(List.of()))))
				.orElseGet(() -> ResponseEntity.status(401).build());
	}

	/**
	 * `400` khi có `sourceId` không tồn tại. Bỏ vế này thì một id rác nằm im
	 * trong bảng và sinh một query fan-out vô ích MÃI MÃI — mỗi lần người đó mở
	 * feed, một query trả về rỗng, không lỗi, không log.
	 *
	 * Đối chiếu với danh sách nguồn ĐANG BẬT, cùng danh sách mà `GET /api/sources`
	 * trả về: người dùng chỉ bấm được cái họ nhìn thấy, nên mọi thứ ngoài đó là
	 * request dựng tay.
	 *
	 * `204` chứ không `200`: không có gì để trả về, và SPA đã có sẵn trạng thái
	 * nó vừa gửi (cập nhật optimistic — Task 24).
	 */
	@PutMapping
	ResponseEntity<Void> replace(@RequestBody SourceSelectionDto body) {
		var sub = currentUser.sub();
		if (sub.isEmpty()) {
			return ResponseEntity.status(401).build();
		}

		List<String> chon = body == null || body.sourceIds() == null
				? List.of() : body.sourceIds();
		Set<String> hopLe = sources.options().stream()
				.map(SourceOptionDto::sourceId)
				.collect(java.util.stream.Collectors.toSet());
		if (!hopLe.containsAll(chon)) {
			return ResponseEntity.badRequest().build();
		}

		repository.save(sub.get(), chon);
		return ResponseEntity.noContent().build();
	}

	/** Rỗng = TẤT CẢ nguồn. Xem `SourcePreferences.getSourceIds`. */
	record SourceSelectionDto(List<String> sourceIds) {
	}
}
