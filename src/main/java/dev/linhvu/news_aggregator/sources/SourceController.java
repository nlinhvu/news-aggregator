package dev.linhvu.news_aggregator.sources;

import java.util.List;

import dev.linhvu.news_aggregator.sources.api.SourceOptionDto;
import dev.linhvu.news_aggregator.platform.RoleProfiles;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nằm TRONG module `sources` vì module này sở hữu dữ liệu (master §5) — không
 * phải trong `personalization`, thứ chỉ tiêu thụ danh sách.
 *
 * CÔNG KHAI, và đó là quyết định chứ không phải sơ suất: hàng chip phải render
 * được (dạng mờ) cho cả người ẩn danh — nó chính là lời mời đăng nhập, nên nó
 * phải hiện ra TRƯỚC khi biết người dùng là ai. `SecurityConfig` đã cho
 * `/api/sources` vào danh sách `permitAll` từ Task 10.
 *
 * Đây là endpoint đầu tiên đọc bảng `sources` từ đường phục vụ Internet, nên
 * `web` mới cần `dynamodb:Scan` trên bảng đó (Task 19) — quyền được cấp có ý
 * thức, xem `SecurityBoundaryTest#web_scan_duoc_sources_va_chi_sources`.
 */
@RestController
@RequestMapping("/api/sources")
@Profile(RoleProfiles.WEB)
class SourceController {

	private final SourceCatalog catalog;

	SourceController(SourceCatalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<SourceOptionDto> options() {
		return catalog.options();
	}
}
