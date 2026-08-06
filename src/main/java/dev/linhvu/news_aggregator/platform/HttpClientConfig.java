package dev.linhvu.news_aggregator.platform;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;

/**
 * `User-Agent` định danh được kèm URL liên hệ, đúng etiquette của feed reader.
 * CỐ Ý không giả làm trình duyệt: nếu một nguồn từ chối bot thì đó là quyết
 * định của họ và phải tôn trọng (master §8.4). Nguồn bị chặn thì loại khỏi
 * `sources.yaml`, không phải tìm cách lách — Baeldung đã bị loại đúng vì lẽ đó.
 *
 * Tên class settings là `HttpClientSettings`, KHÔNG phải
 * `ClientHttpRequestFactorySettings` — cái sau tồn tại ở Boot 3.x
 * (`org.springframework.boot.web.client`) nhưng đã biến mất ở 4.1.0. Cả hai
 * class ở đây nằm trong artifact riêng `spring-boot-http-client`, kéo vào
 * gián tiếp qua `spring-boot-starter-restclient`.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

	@Bean
	@Lazy
	public RestClient feedRestClient(
			@Value("${news.ingestion.user-agent}") String userAgent,
			@Value("${news.ingestion.connect-timeout}") Duration connectTimeout,
			@Value("${news.ingestion.read-timeout}") Duration readTimeout) {

		return RestClient.builder()
				.requestFactory(ClientHttpRequestFactoryBuilder.detect()
						.build(HttpClientSettings.defaults()
								.withConnectTimeout(connectTimeout)
								.withReadTimeout(readTimeout)))
				.defaultHeader("User-Agent", userAgent)
				.build();
	}
}
