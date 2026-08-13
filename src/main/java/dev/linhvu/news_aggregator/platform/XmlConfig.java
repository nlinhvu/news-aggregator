package dev.linhvu.news_aggregator.platform;

import java.util.List;

import javax.xml.stream.XMLInputFactory;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.xml.XmlFactory;
import tools.jackson.dataformat.xml.XmlMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.xml.JacksonXmlHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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

	/**
	 * XML là định dạng của ĐẦU VÀO (feed), KHÔNG BAO GIỜ của đầu ra.
	 *
	 * `jackson-dataformat-xml` có mặt trên classpath chỉ để đọc feed, nhưng
	 * Spring tự đăng ký `JacksonXmlHttpMessageConverter` cho tầng HTTP ngay khi
	 * thấy nó. Hệ quả đo được trên dev 2026-08-13 — và nó có từ Phase 1, không
	 * ai để ý suốt sáu phase:
	 *
	 * <pre>
	 *   Accept: *&#47;*                                    → [{"id":"eed552f0…",…}]   JSON
	 *   Accept: text&#47;html,…,application&#47;xml;q=0.9,*&#47;*   → &lt;List&gt;&lt;item&gt;&lt;id&gt;…      XML
	 * </pre>
	 *
	 * Vế thứ hai là `Accept` của MỌI trình duyệt. Tức `/api/articles` — sản phẩm
	 * chính — nói sai hợp đồng JSON mà nó công bố, cho bất kỳ ai mở nó bằng trình
	 * duyệt. SPA dùng `fetch` gửi `*&#47;*` nên không vỡ, và đó đúng là lý do lỗi
	 * này sống lâu đến vậy.
	 *
	 * Gỡ ở tầng CONVERTER chứ không gắn `produces` lên từng controller: cách sau
	 * đúng cho hôm nay và im lặng cho endpoint viết ngày mai.
	 *
	 * Client nào đòi ĐÍCH DANH `application/xml` từ nay nhận 406, và đó là câu
	 * trả lời trung thực — ta không phục vụ XML.
	 */
	@Bean
	public WebMvcConfigurer khongPhucVuXmlQuaHttp() {
		return new WebMvcConfigurer() {
			@Override
			public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
				converters.removeIf(JacksonXmlHttpMessageConverter.class::isInstance);
			}
		};
	}

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
