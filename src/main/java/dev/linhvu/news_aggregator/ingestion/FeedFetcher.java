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
		RestClient.RequestHeadersSpec<?> request = restClient.get().uri(source.feedUrl());

		// Gửi CẢ HAI header, không chọn một. Đo 2026-08-06 (TDD §13): Spring chỉ
		// có ETag, AWS chỉ có Last-Modified, InfoQ và Inside Java có cả hai —
		// không header nào phủ được cả bốn nguồn.
		//
		// Chỉ gửi khi ĐÃ CÓ giá trị: `If-None-Match: null` khiến một số máy chủ
		// trả 400, và triệu chứng là "nguồn mới không chạy được lần đầu".
		if (source.etag() != null) {
			request = request.header(HttpHeaders.IF_NONE_MATCH, source.etag());
		}
		if (source.lastModified() != null) {
			request = request.header(HttpHeaders.IF_MODIFIED_SINCE, source.lastModified());
		}

		ResponseEntity<byte[]> response = request.retrieve().toEntity(byte[].class);

		if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
			// Giữ nguyên giá trị cũ: máy chủ không gửi lại validator ở 304. Trả
			// null ở đây sẽ khiến `updateFetchState` XOÁ validator đang lưu và
			// lượt sau thành unconditional — conditional GET tự vô hiệu hoá mà
			// không có lỗi nào để nhìn.
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
