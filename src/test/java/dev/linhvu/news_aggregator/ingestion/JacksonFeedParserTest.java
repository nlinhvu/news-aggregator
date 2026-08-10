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

	private final FeedParser parser =
			new JacksonFeedParser(new XmlConfig().feedXmlMapper(), 2000);

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
				"Tue, 04 Aug 2026 00:00:00 GMT",
				"Nội dung đã cắt bớt cho fixture."));
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

	/**
	 * `dc:creator` và `category` trong fixture không được làm chết parse.
	 * (`content:encoded` từng nằm trong danh sách này; từ Task 6 nó được KHAI
	 * BÁO và mang excerpt, nên nó không còn là element lạ nữa.)
	 */
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

	/**
	 * So GIÁ TRỊ CHÍNH XÁC vì lý do đã nêu ở test RSS: `isNotBlank()` xanh nguyên
	 * khi hai trường đổi chỗ. Với Atom còn thêm một chỗ lệch nữa mà "không rỗng"
	 * không bắt được — `published` và `updated` đều là ngày ISO hợp lệ, lấy nhầm
	 * cái nào cũng qua.
	 */
	@Test
	void parse_dung_gia_tri_cua_tung_entry_atom() throws IOException {
		List<ParsedItem> items = parser.parse(fixture("feeds/inside-java.xml"));

		assertThat(items).containsExactly(
				new ParsedItem("Episode 65 “Embracing Virtual Threads with Helidon” [I/O]",
						"https://inside.java/2026/08/06/podcast-065/",
						"2026-08-06T00:00:00Z",
						"How Helidon 4.4 embraces virtual threads, ahoead-of-time "
								+ "computation, and OpenJDK"),
				new ParsedItem("Oracle Java Platform Extension for Visual Studio Code "
						+ "- Version 26.0.1 Is Now Available",
						"https://inside.java/2026/08/05/java-vscode-extension-update/",
						"2026-08-05T00:00:00Z",
						"New release of the Java Platform Extension for VS Code"),
				new ParsedItem("Transitioning Java to More Frequent Security Updates",
						"https://inside.java/2026/07/31/"
								+ "transitioning-java-to-more-frequent-security-updates/",
						"2026-07-31T00:00:00Z",
						"Oracle intends to provide Java security updates more frequently "
								+ "than the current quarterly CPU cadence of January, "
								+ "April, July, and October."));
	}

	/**
	 * Atom để URL trong THUỘC TÍNH `href` của `<link>`, không phải trong nội
	 * dung element như RSS. Một parser chép từ nhánh RSS sang sẽ lấy được chuỗi
	 * rỗng — và chuỗi rỗng thì `CanonicalUrl.normalise` ném lỗi, nên triệu chứng
	 * là "mọi item của nguồn Atom bị bỏ", không phải một exception rõ ràng.
	 */
	@Test
	void atom_lay_link_tu_thuoc_tinh_href() throws IOException {
		assertThat(parser.parse(fixture("feeds/inside-java.xml")))
				.extracting(ParsedItem::link)
				.allSatisfy(l -> assertThat(l).startsWith("http"));
	}

	/**
	 * Atom cho phép NHIỀU `<link>` với `rel` khác nhau, và fixture Inside Java
	 * chỉ có `alternate` — tức nhánh chọn lọc hoàn toàn không được kiểm nếu
	 * không viết ra ở đây. Lấy nhầm sẽ dẫn người đọc tới trang bình luận thay vì
	 * bài viết: URL vẫn hợp lệ, vẫn `http`, nên không có gì đỏ cả.
	 */
	@Test
	void atom_chi_lay_link_alternate() {
		byte[] atom = """
				<?xml version="1.0"?>
				<feed xmlns="http://www.w3.org/2005/Atom">
				  <entry><title>Nhiều link</title>
				    <link rel="replies" href="https://example.test/binh-luan"/>
				    <link rel="alternate" href="https://example.test/bai-viet"/>
				    <updated>2026-08-04T00:00:00Z</updated></entry>
				</feed>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(parser.parse(atom)).singleElement()
				.extracting(ParsedItem::link)
				.isEqualTo("https://example.test/bai-viet");
	}

	/**
	 * `published` là ngày đăng, `updated` là ngày sửa cuối và LUÔN có mặt. Ba
	 * entry của fixture có hai giá trị trùng khít nhau, nên chỉ fixture thì lấy
	 * nhầm trường vẫn xanh — hậu quả thật là một lần sửa chính tả đẩy bài cũ lên
	 * đầu trang.
	 *
	 * Entry thứ hai giữ nhánh dự phòng: `published` vắng mặt thì phải rơi về
	 * `updated`, không được ra `null` (`FeedDates` sẽ NPE ở tầng trên).
	 */
	@Test
	void atom_uu_tien_published_hon_updated() {
		byte[] atom = """
				<?xml version="1.0"?>
				<feed xmlns="http://www.w3.org/2005/Atom">
				  <entry><title>Sửa chính tả hôm nay</title>
				    <link rel="alternate" href="https://example.test/bai-cu"/>
				    <updated>2026-08-06T00:00:00Z</updated>
				    <published>2026-01-01T00:00:00Z</published></entry>
				  <entry><title>Không có published</title>
				    <link rel="alternate" href="https://example.test/chi-co-updated"/>
				    <updated>2026-08-05T00:00:00Z</updated></entry>
				</feed>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(parser.parse(atom))
				.extracting(ParsedItem::publishedAt)
				.containsExactly("2026-01-01T00:00:00Z", "2026-08-05T00:00:00Z");
	}

	/**
	 * RSS 2.0 mang nội dung trong `<description>`.
	 */
	@Test
	void rss_lay_excerpt_tu_description() {
		byte[] body = """
				<?xml version="1.0"?>
				<rss version="2.0"><channel>
				  <item>
				    <title>Bài một</title>
				    <link>https://a.test/1</link>
				    <pubDate>Tue, 05 Aug 2026 10:00:00 GMT</pubDate>
				    <description><![CDATA[<p>Nội dung <b>một</b>.</p>]]></description>
				  </item>
				</channel></rss>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(parser.parse(body))
				.extracting(ParsedItem::excerpt)
				.containsExactly("Nội dung một.");
	}

	/**
	 * RSS cũng có HAI element mang nội dung, y như Atom: `<description>` (tóm
	 * tắt ngắn) và `<content:encoded>` (thân bài đầy đủ). Ưu tiên
	 * `content:encoded` vì cùng lý do với Atom — dài hơn, cho model nhiều thứ
	 * để làm việc hơn.
	 */
	@Test
	void rss_uu_tien_content_encoded_hon_description() {
		byte[] body = """
				<?xml version="1.0"?>
				<rss version="2.0"
				     xmlns:content="http://purl.org/rss/1.0/modules/content/">
				<channel>
				  <item>
				    <title>Có cả hai</title>
				    <link>https://a.test/3</link>
				    <pubDate>Tue, 05 Aug 2026 10:00:00 GMT</pubDate>
				    <description>Tóm tắt ngắn.</description>
				    <content:encoded><![CDATA[<p>Thân bài đầy đủ.</p>]]></content:encoded>
				  </item>
				</channel></rss>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(parser.parse(body))
				.extracting(ParsedItem::excerpt)
				.containsExactly("Thân bài đầy đủ.");
	}

	/**
	 * Spring Blog — nguồn RSS THẬT — không phát `<description>` ở cấp item; nội
	 * dung nằm trong `<content:encoded>`. Chỉ ánh xạ `description` là mọi bài
	 * của nguồn này ra `excerpt == null` và không bài nào có tóm tắt: hỏng im
	 * lặng, không exception, nhìn y hệt "Spring không đăng bài mới".
	 */
	@Test
	void spring_blog_lay_excerpt_tu_content_encoded() throws IOException {
		assertThat(parser.parse(fixture("feeds/spring-blog.xml")))
				.extracting(ParsedItem::excerpt)
				.allSatisfy(e -> assertThat(e).isNotNull())
				.containsExactly("Nội dung đã cắt bớt cho fixture.",
						"Nội dung đã cắt bớt cho fixture.",
						"Nội dung đã cắt bớt cho fixture.");
	}

	/**
	 * Atom có HAI element mang nội dung. `<content>` được ưu tiên vì nó dài hơn
	 * và cho model nhiều thứ để làm việc hơn; `<summary>` là bản lùi.
	 *
	 * Làm ngược lại là một lỗi KHÔNG có exception: bài sẽ luôn cho excerpt dưới
	 * ngưỡng 200 ký tự, và không bài nào của nguồn đó có tóm tắt — nhìn y hệt
	 * "nguồn đó không đăng bài mới".
	 */
	@Test
	void atom_uu_tien_content_hon_summary() {
		byte[] body = """
				<?xml version="1.0"?>
				<feed xmlns="http://www.w3.org/2005/Atom">
				  <entry>
				    <title>Bài Atom</title>
				    <link href="https://b.test/1"/>
				    <published>2026-08-05T10:00:00Z</published>
				    <summary>Tóm tắt ngắn.</summary>
				    <content type="html">&lt;p&gt;Nội dung dài hơn nhiều.&lt;/p&gt;</content>
				  </entry>
				</feed>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(parser.parse(body))
				.extracting(ParsedItem::excerpt)
				.containsExactly("Nội dung dài hơn nhiều.");
	}

	@Test
	void atom_lui_ve_summary_khi_khong_co_content() {
		byte[] body = """
				<?xml version="1.0"?>
				<feed xmlns="http://www.w3.org/2005/Atom">
				  <entry>
				    <title>Bài Atom</title>
				    <link href="https://b.test/2"/>
				    <published>2026-08-05T10:00:00Z</published>
				    <summary>Chỉ có summary.</summary>
				  </entry>
				</feed>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(parser.parse(body))
				.extracting(ParsedItem::excerpt)
				.containsExactly("Chỉ có summary.");
	}

	/**
	 * Item không có element nội dung nào ⇒ `excerpt` là `null`, và item VẪN
	 * được trả về. Bài đó vào bảng bình thường, chỉ là không bao giờ có tóm tắt
	 * (TDD §17 #6). Bỏ hẳn item ở đây sẽ làm trang mất bài — đắt hơn nhiều so
	 * với việc thiếu một đoạn tóm tắt.
	 */
	@Test
	void item_khong_co_noi_dung_van_duoc_giu_voi_excerpt_null() {
		byte[] body = """
				<?xml version="1.0"?>
				<rss version="2.0"><channel>
				  <item>
				    <title>Không mô tả</title>
				    <link>https://a.test/2</link>
				    <pubDate>Tue, 05 Aug 2026 10:00:00 GMT</pubDate>
				  </item>
				</channel></rss>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(parser.parse(body)).singleElement()
				.extracting(ParsedItem::excerpt).isNull();
	}

	/**
	 * Trần `maxExcerptChars` phải được ÁP DỤNG, không chỉ truyền vào cho có.
	 * Không có test này thì một parser bỏ quên tham số vẫn xanh hết, và thứ lộ
	 * ra là hoá đơn Bedrock chứ không phải một dòng đỏ.
	 */
	@Test
	void ap_dung_tran_do_dai_khi_parse() {
		FeedParser hepHoi = new JacksonFeedParser(new XmlConfig().feedXmlMapper(), 12);
		byte[] body = """
				<?xml version="1.0"?>
				<rss version="2.0"><channel>
				  <item>
				    <title>Dài</title>
				    <link>https://a.test/4</link>
				    <description>một hai ba bốn năm sáu bảy tám chín mười</description>
				  </item>
				</channel></rss>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(hepHoi.parse(body))
				.extracting(ParsedItem::excerpt)
				.containsExactly("một hai ba");
	}

	/**
	 * Nguồn RSS thứ hai, để nhánh RSS không bị ghim vào riêng cách Spring Blog
	 * phát feed. AWS khác ở hai chỗ có thật: `guid` là chuỗi băm KHÔNG phải URL
	 * (`isPermaLink="false"`), và `<atom:link>` nằm ngay trong `<channel>` —
	 * cùng localName với `<link>` của RSS.
	 *
	 * Đây cũng là fixture THẬT duy nhất có CẢ `<description>` lẫn
	 * `<content:encoded>`, nên nó ghim luôn thứ tự ưu tiên. `excerpt` ra đúng
	 * chuỗi giữ chỗ vì `content:encoded` của fixture đã bị cắt bớt khi tạo
	 * fixture — `<description>` thật dài 197 ký tự, và nếu ưu tiên ngược lại thì
	 * chính chuỗi 197 ký tự đó hiện ra ở đây.
	 */
	@Test
	void parse_dung_gia_tri_cua_rss_aws() throws IOException {
		List<ParsedItem> items = parser.parse(fixture("feeds/aws-news.xml"));

		assertThat(items).hasSize(3);
		assertThat(items.getFirst()).isEqualTo(new ParsedItem(
				"Amazon DynamoDB now supports real-time vector search at any scale",
				"https://aws.amazon.com/blogs/aws/"
						+ "amazon-dynamodb-now-supports-real-time-vector-search-at-any-scale/",
				"Wed, 05 Aug 2026 14:45:10 +0000",
				"Nội dung đã cắt bớt cho fixture."));
		assertThat(items).extracting(ParsedItem::link)
				.allSatisfy(l -> assertThat(l).startsWith("https://aws.amazon.com/"));
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
	 * Root element có TIỀN TỐ namespace phải được nhận ra là `feed` chứ không
	 * phải `atom:feed`. Tới Task 13 test này còn khẳng định "ném lỗi kèm chữ
	 * feed"; giờ `feed` đã vào `switch` nên nó khẳng định thứ nó luôn nhắm tới:
	 * feed khai bằng tiền tố PHẢI đi qua nhánh Atom và ra item.
	 */
	@Test
	void bo_tien_to_namespace_o_root_element() {
		byte[] atom = """
				<?xml version="1.0"?>
				<atom:feed xmlns:atom="http://www.w3.org/2005/Atom">
				  <atom:entry><atom:title>Khai bằng tiền tố</atom:title>
				    <atom:link rel="alternate" href="https://example.test/tien-to"/>
				    <atom:updated>2026-08-04T00:00:00Z</atom:updated></atom:entry>
				</atom:feed>
				""".getBytes(StandardCharsets.UTF_8);

		assertThat(parser.parse(atom)).singleElement()
				.isEqualTo(new ParsedItem("Khai bằng tiền tố",
						"https://example.test/tien-to", "2026-08-04T00:00:00Z", null));
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
