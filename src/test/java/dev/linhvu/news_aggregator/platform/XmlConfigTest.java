package dev.linhvu.news_aggregator.platform;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.xml.XmlMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kiểm chính `XmlConfig` chứ không kiểm một `XmlMapper` dựng lại trong test.
 *
 * Phân biệt này là toàn bộ giá trị của lớp test này. Siết XXE là **biện pháp an
 * ninh duy nhất** của đường ingestion — feed là XML từ máy chủ người khác, tức
 * input không tin cậy đúng nghĩa. Một test tự dựng mapper của riêng nó sẽ xanh
 * y hệt kể cả khi `XmlConfig` quên tắt DTD, vì nó có bao giờ chạm tới
 * `XmlConfig` đâu.
 *
 * Gọi thẳng `new XmlConfig().feedXmlMapper()` được vì đó là `@Configuration`
 * không state — không cần Spring context, nên đây vẫn là T1 chạy trong mili
 * giây.
 */
class XmlConfigTest {

	private final XmlMapper mapper = new XmlConfig().feedXmlMapper();

	/**
	 * XXE cổ điển: entity ngoài trỏ vào file cục bộ. Trên một Lambda, `file:///`
	 * đọc được cả `/proc/self/environ` — nơi AWS đặt credential của execution
	 * role. Đây không phải rủi ro lý thuyết.
	 */
	@Test
	void rejects_external_entities() {
		String xxe = """
				<?xml version="1.0"?>
				<!DOCTYPE rss [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
				<rss version="2.0"><channel><item><title>&xxe;</title></item></channel></rss>
				""";

		assertThatThrownBy(() -> mapper.readValue(bytes(xxe), JsonNode.class))
				.as("DTD phải bị từ chối, không phải được giải rồi trả về")
				.isNotNull();
	}

	/**
	 * Billion laughs — entity NỘI BỘ, không ra ngoài mạng, nên
	 * `IS_SUPPORTING_EXTERNAL_ENTITIES` một mình KHÔNG chặn được. Nó chết bằng
	 * `SUPPORT_DTD=false`, và đó là lý do phải đặt cả hai property chứ không
	 * phải một.
	 */
	@Test
	void rejects_the_internal_entity_that_blows_up_memory() {
		String lol = """
				<?xml version="1.0"?>
				<!DOCTYPE lolz [
				  <!ENTITY lol "lol">
				  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
				  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
				]>
				<rss version="2.0"><channel><item><title>&lol3;</title></item></channel></rss>
				""";

		assertThatThrownBy(() -> mapper.readValue(bytes(lol), JsonNode.class))
				.isNotNull();
	}

	/**
	 * ĐỐI CHỨNG ÂM. Hai test trên khẳng định "có ném exception" — nhưng một
	 * payload viết sai cú pháp cũng ném exception, và khi đó chúng xanh mà
	 * không chứng minh gì về việc siết XXE cả. Test này dựng một mapper CỐ Ý
	 * KHÔNG siết và cho thấy cùng payload ấy đi qua trót lọt: entity được giải,
	 * `&greeting;` thành nội dung thật.
	 *
	 * Dùng entity NỘI BỘ chứ không `file:///etc/passwd` — đối chứng không được
	 * phụ thuộc vào việc máy chạy test có file nào.
	 *
	 * Bảng đã đo 2026-08-06 với đúng payload `file:///etc/passwd`:
	 *
	 * | SUPPORT_DTD | EXTERNAL_ENTITIES | kết quả |
	 * |---|---|---|
	 * | false | false | ném `Undeclared general entity` ← cấu hình production |
	 * | true  | false | ném `…external entity… feature disabled` |
	 * | true  | true  | **KHÔNG ném gì — XXE thành công** |
	 *
	 * Nên `IS_SUPPORTING_EXTERNAL_ENTITIES` KHÔNG thừa dù `SUPPORT_DTD=false`
	 * đã chặn trước: nó là lớp cuối cùng nếu có ai bật lại DTD. Mutation test
	 * cho thấy lật riêng nó thì không test nào đỏ — đúng và vô hại, vì lật
	 * riêng nó cũng không khai thác được. Lật CẢ HAI thì
	 * `rejects_external_entities` đỏ ngay.
	 */
	@Test
	void a_control_mapper_without_hardening_does_expand_the_entity() {
		String xml = """
				<?xml version="1.0"?>
				<!DOCTYPE rss [ <!ENTITY greeting "xin chào"> ]>
				<rss version="2.0"><channel><item><title>&greeting;</title></item></channel></rss>
				""";

		XmlMapper unhardened = XmlMapper.builder(
				tools.jackson.dataformat.xml.XmlFactory.builder()
						.xmlInputFactory(dtdReEnabled()).build()).build();

		assertThat(unhardened.readValue(bytes(xml), JsonNode.class)
				.path("channel").path("item").path("title").asString())
				.isEqualTo("xin chào");

		// Cùng payload, mapper của production: bị chặn.
		assertThatThrownBy(() -> mapper.readValue(bytes(xml), JsonNode.class))
				.isNotNull();
	}

	private static javax.xml.stream.XMLInputFactory dtdReEnabled() {
		javax.xml.stream.XMLInputFactory f = javax.xml.stream.XMLInputFactory.newFactory();
		f.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, true);
		f.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, true);
		return f;
	}

	/**
	 * Mặt còn lại: XML hợp lệ mang element ta không khai báo phải đi qua bình
	 * thường. Feed ngoài đời luôn có `dc:creator`, `content:encoded`,
	 * `slash:comments`… — chết vì chúng là biến một chi tiết vô hại thành lỗi
	 * cả nguồn.
	 */
	@Test
	void accepts_undeclared_elements() {
		String xml = """
				<?xml version="1.0"?>
				<rss xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
				  <channel><item>
				    <title>Bài</title>
				    <dc:creator>ai đó</dc:creator>
				  </item></channel>
				</rss>
				""";

		JsonNode tree = mapper.readValue(bytes(xml), JsonNode.class);

		assertThat(tree.path("channel").path("item").path("title").asString())
				.isEqualTo("Bài");
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}
}
