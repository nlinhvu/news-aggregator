package dev.linhvu.news_aggregator.sources;

import java.util.Comparator;
import java.util.List;

import dev.linhvu.news_aggregator.sources.api.SourceOptionDto;
import dev.linhvu.news_aggregator.sources.api.SourceView;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * EXPOSED SERVICE — cách hợp lệ để `ingestion` nói chuyện với module này
 * (master §4 nguyên tắc 4, sửa 2026-08-05). `ingestion` KHÔNG bao giờ chạm
 * bảng `sources`, và không bao giờ thấy `Source` hay `SourceRepository`.
 */
@Service
@Lazy
public class SourceCatalog {

	private final SourceRepository repository;

	SourceCatalog(SourceRepository repository) {
		this.repository = repository;
	}

	public List<SourceView> enabledSources() {
		return repository.findAll().stream()
				.filter(Source::isEnabled)
				.map(s -> new SourceView(s.getSourceId(), s.getName(), s.getFeedUrl(),
						s.getEtag(), s.getLastModified()))
				.toList();
	}

	/**
	 * AP14 — danh sách cho hàng chip của slice 4, và cho việc `personalization`
	 * kiểm một `sourceId` gửi lên có thật hay không.
	 *
	 * Dựng TRÊN `enabledSources()` chứ không lặp lại phép lọc `enabled`: hai
	 * bản lọc rời nhau sẽ trôi khỏi nhau, và chiều trôi tệ nhất là một nguồn đã
	 * tắt vẫn hiện ra như một lựa chọn hợp lệ.
	 *
	 * Sắp theo `name` vì Scan trả item theo thứ tự nội bộ của DynamoDB: không
	 * sắp thì hàng chip đổi chỗ giữa các lần tải trang, và người dùng bấm nhầm
	 * nguồn — một khiếm khuyết nhìn thấy được nhưng khó truy nguyên.
	 */
	public List<SourceOptionDto> options() {
		return enabledSources().stream()
				.map(s -> new SourceOptionDto(s.sourceId(), s.name()))
				.sorted(Comparator.comparing(SourceOptionDto::name))
				.toList();
	}

	public void recordFetch(String sourceId, String etag, String lastModified,
			String fetchedAt) {
		repository.updateFetchState(sourceId, etag, lastModified, fetchedAt);
	}
}
