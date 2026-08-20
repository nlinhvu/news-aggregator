package dev.linhvu.news_aggregator.identity;

import java.time.Duration;
import java.time.Instant;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.MapSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KHÔNG `@ActiveProfiles`: `DynamoDbSessionRepository` không mang `@Profile`
 * nào, nên nó có mặt ở mọi context — giống hệt `ArticleRepositoryTest`, hàng
 * xóm gần nhất của test này. Plan viết `{ "test", "web" }`; `web` là thừa, còn
 * `test` thì KHÔNG TỒN TẠI — repo không có `application-test.yaml`, và
 * `RoleProfileContextTest` đã ghi thẳng điều đó ra thành một khẳng định.
 */
@SpringBootTest
@Import(FlociTestConfiguration.class)
class DynamoDbSessionRepositoryTest {

	@Autowired
	DynamoDbSessionRepository repository;

	@Test
	void save_then_read_back_intact() {
		MapSession session = repository.createSession();
		session.setAttribute("sub", "u-123");
		repository.save(session);

		MapSession loaded = repository.findById(session.getId());

		assertThat(loaded).isNotNull();
		assertThat(loaded.<String>getAttribute("sub")).isEqualTo("u-123");
	}

	@Test
	void delete_removes_it_at_once_without_waiting_for_the_ttl() {
		// Đăng xuất phải có hiệu lực TỨC THÌ. TTL của DynamoDB xoá item trong
		// vòng tối đa 48 giờ sau `expiresAt` — nếu đăng xuất chỉ hạ `expiresAt`
		// thì phiên vẫn dùng được cho tới lúc dọn dẹp, và nút đăng xuất nói dối.
		MapSession session = repository.createSession();
		repository.save(session);

		repository.deleteById(session.getId());

		assertThat(repository.findById(session.getId())).isNull();
	}

	@Test
	void an_expired_session_is_unreadable_even_though_the_item_is_still_in_the_table() {
		// TTL của DynamoDB là DỌN DẸP, không phải cơ chế kiểm hạn. Nó chạy trễ
		// tới 48 giờ. Nếu repository tin vào TTL thì có một cửa sổ 48 giờ mà
		// phiên đã hết hạn vẫn đăng nhập được — chính xác là chế độ hỏng mà
		// không ai phát hiện ra cho tới khi bị lợi dụng.
		MapSession session = repository.createSession();
		session.setLastAccessedTime(Instant.now().minus(Duration.ofDays(40)));
		session.setMaxInactiveInterval(Duration.ofDays(30));
		repository.save(session);

		assertThat(repository.findById(session.getId())).isNull();
	}
}
