package dev.linhvu.news_aggregator.ingestion;

import dev.linhvu.news_aggregator.sources.api.SourceView;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * T1 — KHÔNG chạm mạng. `MockRestServiceServer` cho phép khẳng định header ĐI
 * RA, thứ mà một test đánh vào máy chủ thật không kiểm được.
 *
 * Ba field dựng lại cho TỪNG test (JUnit 5 mặc định một instance mỗi method),
 * và thứ tự khởi tạo có ý nghĩa: `bindTo` gắn request factory giả vào builder,
 * nên `builder.build()` phải chạy SAU nó.
 */
class FeedFetcherTest {

	private static final String FEED = "https://a.test/feed";

	private static final String XML = "<rss version=\"2.0\"><channel/></rss>";

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private final FeedFetcher fetcher = new FeedFetcher(builder.build(), 5_242_880);

	@Test
	void gui_ca_hai_header_khi_da_co_trang_thai() {
		server.expect(requestTo(FEED))
				.andExpect(header("If-None-Match", "\"abc\""))
				.andExpect(header("If-Modified-Since", "Mon, 04 Aug 2026 00:00:00 GMT"))
				.andRespond(withSuccess(XML, MediaType.APPLICATION_XML));

		fetcher.fetch(new SourceView("a", "A", FEED, "\"abc\"",
				"Mon, 04 Aug 2026 00:00:00 GMT"));

		server.verify();
	}

	/**
	 * Nguồn CHỈ có `ETag` — đây là Spring Blog, không phải trường hợp giả định
	 * (đo 2026-08-06, xem `sources.yaml`). Gửi kèm `If-Modified-Since: null` sẽ
	 * làm hỏng đúng nguồn này.
	 */
	@Test
	void chi_gui_if_none_match_khi_chi_co_etag() {
		server.expect(requestTo(FEED))
				.andExpect(header("If-None-Match", "\"abc\""))
				.andExpect(headerDoesNotExist("If-Modified-Since"))
				.andRespond(withSuccess(XML, MediaType.APPLICATION_XML));

		fetcher.fetch(new SourceView("a", "A", FEED, "\"abc\"", null));

		server.verify();
	}

	/**
	 * Nguồn CHỈ có `Last-Modified` — đây là AWS News Blog, đối xứng ngược với
	 * test trên. Hai nguồn này chia đôi tổ hợp header, nên bỏ một trong hai test
	 * là bỏ hẳn một nửa số nguồn thật.
	 */
	@Test
	void chi_gui_if_modified_since_khi_chi_co_last_modified() {
		server.expect(requestTo(FEED))
				.andExpect(headerDoesNotExist("If-None-Match"))
				.andExpect(header("If-Modified-Since", "Mon, 04 Aug 2026 00:00:00 GMT"))
				.andRespond(withSuccess(XML, MediaType.APPLICATION_XML));

		fetcher.fetch(new SourceView("a", "A", FEED, null,
				"Mon, 04 Aug 2026 00:00:00 GMT"));

		server.verify();
	}

	/**
	 * Lượt ĐẦU TIÊN của một nguồn chưa có etag. Gửi `If-None-Match: null` sẽ
	 * khiến một số máy chủ trả 400, và triệu chứng là "nguồn mới không bao giờ
	 * chạy được lần đầu".
	 */
	@Test
	void khong_gui_header_khi_chua_co_trang_thai() {
		server.expect(requestTo(FEED))
				.andExpect(headerDoesNotExist("If-None-Match"))
				.andExpect(headerDoesNotExist("If-Modified-Since"))
				.andRespond(withSuccess(XML, MediaType.APPLICATION_XML));

		fetcher.fetch(new SourceView("a", "A", FEED, null, null));

		server.verify();
	}

	/**
	 * `RestClient` chỉ ném lỗi ở 4xx/5xx — 304 đi qua "thành công" với body
	 * rỗng. Đọc status TƯỜNG MINH là bắt buộc; nếu không, lượt nào cũng parse
	 * một body rỗng và ném lỗi cả nguồn.
	 *
	 * Khẳng định luôn HAI GIÁ TRỊ mang theo, không chỉ kiểu: máy chủ KHÔNG gửi
	 * lại validator ở 304, nên `NotModified` phải chở giá trị cũ đi tiếp. Trả
	 * `new NotModified(null, null)` vẫn qua được một phép kiểm `isInstanceOf`,
	 * rồi `updateFetchState` sẽ XOÁ validator đang lưu, và lượt sau thành
	 * unconditional — tức Task 16 tự vô hiệu hoá mà không có lỗi nào.
	 */
	@Test
	void tra_ve_not_modified_khi_304_va_giu_nguyen_validator() {
		server.expect(requestTo(FEED)).andRespond(withStatus(HttpStatus.NOT_MODIFIED));

		FetchOutcome outcome = fetcher.fetch(new SourceView("a", "A", FEED,
				"\"abc\"", "Mon, 04 Aug 2026 00:00:00 GMT"));

		assertThat(outcome).isEqualTo(new FetchOutcome.NotModified(
				"\"abc\"", "Mon, 04 Aug 2026 00:00:00 GMT"));
	}

	/**
	 * Khép kín vòng lặp: validator ở lượt 200 phải lấy từ RESPONSE (giá trị mới
	 * của máy chủ), không phải chép lại giá trị đang lưu. Chép lại thì mọi lượt
	 * sau gửi đi một validator vĩnh viễn lỗi thời, máy chủ không bao giờ trả 304
	 * nữa, và cái mất là băng thông chứ không phải tính đúng — nên không có gì
	 * đỏ để mà nhìn.
	 */
	@Test
	void lay_validator_moi_tu_response_khi_200() {
		server.expect(requestTo(FEED))
				.andRespond(withSuccess(XML, MediaType.APPLICATION_XML)
						.header("ETag", "\"moi\"")
						.header("Last-Modified", "Tue, 05 Aug 2026 00:00:00 GMT"));

		FetchOutcome outcome = fetcher.fetch(new SourceView("a", "A", FEED,
				"\"cu\"", "Mon, 04 Aug 2026 00:00:00 GMT"));

		assertThat(outcome).isInstanceOfSatisfying(FetchOutcome.Body.class, body -> {
			assertThat(body.etag()).isEqualTo("\"moi\"");
			assertThat(body.lastModified()).isEqualTo("Tue, 05 Aug 2026 00:00:00 GMT");
			assertThat(body.content()).asString().isEqualTo(XML);
		});
	}
}
