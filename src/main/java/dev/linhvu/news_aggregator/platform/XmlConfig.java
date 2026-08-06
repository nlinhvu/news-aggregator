package dev.linhvu.news_aggregator.platform;

import javax.xml.stream.XMLInputFactory;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.xml.XmlFactory;
import tools.jackson.dataformat.xml.XmlMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * `XmlMapper` phải tự dựng: `JacksonAutoConfiguration` của Boot 4 chỉ cấu hình
 * `JsonMapper` và `CborMapper`. Đó không phải phiền toái mà là chỗ BẮT BUỘC
 * phải đi qua — feed là XML từ máy chủ của người khác, tức là input không tin
 * cậy đúng nghĩa, và siết XXE là việc phải làm tường minh.
 *
 * Kiểm chứng ở `XmlConfigTest`, và nó gọi THẲNG `new XmlConfig().feedXmlMapper()`
 * chứ không dựng lại mapper — xem lý do ở đó.
 */
@Configuration(proxyBeanMethods = false)
public class XmlConfig {

	@Bean
	@Lazy
	public XmlMapper feedXmlMapper() {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		// Tắt DTD chặn cả XXE lẫn billion-laughs. Hai property vì hai tầng:
		// SUPPORT_DTD chặn khai báo, IS_SUPPORTING_EXTERNAL_ENTITIES chặn giải
		// tham chiếu ra ngoài.
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

		return XmlMapper.builder(XmlFactory.builder().xmlInputFactory(factory).build())
				// Feed ngoài đời luôn có element ta không khai báo (dc:creator,
				// content:encoded, slash:comments…). Chết vì chúng là biến một
				// chi tiết vô hại thành lỗi cả nguồn.
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.build();
	}
}
