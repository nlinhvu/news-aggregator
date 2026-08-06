package dev.linhvu.news_aggregator.sources;

import java.util.List;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.sources.api.SourceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(FlociTestConfiguration.class)
class SourceCatalogTest {

	@Autowired
	SourceCatalog catalog;

	@Autowired
	DynamoDbEnhancedClient enhancedClient;

	@Value("${news.sources.table-name}")
	String tableName;

	DynamoDbTable<Source> table;

	/**
	 * Dọn bảng trước mỗi test vì cả lớp dùng CHUNG một container: Spring cache
	 * context nên `FlociTestConfiguration` chỉ dựng Floci một lần, và item của
	 * test trước sống sót sang test sau.
	 *
	 * Gom `toList()` trước rồi mới xoá, KHÔNG xoá ngay trong lúc duyệt: `scan()`
	 * của Enhanced Client là paginated và lazy, nên xoá giữa chừng là sửa đúng
	 * cái đang được duyệt.
	 */
	@BeforeEach
	void setUp() {
		table = enhancedClient.table(tableName, TableSchema.fromBean(Source.class));
		table.scan().items().stream().toList().forEach(table::deleteItem);
	}

	@Test
	void chi_tra_ve_nguon_dang_bat() {
		table.putItem(source("a", true));
		table.putItem(source("b", false));
		table.putItem(source("c", true));

		List<SourceView> enabled = catalog.enabledSources();

		assertThat(enabled).extracting(SourceView::sourceId)
				.containsExactlyInAnyOrder("a", "c");
	}

	/**
	 * `recordFetch` dùng UpdateItem, KHÔNG PutItem — nó chỉ được đụng ba trường
	 * trạng thái. Nếu nó ghi đè cả item thì `enabled` và `name` (do sourcesSync
	 * sở hữu) sẽ bị xoá, và triệu chứng là một nguồn đã tắt bỗng chạy lại.
	 */
	@Test
	void record_fetch_khong_dung_toi_cau_hinh() {
		table.putItem(source("a", true));

		catalog.recordFetch("a", "etag-123", "Mon, 04 Aug 2026 00:00:00 GMT",
				"2026-08-05T10:00:00Z");

		Source after = table.getItem(r -> r.key(k -> k.partitionValue("a")));
		assertThat(after.getEtag()).isEqualTo("etag-123");
		assertThat(after.getLastFetchedAt()).isEqualTo("2026-08-05T10:00:00Z");
		assertThat(after.getName()).isEqualTo("Tên của a");
		assertThat(after.isEnabled()).isTrue();
	}

	/**
	 * Lượt đầu tiên chưa có etag — `recordFetch` phải nhận null mà không chết,
	 * vì DynamoDB không cho set attribute bằng null trong UpdateExpression.
	 */
	@Test
	void record_fetch_chiu_duoc_etag_null() {
		table.putItem(source("a", true));

		catalog.recordFetch("a", null, null, "2026-08-05T10:00:00Z");

		Source after = table.getItem(r -> r.key(k -> k.partitionValue("a")));
		assertThat(after.getLastFetchedAt()).isEqualTo("2026-08-05T10:00:00Z");
	}

	/**
	 * Không chết là chưa đủ — validator cũ PHẢI biến mất khỏi item.
	 *
	 * Đây là nhánh có hậu quả im lặng nhất của cả module: giữ lại `etag` của
	 * lượt trước nghĩa là lượt sau vẫn gửi `If-None-Match` bằng một giá trị
	 * server đã thôi công nhận. Feed trả 200 kèm nội dung mới, nhưng ta thì
	 * đang so với validator sai — hoặc tệ hơn, server trả 304 vĩnh viễn và
	 * nguồn đó ĐỨNG YÊN mà mọi log vẫn xanh.
	 *
	 * Test `record_fetch_chiu_duoc_etag_null` ở trên KHÔNG bắt được: nó chỉ
	 * khẳng định `lastFetchedAt`, nên một bản cài đặt bỏ hẳn mệnh đề REMOVE
	 * vẫn làm nó xanh.
	 */
	@Test
	void record_fetch_xoa_validator_cu_khi_luot_moi_khong_co() {
		table.putItem(source("a", true));
		catalog.recordFetch("a", "etag-cu", "Mon, 04 Aug 2026 00:00:00 GMT",
				"2026-08-05T10:00:00Z");

		catalog.recordFetch("a", null, null, "2026-08-06T10:00:00Z");

		Source after = table.getItem(r -> r.key(k -> k.partitionValue("a")));
		assertThat(after.getEtag()).isNull();
		assertThat(after.getLastModified()).isNull();
		assertThat(after.getLastFetchedAt()).isEqualTo("2026-08-06T10:00:00Z");
	}

	/**
	 * Trường hợp lẫn lộn: có `ETag` nhưng không có `Last-Modified` — rất thường
	 * gặp, nhiều feed chỉ phát một trong hai.
	 *
	 * Đây là nhánh mà `UpdateExpression` phải vừa SET vừa REMOVE trong cùng một
	 * câu, và là chỗ duy nhất mệnh đề REMOVE được mở đầu bởi `lastModified` chứ
	 * không phải `etag`. Sai dấu phẩy ở đó là `ValidationException` lúc runtime,
	 * không phải lỗi compile.
	 */
	@Test
	void record_fetch_set_va_remove_trong_cung_mot_luot() {
		table.putItem(source("a", true));
		catalog.recordFetch("a", "etag-cu", "Mon, 04 Aug 2026 00:00:00 GMT",
				"2026-08-05T10:00:00Z");

		catalog.recordFetch("a", "etag-moi", null, "2026-08-06T10:00:00Z");

		Source after = table.getItem(r -> r.key(k -> k.partitionValue("a")));
		assertThat(after.getEtag()).isEqualTo("etag-moi");
		assertThat(after.getLastModified()).isNull();
	}

	private static Source source(String id, boolean enabled) {
		Source s = new Source();
		s.setSourceId(id);
		s.setName("Tên của " + id);
		s.setFeedUrl("https://example.test/" + id + ".xml");
		s.setEnabled(enabled);
		return s;
	}
}
