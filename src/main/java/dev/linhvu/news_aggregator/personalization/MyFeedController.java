package dev.linhvu.news_aggregator.personalization;

import java.util.List;

import dev.linhvu.news_aggregator.catalog.api.ArticleCatalog;
import dev.linhvu.news_aggregator.catalog.api.ArticleSummaryDto;
import dev.linhvu.news_aggregator.catalog.api.CatalogUnavailableException;
import dev.linhvu.news_aggregator.identity.api.CurrentUser;
import dev.linhvu.news_aggregator.platform.RoleProfiles;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feed đã lọc. Module này KHÔNG query DynamoDB của `catalog` — nó hỏi
 * {@link ArticleCatalog}, và đó là cạnh duy nhất giữa hai module ([ADR-0019]).
 *
 * Hình dạng response giống hệt `/api/articles` (TDD §7) nên frontend chỉ đổi
 * URL; phép ánh xạ nằm chung một chỗ bên `catalog` (`ArticleSummaries`), kể cả
 * việc `summary` vắng mặt khi `AI_SUMMARIZATION` tắt.
 */
@RestController
@RequestMapping("/api/my/feed")
@Profile(RoleProfiles.WEB)
class MyFeedController {

	private final SourcePreferenceRepository repository;
	private final ArticleCatalog catalog;
	private final CurrentUser currentUser;
	private final int defaultLimit;
	private final int maxLimit;

	/**
	 * Dùng CHUNG `news.catalog.*` với `/api/articles`, không khai cặp giới hạn
	 * riêng: hai feed cùng hình dạng mà khác trần là thứ chỉ lộ ra khi ai đó so
	 * hai response và thấy số bài lệch nhau.
	 */
	MyFeedController(SourcePreferenceRepository repository, ArticleCatalog catalog,
			CurrentUser currentUser,
			@Value("${news.catalog.default-limit}") int defaultLimit,
			@Value("${news.catalog.max-limit}") int maxLimit) {
		this.repository = repository;
		this.catalog = catalog;
		this.currentUser = currentUser;
		this.defaultLimit = defaultLimit;
		this.maxLimit = maxLimit;
	}

	@GetMapping
	ResponseEntity<List<ArticleSummaryDto>> myFeed(
			@RequestParam(required = false) Integer limit) {
		var sub = currentUser.sub();
		if (sub.isEmpty()) {
			return ResponseEntity.status(401).build();
		}

		int effective = Math.clamp(limit == null ? defaultLimit : limit, 1, maxLimit);
		// Chưa chọn gì ⇒ danh sách RỖNG ⇒ `recentBySources` trả TẤT CẢ nguồn.
		// Đây là trạng thái bình thường nhất của một người vừa đăng nhập, nên
		// hiểu nhầm nó thành "không nguồn nào" biến trang chủ của họ thành trang
		// trắng.
		List<String> selected = repository.findByUserId(sub.get())
				.map(SourcePreferences::getSourceIds)
				.orElse(List.of());

		return ResponseEntity.ok(catalog.recentBySources(selected, effective));
	}

	/**
	 * `503`, không phải `500`: fan-out hỏng là lỗi TẠM THỜI của một phụ thuộc,
	 * và SPA phân biệt được để mời thử lại thay vì báo hỏng.
	 *
	 * Vế quan trọng hơn nằm ở `catalog`: nó NÉM thay vì trả kết quả một phần.
	 * Feed thiếu bài mà không báo gì khiến người đọc tưởng nguồn đó không có bài
	 * mới — kiểu hỏng tệ nhất vì không ai phát hiện ra.
	 */
	@ExceptionHandler(CatalogUnavailableException.class)
	ResponseEntity<Void> catalogUnavailable() {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
	}
}
