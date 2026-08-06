package dev.linhvu.news_aggregator.sources;

import java.util.List;

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

	public void recordFetch(String sourceId, String etag, String lastModified,
			String fetchedAt) {
		repository.updateFetchState(sourceId, etag, lastModified, fetchedAt);
	}
}
