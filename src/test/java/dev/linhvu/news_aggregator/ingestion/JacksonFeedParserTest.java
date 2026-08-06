package dev.linhvu.news_aggregator.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.linhvu.news_aggregator.platform.XmlConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1 — KHÔNG chạm mạng. Fixture lấy từ feed thật một lần rồi lưu lại; một test
 * phụ thuộc máy chủ của người khác là một test sẽ đỏ vào ngày người ta sửa
 * blog, và khi đó không ai phân biệt được "code hỏng" với "blog đổi".
 *
 * Dùng `new XmlConfig().feedXmlMapper()` — ĐÚNG mapper mà production dùng —
 * chứ không dựng lại một bản sao trong test. Bản sao sẽ trôi khỏi bản thật mà
 * không có gì báo, và khi đó test xanh không còn nói gì về hệ thống chạy thật.
 */
class JacksonFeedParserTest {

	private final FeedParser parser = new JacksonFeedParser(new XmlConfig().feedXmlMapper());

	/**
	 * So GIÁ TRỊ CHÍNH XÁC, không chỉ `isNotBlank()`. Ánh xạ XML→bean là chỗ
	 * `title` và `link` rất dễ đổi chỗ cho nhau, mà một khẳng định "không rỗng"
	 * thì hai trường đổi chỗ vẫn xanh nguyên.
	 *
	 * Cũng ghim luôn hai thứ khác: CDATA phải được bóc (`<![CDATA[…]]>` trong
	 * fixture), và `pubDate` phải về NGUYÊN VĂN — quy đổi ngày là việc của
	 * `FeedDates`, không phải của parser.
	 */
	@Test
	void parse_dung_gia_tri_cua_tung_item() throws IOException {
		List<ParsedItem> items = parser.parse(fixture("feeds/spring-blog.xml"));

		assertThat(items).hasSize(3);
		assertThat(items.getFirst()).isEqualTo(new ParsedItem(
				"This Week in Spring - August 4th, 2026",
				"https://spring.io/blog/2026/08/04/this-week-in-spring-august-4-2026",
				"Tue, 04 Aug 2026 00:00:00 GMT"));
	}

	/**
	 * Thứ tự trong feed phải được giữ. RSS phát bài mới nhất trước, và Task 10
	 * dựa vào việc parser không xáo lại — nếu nó xáo, lỗi chỉ lộ ra thành "thứ
	 * tự bài trên trang trông hơi lạ", đúng loại triệu chứng không ai truy.
	 */
	@Test
	void giu_nguyen_thu_tu_cua_feed() throws IOException {
		assertThat(parser.parse(fixture("feeds/spring-blog.xml")))
				.extracting(ParsedItem::title)
				.containsExactly(
						"This Week in Spring - August 4th, 2026",
						"A Bootiful Podcast: Spring Boot founder and lead Phil Webb",
						"This Week in Spring - July 28th, 2026");
	}

	/** `dc:creator` và `content:encoded` trong fixture không được làm chết parse. */
	@Test
	void bo_qua_element_khong_khai_bao() throws IOException {
		assertThat(parser.parse(fixture("feeds/spring-blog.xml"))).hasSize(3);
	}

	/**
	 * Ngày hỏng là việc của tầng trên, KHÔNG phải của parser: nó trả nguyên văn
	 * và giữ đủ item. Bỏ item ngay tại đây sẽ giấu mất thông tin mà Task 10 cần
	 * để quyết định (và để đếm vào `failed`).
	 */
	@Test
	void ngay_hong_van_ra_du_item_va_giu_nguyen_van() throws IOException {
		List<ParsedItem> items = parser.parse(fixture("feeds/broken-date.xml"));

		assertThat(items).hasSize(3);
		assertThat(items.get(1).publishedAt()).isEqualTo("hôm qua");
		assertThat(FeedDates.parse(items.get(1).publishedAt())).isEmpty();
	}

	/**
	 * `<link>` là nguồn của identity (TDD §17 #13); `guid` chỉ là phương án dự
	 * phòng khi feed không phát `<link>`. Nhánh này không có fixture thật nào
	 * đi qua, nên nếu không viết ra ở đây thì nó hoàn toàn không được kiểm.
	 */
	@Test
	void dung_guid_khi_thieu_link() {
		byte[] xml = """
				<?xml version="1.0"?>
				<rss version="2.0"><channel>
				  <item><title>Không có link</title>
				    <guid isPermaLink="true">https://example.test/tu-guid</guid>
				    <pubDate>Tue, 04 Aug 2026 10:00:00 GMT</pubDate></item>
				</channel></rss>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(parser.parse(xml)).singleElement()
				.extracting(ParsedItem::link)
				.isEqualTo("https://example.test/tu-guid");
	}

	@Test
	void nem_loi_khi_root_element_khong_ho_tro() {
		byte[] html = "<html><body>403 Forbidden</body></html>"
				.getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> parser.parse(html))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("html");
	}

	/**
	 * Atom chưa được hỗ trợ (tới Task 14), và root element có TIỀN TỐ namespace
	 * phải được nhận ra là `feed` chứ không phải `atom:feed` — nếu không, ngày
	 * thêm Atom vào `switch` nó sẽ không bao giờ khớp.
	 */
	@Test
	void bo_tien_to_namespace_o_root_element() {
		byte[] atom = """
				<?xml version="1.0"?>
				<atom:feed xmlns:atom="http://www.w3.org/2005/Atom"><atom:title>x</atom:title></atom:feed>
				""".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> parser.parse(atom))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("feed")
				.hasMessageNotContaining("atom:");
	}

	private static byte[] fixture(String path) throws IOException {
		try (InputStream in = JacksonFeedParserTest.class.getClassLoader()
				.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("thiếu fixture: " + path);
			}
			return in.readAllBytes();
		}
	}
}
