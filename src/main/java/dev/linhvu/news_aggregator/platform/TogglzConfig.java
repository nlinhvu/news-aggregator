package dev.linhvu.news_aggregator.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.togglz.core.Feature;
import org.togglz.core.repository.FeatureState;
import org.togglz.core.repository.StateRepository;
import org.togglz.core.user.NoOpUserProvider;
import org.togglz.core.user.UserProvider;
import org.togglz.dynamodb.DynamoDBStateRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration(proxyBeanMethods = false)
public class TogglzConfig {

	/**
	 * Bean này KHÔNG thêm hành vi nào — nó GIỮ NGUYÊN hành vi đã có. Trước
	 * Phase 7, `TogglzAutoConfiguration` tự dựng đúng `NoOpUserProvider` này.
	 *
	 * Phase 7 đưa `spring-boot-starter-oauth2-client` vào, và bean tự động kia
	 * là `@ConditionalOnMissingClass(EnableWebSecurity)` nên nó BACK OFF. Bean
	 * thay thế (`SpringSecurityUserProviderConfiguration`) lại
	 * `@ConditionalOnClass(SpringSecurityUserProvider)` — class đó nằm ở
	 * artifact `togglz-spring-security`, thứ ta không có. Kết quả: KHÔNG bean
	 * `UserProvider` nào tồn tại, mà `featureManager` inject nó EAGER, nên MỌI
	 * Spring context chết ngay lúc khởi động — kể cả context không đụng gì tới
	 * feature flag. Cùng chế độ hỏng đã ghi ở
	 * `FlociTestConfiguration#featureTogglesTableSchema`, chỉ khác nguyên nhân.
	 *
	 * ⚠️ NGƯỠNG PHẢI ĐỔI: lúc bật Togglz console (`togglz.console.enabled`, hẹn
	 * ở Phase 4). `NoOpUserProvider` trả về "không có người dùng", nên kiểm tra
	 * quyền admin của console mất hiệu lực — bật console mà giữ dòng này nghĩa
	 * là ai vào được console cũng bật/tắt flag được. Lúc đó đổi sang
	 * `togglz-spring-security` + `SpringSecurityUserProvider`.
	 */
	@Bean
	UserProvider userProvider() {
		return new NoOpUserProvider();
	}

	/**
	 * @Lazy giữ lại vì lý do ở AwsClientConfig, nhưng đừng trông cậy vào nó ở đây:
	 * `TogglzAutoConfiguration` dựng bean `featureManager` lúc khởi động và inject
	 * `StateRepository` theo kiểu eager, nên bean này bị ép tạo ngay bất kể @Lazy.
	 *
	 * CỐ Ý KHÔNG bọc CachingStateRepository, dù ví dụ chính thức của
	 * togglz-dynamodb có bọc với TTL 30 giây.
	 *
	 * Lý do: trên Lambda, cache là PER-EXECUTION-ENVIRONMENT, nên trong cửa
	 * sổ TTL các instance sẽ trả lời khác nhau cho cùng một câu hỏi — khó
	 * hiểu khi demo và khó tin khi debug. Mỗi request là một GetItem; ở dưới
	 * 1.000 lượt xem/ngày thì chi phí không đáng kể, đổi lại flag có hiệu
	 * lực NGAY.
	 *
	 * Đừng "sửa" chỗ này theo docs của thư viện mà không đọc TDD §13 trước.
	 */
	@Bean
	@Lazy
	StateRepository stateRepository(
			DynamoDbClient dynamoDbClient,
			@Value("${news.togglz.table-name}") String tableName) {
		return new FailClosedDynamoDbStateRepository(dynamoDbClient, tableName);
	}

	/**
	 * Hoãn việc dựng `DynamoDBStateRepository` tới lần ĐỌC FLAG đầu tiên, và nuốt
	 * mọi lỗi đọc thành `null`.
	 *
	 * Vì sao không dùng thẳng `DynamoDBStateRepository` như plan viết: builder của
	 * nó gọi `describeTable` NGAY trong `build()` và ném RuntimeException nếu hỏng.
	 * Vì `featureManager` inject eager, lỗi đó thành **lỗi khởi động** — bảng
	 * feature-toggles không đọc được thì ứng dụng không boot nổi, kể cả
	 * `/api/health`. Đó là vi phạm trực tiếp TDD §5.4: *"Lỗi đọc flag không được
	 * làm hỏng cả trang"*. Đã kiểm chứng: trước lớp này, `contextLoads()` gọi
	 * DynamoDB thật lúc khởi động và chết khi không có credential.
	 *
	 * Trả `null` là hợp đồng fail-closed của Togglz, không phải mẹo:
	 * `DefaultFeatureManager.isActive` kiểm `ifnonnull` rồi rơi về
	 * `getMetaData(feature).getDefaultFeatureState()`, mà enum `NewsFeature` không
	 * có `@EnabledByDefault` nên mặc định là OFF. Đọc flag hỏng ⇒ tính năng tắt ⇒
	 * request vẫn thành công.
	 *
	 * Delegate được nhớ lại sau lần dựng thành công đầu tiên, và KHÔNG nhớ lần
	 * thất bại — nên khi bảng đọc được trở lại (quyền được khôi phục, throttle hết)
	 * flag tự sống lại mà không cần deploy hay restart.
	 */
	static final class FailClosedDynamoDbStateRepository implements StateRepository {

		private static final Logger log =
				LoggerFactory.getLogger(FailClosedDynamoDbStateRepository.class);

		private final DynamoDbClient dynamoDbClient;
		private final String tableName;
		private volatile StateRepository delegate;

		FailClosedDynamoDbStateRepository(DynamoDbClient dynamoDbClient, String tableName) {
			this.dynamoDbClient = dynamoDbClient;
			this.tableName = tableName;
		}

		@Override
		public FeatureState getFeatureState(Feature feature) {
			try {
				return delegate().getFeatureState(feature);
			}
			catch (RuntimeException e) {
				log.warn("Không đọc được trạng thái flag {} từ bảng {} — coi như OFF",
						feature.name(), tableName, e);
				return null;
			}
		}

		/**
		 * Đường GHI duy nhất là Togglz console, đang tắt; execution role của Lambda
		 * cũng không có `dynamodb:UpdateItem`. Không bọc try/catch ở đây là cố ý:
		 * một lệnh ghi thất bại phải nổ ra chứ không được im lặng.
		 */
		@Override
		public void setFeatureState(FeatureState featureState) {
			delegate().setFeatureState(featureState);
		}

		private StateRepository delegate() {
			StateRepository current = delegate;
			if (current == null) {
				current = new DynamoDBStateRepository
						.DynamoDBStateRepositoryBuilder(dynamoDbClient)
						.withStateStoredInTable(tableName)
						.build();
				delegate = current;
			}
			return current;
		}
	}
}
