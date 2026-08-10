package dev.linhvu.news_aggregator.ingestion.xml;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/** Ánh xạ Atom (RFC 4287). Chỉ khai những element thực sự dùng. */
public class AtomFeed {

	@JacksonXmlProperty(localName = "entry")
	@JacksonXmlElementWrapper(useWrapping = false)
	public List<Entry> entries = new ArrayList<>();

	public static class Entry {
		public String title;
		public String id;

		/**
		 * Atom cho phép NHIỀU `<link>` với `rel` khác nhau (alternate, edit,
		 * replies…). Lấy nhầm cái không phải `alternate` sẽ dẫn người đọc tới
		 * API endpoint thay vì bài viết.
		 */
		@JacksonXmlProperty(localName = "link")
		@JacksonXmlElementWrapper(useWrapping = false)
		public List<Link> links = new ArrayList<>();

		public String published;
		public String updated;

		/**
		 * Atom có HAI element mang nội dung. `content` dài hơn nên được ưu tiên;
		 * `summary` là bản lùi. Ưu tiên ngược lại là một lỗi KHÔNG có exception —
		 * xem `atom_uu_tien_content_hon_summary`.
		 */
		public String summary;

		public String content;
	}

	public static class Link {
		@JacksonXmlProperty(isAttribute = true)
		public String href;

		@JacksonXmlProperty(isAttribute = true)
		public String rel;
	}
}
