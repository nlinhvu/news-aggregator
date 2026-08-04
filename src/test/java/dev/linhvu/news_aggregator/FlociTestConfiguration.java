package dev.linhvu.news_aggregator;

import io.floci.testcontainers.FlociContainer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class FlociTestConfiguration {

	@Bean
	@ServiceConnection
	FlociContainer flociContainer() {
		// `floci/floci` là tên canonical mà `FlociContainer` tự kiểm bằng
		// `assertCompatibleWith` — đưa tên khác vào là chết ngay lúc dựng bean,
		// trước cả khi Docker kịp pull. Testcontainers gợi ý
		// `asCompatibleSubstituteFor("floci/floci")` để ép qua, nhưng ở đây ép là
		// SAI: `ghcr.io/floci-io/floci` không tồn tại, nên ép chỉ đổi lỗi rõ ràng
		// lúc dựng bean thành lỗi pull tối nghĩa ở tận trong Docker.
		return new FlociContainer("floci/floci:latest")
				.disableAllServices()
				.withDynamoDbConfig(b -> b.enabled(true));
	}
}
