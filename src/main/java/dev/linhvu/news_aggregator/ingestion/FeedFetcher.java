package dev.linhvu.news_aggregator.ingestion;

import dev.linhvu.news_aggregator.sources.api.SourceView;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Lazy
class FeedFetcher {

	private final RestClient restClient;
	private final int maxBodyBytes;

	FeedFetcher(RestClient feedRestClient,
			@Value("${news.ingestion.max-body-bytes}") int maxBodyBytes) {
		this.restClient = feedRestClient;
		this.maxBodyBytes = maxBodyBytes;
	}

	/**
	 * Slice 2: luôn tải full. Conditional GET thêm ở Task 16.
	 *
	 * `RestClient` mặc định chỉ ném lỗi ở 4xx/5xx, nên 3xx đi qua bình thường —
	 * phải đọc status TƯỜNG MINH chứ không giả định "không ném lỗi nghĩa là có
	 * body".
	 *
	 * ⚠️ `maxBodyBytes` kiểm SAU khi body đã nằm hết trong RAM, nên nó chặn
	 * được feed to bất thường đi tiếp vào parser, KHÔNG chặn được việc tải nó
	 * về. Chấp nhận ở slice 2 vì Lambda có 2048 MB và trần là 5 MB; đừng đọc
	 * dòng này như một giới hạn bộ nhớ.
	 */
	FetchOutcome fetch(SourceView source) {
		ResponseEntity<byte[]> response = restClient.get()
				.uri(source.feedUrl())
				.retrieve()
				.toEntity(byte[].class);

		if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
			return new FetchOutcome.NotModified(source.etag(), source.lastModified());
		}

		byte[] body = response.getBody();
		if (body == null || body.length == 0) {
			throw new IllegalStateException("feed trả body rỗng: " + source.feedUrl());
		}
		if (body.length > maxBodyBytes) {
			throw new IllegalStateException("feed vượt trần %d byte: %s"
					.formatted(maxBodyBytes, source.feedUrl()));
		}

		HttpHeaders headers = response.getHeaders();
		return new FetchOutcome.Body(body,
				headers.getFirst(HttpHeaders.ETAG),
				headers.getFirst(HttpHeaders.LAST_MODIFIED));
	}
}
