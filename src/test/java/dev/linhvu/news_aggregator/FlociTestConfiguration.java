package dev.linhvu.news_aggregator;

import java.util.concurrent.CompletionException;

import dev.linhvu.news_aggregator.catalog.Article;
import io.floci.testcontainers.FlociContainer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
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
				.withDynamoDbConfig(b -> b.enabled(true))
				.withSqsConfig(b -> b.enabled(true));
	}

	/**
	 * Schema sống ở ĐÂY, không ở trong test nào cả — fixture này đóng đúng vai
	 * mà `DataStack` đóng trên AWS thật (master §4 nguyên tắc 7: schema thuộc
	 * IaC, ứng dụng chỉ đọc/ghi). Ứng dụng KHÔNG được tự tạo bảng, nên mọi test
	 * T2 phải nhận bảng từ bên ngoài.
	 *
	 * Đặt ở tầng config chứ không phải `@BeforeEach` của từng test là CỐ Ý: mỗi
	 * Spring context lấy một container Floci riêng, nên test nào tự tạo bảng thì
	 * chỉ tự cứu mình. `ArticleControllerTest` đã chết đúng vì lẽ đó — DynamoDB
	 * phân biệt "bảng rỗng" với "không có bảng", và cái sau ném
	 * `ResourceNotFoundException` chứ không trả về danh sách rỗng.
	 *
	 * Định nghĩa dưới đây phải soi gương `DataStack.articlesTable`. Hai module
	 * không thấy nhau nên lệch nhau sẽ KHÔNG có lỗi compile: test vẫn xanh trong
	 * khi prod hỏng. Sửa GSI ở một bên thì sửa cả bên kia.
	 */
	@Bean
	InitializingBean articlesTableSchema(DynamoDbClient dynamoDbClient,
			@Value("${news.catalog.table-name}") String tableName) {
		return () -> {
			try {
				dynamoDbClient.createTable(CreateTableRequest.builder()
						.tableName(tableName)
						.keySchema(KeySchemaElement.builder()
								.attributeName("articleId").keyType(KeyType.HASH).build())
						.attributeDefinitions(
								AttributeDefinition.builder().attributeName("articleId")
										.attributeType(ScalarAttributeType.S).build(),
								AttributeDefinition.builder().attributeName("listBucket")
										.attributeType(ScalarAttributeType.S).build(),
								AttributeDefinition.builder().attributeName("publishedAt")
										.attributeType(ScalarAttributeType.S).build())
						.globalSecondaryIndexes(GlobalSecondaryIndex.builder()
								.indexName(Article.RECENT_INDEX)
								.keySchema(
										KeySchemaElement.builder().attributeName("listBucket")
												.keyType(KeyType.HASH).build(),
										KeySchemaElement.builder().attributeName("publishedAt")
												.keyType(KeyType.RANGE).build())
								.projection(Projection.builder()
										.projectionType(ProjectionType.INCLUDE)
										.nonKeyAttributes("title", "canonicalUrl",
												"sourceName", "summary", "excerpt")
										.build())
								.build())
						.billingMode(BillingMode.PAY_PER_REQUEST)
						.build());
			} catch (ResourceInUseException ignored) {
				// container dùng lại giữa các context — bảng đã có sẵn
			}
		};
	}

	/**
	 * Bảng feature-toggles, soi gương `DataStack.featureTogglesTable`.
	 *
	 * Bắt buộc phải có, không phải "tiện thì thêm": `TogglzAutoConfiguration`
	 * dựng bean `featureManager` NGAY lúc khởi động và nó inject `StateRepository`
	 * theo kiểu eager, nên `@Lazy` trên bean đó KHÔNG cứu được. Builder của
	 * togglz-dynamodb gọi `describeTable` rồi ném RuntimeException — thiếu bảng
	 * thì MỌI test có Spring context đều chết, kể cả test không liên quan gì tới
	 * feature flag.
	 *
	 * Tên attribute `featureName` là hợp đồng của thư viện, không phải lựa chọn —
	 * xem `DataStack.TOGGLZ_PRIMARY_KEY`. Hai module không thấy nhau nên chuỗi này
	 * phải khớp thủ công; lệch nhau thì test vẫn xanh trong khi prod hỏng.
	 */
	@Bean
	InitializingBean featureTogglesTableSchema(DynamoDbClient dynamoDbClient,
			@Value("${news.togglz.table-name}") String tableName) {
		return () -> {
			try {
				dynamoDbClient.createTable(CreateTableRequest.builder()
						.tableName(tableName)
						.keySchema(KeySchemaElement.builder()
								.attributeName("featureName").keyType(KeyType.HASH).build())
						.attributeDefinitions(
								AttributeDefinition.builder().attributeName("featureName")
										.attributeType(ScalarAttributeType.S).build())
						.billingMode(BillingMode.PAY_PER_REQUEST)
						.build());
			} catch (ResourceInUseException ignored) {
				// container dùng lại giữa các context — bảng đã có sẵn
			}
		};
	}

	/**
	 * Bảng `sources`, soi gương `DataStack.sourcesTable`.
	 *
	 * Cùng lý do như hai bảng kia: ứng dụng KHÔNG tự tạo bảng (master §4 nguyên
	 * tắc 7), nên test phải nhận bảng từ bên ngoài. DynamoDB phân biệt "bảng
	 * rỗng" với "không có bảng" — cái sau ném `ResourceNotFoundException` chứ
	 * không trả về danh sách rỗng.
	 *
	 * KHÔNG có GSI, và đó là một khẳng định chứ không phải chỗ bỏ sót — xem
	 * `DataStackTest#bang_sources_khong_co_gsi`. Thêm GSI ở một bên thì thêm cả
	 * bên kia.
	 */
	@Bean
	InitializingBean sourcesTableSchema(DynamoDbClient dynamoDbClient,
			@Value("${news.sources.table-name}") String tableName) {
		return () -> {
			try {
				dynamoDbClient.createTable(CreateTableRequest.builder()
						.tableName(tableName)
						.keySchema(KeySchemaElement.builder()
								.attributeName("sourceId").keyType(KeyType.HASH).build())
						.attributeDefinitions(
								AttributeDefinition.builder().attributeName("sourceId")
										.attributeType(ScalarAttributeType.S).build())
						.billingMode(BillingMode.PAY_PER_REQUEST)
						.build());
			} catch (ResourceInUseException ignored) {
				// container dùng lại giữa các context — bảng đã có sẵn
			}
		};
	}

	/**
	 * Queue `summarize-queue` cho test T2, soi gương `AppStack.summarizeQueue`.
	 *
	 * Cùng lý do như ba bảng DynamoDB ở trên: ứng dụng KHÔNG tự tạo hạ tầng, nên
	 * test phải nhận queue từ bên ngoài. SQS phân biệt "queue rỗng" với "không có
	 * queue" — cái sau ném `QueueDoesNotExistException`.
	 *
	 * `SqsAsyncClient` chứ KHÔNG phải `SqsClient`: `SqsAutoConfiguration` của
	 * spring-cloud-aws 4.1.0 chỉ dựng bean async (cộng `SqsTemplate` và listener
	 * container factory). KHÔNG có bean `SqsClient` sync nào trên classpath, nên
	 * inject nó là `NoSuchBeanDefinitionException` và MỌI test có Spring context
	 * chết theo, không riêng test SQS.
	 *
	 * KHÔNG mô phỏng DLQ hay redrive policy ở đây: `maxReceiveCount` là hành vi
	 * của ESM, và ESM không có bản local nào (TDD §11). Test T2 chỉ chứng minh
	 * message được GỬI đúng; phần giao lại là việc của smoke test trên prod.
	 */
	@Bean
	InitializingBean summarizeQueueFixture(SqsAsyncClient sqsAsyncClient) {
		return () -> {
			try {
				sqsAsyncClient.createQueue(CreateQueueRequest.builder()
						.queueName("summarize-queue").build()).join();
			} catch (QueueNameExistsException ignored) {
				// container dùng lại giữa các context — queue đã có sẵn
			} catch (CompletionException e) {
				// `join()` gói mọi lỗi vào CompletionException, nên nhánh catch
				// phía trên một mình KHÔNG bao giờ bắt được gì.
				if (!(e.getCause() instanceof QueueNameExistsException)) {
					throw e;
				}
			}
		};
	}
}
