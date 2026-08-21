package dev.linhvu.news_aggregator.catalog;

import dev.linhvu.news_aggregator.catalog.api.ArticlePage;
import dev.linhvu.news_aggregator.catalog.api.InvalidCursorException;
import dev.linhvu.news_aggregator.platform.RoleProfiles;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/articles")
@Profile(RoleProfiles.WEB)
class ArticleController {

	private final ArticleRepository repository;
	private final int defaultLimit;
	private final int maxLimit;

	ArticleController(ArticleRepository repository,
			@Value("${news.catalog.default-limit}") int defaultLimit,
			@Value("${news.catalog.max-limit}") int maxLimit) {
		this.repository = repository;
		this.defaultLimit = defaultLimit;
		this.maxLimit = maxLimit;
	}

	@GetMapping
	ArticlePage recent(@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String cursor) {
		int effective = Math.clamp(
				limit == null ? defaultLimit : limit, 1, maxLimit);

		// Đọc THỪA MỘT để biết có trang sau không — `ArticleSummaries.toPage`
		// cắt lại còn `effective`. Quyết định "có kèm summary không" và quyết
		// định "có cursor không" nằm chung một chỗ ở đó, vì `/api/my/feed` phải
		// trả CÙNG hình dạng và hai bản sao sẽ trôi khỏi nhau.
		return ArticleSummaries.toPage(
				repository.findRecent(effective + 1,
						ArticleCursor.decodeOrNull(cursor)),
				effective);
	}

	/**
	 * `400`, không `500`: cursor hỏng là lỗi của người gọi. Handler nằm ở đây và
	 * một bản y hệt nằm ở `MyFeedController` — hai dòng lặp lại, có chủ ý. Gom
	 * vào một `@RestControllerAdvice` ở `platform` sẽ tạo cạnh
	 * `platform → catalog`, tức chiều phụ thuộc ngược, thứ mà ADR-0012 đã phải
	 * xử lý bằng ignore-predicate một lần rồi.
	 */
	@ExceptionHandler(InvalidCursorException.class)
	ResponseEntity<Void> invalidCursor() {
		return ResponseEntity.badRequest().build();
	}
}
