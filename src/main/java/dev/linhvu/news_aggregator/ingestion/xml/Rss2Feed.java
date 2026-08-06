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
	}
}
