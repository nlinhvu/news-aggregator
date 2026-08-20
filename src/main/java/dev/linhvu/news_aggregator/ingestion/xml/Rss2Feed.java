package dev.linhvu.news_aggregator.ingestion.xml;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/** Ánh xạ RSS 2.0. Chỉ khai những element thực sự dùng. */
public class Rss2Feed {

	@JacksonXmlProperty(localName = "channel")
	public Channel channel;

	public static class Channel {
		@JacksonXmlProperty(localName = "item")
		@JacksonXmlElementWrapper(useWrapping = false)
		public List<Item> items = new ArrayList<>();
	}

	public static class Item {
		public String title;
		public String link;
		public String guid;
		public String pubDate;

		/**
		 * Nội dung để tóm tắt. Thường là HTML trong CDATA — `FeedExcerpt` lo
		 * phần strip. Có thể vắng mặt; khi đó bài vẫn vào bảng, chỉ không có
		 * tóm tắt (TDD §17 #6).
		 */
		public String description;

		/**
		 * `<content:encoded>` của module content — thân bài đầy đủ, và với một
		 * số nguồn là nơi DUY NHẤT có nội dung: Spring Blog không phát
		 * `<description>` ở cấp item. Bỏ element này thì mọi bài của nguồn đó ra
		 * `excerpt == null` mà không có gì đỏ — xem
		 * `spring_blog_takes_the_excerpt_from_content_encoded`.
		 */
		@JacksonXmlProperty(localName = "encoded")
		public String contentEncoded;
	}
}
