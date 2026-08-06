package dev.linhvu.news_aggregator.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.linhvu.news_aggregator.ingestion.xml.AtomFeed;
import dev.linhvu.news_aggregator.ingestion.xml.Rss2Feed;
import tools.jackson.dataformat.xml.XmlMapper;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Chọn parser bằng ROOT ELEMENT, không bao giờ bằng `Content-Type` hay đuôi URL.
 *
 * Đây không phải phòng xa. Đo 2026-08-05, kiểm lại 2026-08-06 (TDD §13): Spring
 * Blog phục vụ `<rss version="2.0">` dưới đuôi `.atom` VÀ header
 * `application/atom+xml`; Inside Java phục vụ Atom dưới `application/xml`. Ba
 * trên bốn nguồn khai sai hoặc mơ hồ.
 */
@Component
@Lazy
class JacksonFeedParser implements FeedParser {

	/**
	 * Tên element mở đầu tiên. `<` rồi tên hợp lệ, kết thúc bằng khoảng trắng,
	 * `>` hoặc `/`. Khai báo XML (`<?xml`), DOCTYPE (`<!DOCTYPE`) và comment
	 * (`<!--`) tự rơi ra ngoài vì `?` và `!` không thuộc lớp ký tự mở đầu.
	 *
	 * `:` PHẢI nằm trong lớp ký tự. Thiếu nó thì `<atom:feed>` không khớp được
	 * gì cả và hàm ném "không tìm thấy root element" — vừa sai thông điệp, vừa
	 * làm đoạn cắt tiền tố bên dưới thành code chết. Không nguồn nào trong
	 * `sources.yaml` khai bằng tiền tố (Inside Java dùng xmlns mặc định), nên
	 * nhánh này chỉ được giữ sống bằng test — xem
	 * `bo_tien_to_namespace_o_root_element`.
	 */
	private static final Pattern ROOT_ELEMENT =
			Pattern.compile("<([A-Za-z_][\\w.:-]*)(?:\\s|>|/)");

	private final XmlMapper xmlMapper;

	JacksonFeedParser(XmlMapper xmlMapper) {
		this.xmlMapper = xmlMapper;
	}

	@Override
	public List<ParsedItem> parse(byte[] body) {
		String root = rootElement(body);
		return switch (root) {
			case "rss" -> parseRss(body);
			case "feed" -> parseAtom(body);
			default -> throw new IllegalArgumentException(
					"root element không được hỗ trợ: " + root);
		};
	}

	private List<ParsedItem> parseAtom(byte[] body) {
		AtomFeed feed = xmlMapper.readValue(body, AtomFeed.class);
		return feed.entries.stream()
				.map(e -> new ParsedItem(e.title, alternateLink(e),
						// `published` là ngày đăng; `updated` là ngày sửa cuối và
						// LUÔN có mặt. Ưu tiên `published` để một lần sửa chính tả
						// không đẩy bài cũ lên đầu danh sách.
						e.published != null ? e.published : e.updated))
				.toList();
	}

	private static String alternateLink(AtomFeed.Entry entry) {
		return entry.links.stream()
				.filter(l -> l.href != null)
				// `rel` vắng mặt nghĩa là `alternate` theo RFC 4287 §4.2.7.2.
				.filter(l -> l.rel == null || "alternate".equals(l.rel))
				.map(l -> l.href)
				.findFirst()
				.orElse(entry.id);
	}

	private List<ParsedItem> parseRss(byte[] body) {
		Rss2Feed feed = xmlMapper.readValue(body, Rss2Feed.class);
		if (feed.channel == null) {
			return List.of();
		}
		return feed.channel.items.stream()
				.map(i -> new ParsedItem(i.title,
						// `<link>` là nguồn của identity (TDD §17 #13): nó là thứ
						// master §8.4 buộc phải hiển thị, nên suy id từ chính nó
						// khiến id và link không bao giờ lệch nhau được.
						i.link != null ? i.link : i.guid,
						i.pubDate))
				.toList();
	}

	/**
	 * Đọc tên element mở đầu tiên mà không dựng cả cây. Bỏ qua khai báo XML,
	 * comment và khoảng trắng đứng trước.
	 */
	private static String rootElement(byte[] body) {
		String head = new String(body, 0, Math.min(body.length, 2048),
				StandardCharsets.UTF_8);
		Matcher m = ROOT_ELEMENT.matcher(head
				.replaceAll("(?s)<\\?.*?\\?>", "")
				.replaceAll("(?s)<!--.*?-->", ""));
		if (!m.find()) {
			throw new IllegalArgumentException("không tìm thấy root element");
		}
		String name = m.group(1);
		int colon = name.indexOf(':');
		return colon < 0 ? name : name.substring(colon + 1);
	}
}
