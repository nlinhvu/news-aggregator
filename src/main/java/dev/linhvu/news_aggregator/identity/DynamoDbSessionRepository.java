package dev.linhvu.news_aggregator.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Repository;

/**
 * Session store của mô hình BFF ([ADR-0018]). Item chứa token của Cognito và
 * KHÔNG BAO GIỜ rời khỏi Lambda.
 *
 * `@Lazy` cùng lý do với `ArticleRepository`: trên đường đọc ẩn danh, bean này
 * không được chạm tới — không cookie thì không tra phiên. Đó là driver #3 của
 * ADR-0018 và `AnonymousReadTest` là chốt chặn.
 */
@Repository
@Lazy
class DynamoDbSessionRepository implements SessionRepository<MapSession> {

	private static final String PK = "sessionId";
	/** Phải khớp `timeToLiveAttribute` của `DataStack.sessionsTable`. */
	private static final String TTL = "expiresAt";
	private static final String ATTRS = "attributes";
	private static final String LAST_ACCESSED = "lastAccessedAt";
	private static final String MAX_INACTIVE = "maxInactiveSeconds";

	private final DynamoDbClient client;
	private final String tableName;
	private final Duration defaultTtl;
	private final SerializingConverter serializer = new SerializingConverter();
	private final DeserializingConverter deserializer = new DeserializingConverter();

	DynamoDbSessionRepository(DynamoDbClient client,
			@Value("${news.identity.sessions-table}") String tableName,
			@Value("${news.identity.session-ttl}") Duration defaultTtl) {
		this.client = client;
		this.tableName = tableName;
		this.defaultTtl = defaultTtl;
	}

	@Override
	public MapSession createSession() {
		MapSession session = new MapSession();
		session.setMaxInactiveInterval(defaultTtl);
		return session;
	}

	@Override
	public void save(MapSession session) {
		Map<String, AttributeValue> item = new HashMap<>();
		item.put(PK, AttributeValue.fromS(session.getId()));
		item.put(LAST_ACCESSED,
				AttributeValue.fromN(String.valueOf(session.getLastAccessedTime().getEpochSecond())));
		item.put(MAX_INACTIVE,
				AttributeValue.fromN(String.valueOf(session.getMaxInactiveInterval().getSeconds())));
		item.put(ATTRS, AttributeValue.fromB(SdkBytes.fromByteArray(
				serializer.convert(attributesOf(session)))));
		// TTL trượt: mỗi lần ghi lại đẩy hạn ra xa. Đây là vế "trượt theo lần
		// dùng" của TDD §17 #15.
		item.put(TTL, AttributeValue.fromN(String.valueOf(
				session.getLastAccessedTime()
						.plus(session.getMaxInactiveInterval()).getEpochSecond())));

		client.putItem(PutItemRequest.builder()
				.tableName(tableName).item(item).build());
	}

	@Override
	public MapSession findById(String id) {
		Map<String, AttributeValue> item = client.getItem(GetItemRequest.builder()
				.tableName(tableName)
				.key(Map.of(PK, AttributeValue.fromS(id)))
				// Đọc nhất quán mạnh: một người vừa đăng nhập xong mà request kế
				// tiếp đọc phải bản cũ sẽ thấy mình chưa đăng nhập. Eventually
				// consistent read rẻ hơn một nửa nhưng đổi lấy đúng cái đó.
				.consistentRead(true)
				.build()).item();

		if (item == null || item.isEmpty()) {
			return null;
		}

		MapSession session = new MapSession(id);
		session.setLastAccessedTime(Instant.ofEpochSecond(
				Long.parseLong(item.get(LAST_ACCESSED).n())));
		session.setMaxInactiveInterval(Duration.ofSeconds(
				Long.parseLong(item.get(MAX_INACTIVE).n())));

		// KIỂM HẠN Ở ĐÂY, không giao cho TTL của DynamoDB. TTL là cơ chế DỌN
		// DẸP và nó chạy trễ tới 48 giờ; tin vào nó nghĩa là mở một cửa sổ 48
		// giờ cho phiên đã hết hạn.
		if (session.isExpired()) {
			deleteById(id);
			return null;
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> attrs = (Map<String, Object>) deserializer.convert(
				item.get(ATTRS).b().asByteArray());
		attrs.forEach(session::setAttribute);
		return session;
	}

	@Override
	public void deleteById(String id) {
		client.deleteItem(DeleteItemRequest.builder()
				.tableName(tableName)
				.key(Map.of(PK, AttributeValue.fromS(id)))
				.build());
	}

	/**
	 * `HashMap` chứ không `Map`: `SerializingConverter` đòi `Serializable` lúc
	 * CHẠY, và kiểu trả về ở đây là thứ duy nhất nói ra ràng buộc đó.
	 */
	private static HashMap<String, Object> attributesOf(MapSession session) {
		HashMap<String, Object> attrs = new HashMap<>();
		session.getAttributeNames().forEach(n -> attrs.put(n, session.getAttribute(n)));
		return attrs;
	}
}
