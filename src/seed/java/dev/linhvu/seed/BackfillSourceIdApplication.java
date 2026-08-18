package dev.linhvu.seed;

import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Gắn `sourceId` cho những article được ghi TRƯỚC Phase 7 — điều kiện tiên
 * quyết của slice 4, không phải việc dọn dẹp.
 *
 * `gsi-by-source` là SPARSE INDEX: item không có attribute `sourceId` thì không
 * nằm trong index. Bài của Phase 1–3 vì thế biến mất khỏi feed ĐÃ LỌC trong khi
 * vẫn còn nguyên ở feed công khai — không lỗi, không log, chỉ là vài chục bài
 * không bao giờ hiện ra. Mục nghiệm thu là con số `attribute_not_exists(sourceId)`
 * bằng 0 ở CẢ BA môi trường (TDD §16).
 *
 * Chạy bằng credential của NGƯỜI VẬN HÀNH, đúng khuôn {@link SourcesSyncApplication}:
 * execution role của Lambda cố ý không có `Scan` trên `articles` (bảng tăng vô
 * hạn, `Scan` tính tiền theo kích thước BẢNG chứ không theo số item trả về).
 *
 * IDEMPOTENT nhờ điều kiện `attribute_not_exists(sourceId)` trên UpdateItem:
 * chạy lại bao nhiêu lần cũng ra cùng kết quả, và một lượt bị ngắt giữa chừng
 * thì chạy lại là đủ. Điều kiện đó cũng là thứ giữ cho lượt chạy MUỘN không ghi
 * đè giá trị mà ingestion đã gắn cho bài mới.
 */
public final class BackfillSourceIdApplication {

	private BackfillSourceIdApplication() {
	}

	public static void main(String[] args) {
		String articlesTable = System.getenv()
				.getOrDefault("NEWS_ARTICLES_TABLE", "articles");
		String sourcesTable = System.getenv()
				.getOrDefault("NEWS_SOURCES_TABLE", "sources");

		try (DynamoDbClient client = DynamoDbClient.create()) {
			Map<String, String> idTheoTen = readSourceIdsByName(client, sourcesTable);
			System.out.printf("bảng %s có %d nguồn%n", sourcesTable, idTheoTen.size());

			int daGan = 0;
			int moCoi = 0;
			int daCoSan = 0;

			// `scanPaginator` chứ không `scan` một phát: Scan trả về nhiều nhất 1 MB
			// mỗi trang, nên bản không phân trang sẽ backfill đúng trang đầu rồi báo
			// thành công — và số item còn thiếu chỉ lộ ra ở bước kiểm §16.
			//
			// FilterExpression áp SAU khi đọc, nên nó không giảm tiền; nó ở đây để
			// giảm số UpdateItem vô ích, và để dòng tổng kết nói đúng con số.
			var trang = client.scanPaginator(ScanRequest.builder()
					.tableName(articlesTable)
					.filterExpression("attribute_not_exists(sourceId)")
					.projectionExpression("articleId, sourceName")
					.build());

			for (var page : trang) {
				for (Map<String, AttributeValue> item : page.items()) {
					String articleId = item.get("articleId").s();
					AttributeValue ten = item.get("sourceName");
					String sourceId = ten == null ? null : idTheoTen.get(ten.s());

					// Bài MỒ CÔI: nguồn đã bị xoá khỏi bảng `sources`, hoặc đổi tên.
					// Đoán một `sourceId` cho nó là bịa dữ liệu — bỏ qua, và con số
					// dưới là lời giải thích cho một lượt kiểm §16 không ra 0.
					if (sourceId == null) {
						System.out.printf("WARN bài mồ côi %s — sourceName=%s%n",
								articleId, ten == null ? "<thiếu>" : ten.s());
						moCoi++;
						continue;
					}

					if (gan(client, articlesTable, articleId, sourceId)) {
						daGan++;
					}
					else {
						daCoSan++;
					}
				}
			}

			System.out.printf("backfill xong: đã gắn=%d mồ côi=%d đã có sẵn=%d%n",
					daGan, moCoi, daCoSan);
			if (moCoi > 0) {
				System.out.printf("⚠️ %d bài KHÔNG tra được nguồn — chúng sẽ vắng mặt "
						+ "khỏi feed đã lọc, và lệnh kiểm sẽ đếm ra đúng %d%n",
						moCoi, moCoi);
			}
		}
	}

	/**
	 * Khoá tra là `name`, vì đó là thứ DUY NHẤT article cũ mang theo. Đây cũng là
	 * lý do backfill phải chạy TRƯỚC khi ai đó đổi `name` trong `sources.yaml`:
	 * đổi tên xong thì bài cũ thành mồ côi vĩnh viễn.
	 */
	private static Map<String, String> readSourceIdsByName(DynamoDbClient client,
			String sourcesTable) {
		Map<String, String> idTheoTen = new HashMap<>();
		var trang = client.scanPaginator(ScanRequest.builder()
				.tableName(sourcesTable)
				// `name` là reserved word của DynamoDB — dùng thẳng trong
				// ProjectionExpression sẽ ném ValidationException.
				.projectionExpression("sourceId, #n")
				.expressionAttributeNames(Map.of("#n", "name"))
				.build());

		for (var page : trang) {
			for (Map<String, AttributeValue> item : page.items()) {
				idTheoTen.put(item.get("name").s(), item.get("sourceId").s());
			}
		}
		if (idTheoTen.isEmpty()) {
			throw new IllegalStateException("bảng " + sourcesTable
					+ " rỗng — đã chạy ./gradlew sourcesSync chưa?");
		}
		return idTheoTen;
	}

	/**
	 * `UpdateItem` chứ KHÔNG `PutItem`, cùng lý do với `SourcesSyncApplication`:
	 * PutItem ghi đè cả item, tức xoá `title`/`canonicalUrl`/`excerpt`/`summary`
	 * — mọi thứ ingestion và summarization đã ghi.
	 *
	 * @return true nếu lượt này thật sự gắn; false nếu item đã có `sourceId`
	 */
	private static boolean gan(DynamoDbClient client, String articlesTable,
			String articleId, String sourceId) {
		try {
			client.updateItem(UpdateItemRequest.builder()
					.tableName(articlesTable)
					.key(Map.of("articleId", AttributeValue.fromS(articleId)))
					.updateExpression("SET sourceId = :s")
					.conditionExpression("attribute_not_exists(sourceId)")
					.expressionAttributeValues(
							Map.of(":s", AttributeValue.fromS(sourceId)))
					.build());
			return true;
		}
		catch (ConditionalCheckFailedException ex) {
			// Luồng BÌNH THƯỜNG, không phải lỗi: ingestion vừa ghi bài này kèm
			// `sourceId`, hoặc một lượt backfill trước đã gắn rồi.
			return false;
		}
	}
}
