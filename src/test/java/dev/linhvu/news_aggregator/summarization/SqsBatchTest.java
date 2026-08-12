package dev.linhvu.news_aggregator.summarization;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqsBatchTest {

	private static Map<String, Object> record(String messageId, String body) {
		return Map.of("messageId", messageId, "eventSource", "aws:sqs", "body", body);
	}

	@Test
	void doc_duoc_articleid_tu_body_json() {
		Map<String, Object> payload = Map.of("Records", List.of(
				record("m1", "{\"articleId\":\"a1\"}"),
				record("m2", "{\"articleId\":\"a2\"}")));

		assertThat(SqsBatch.parse(payload))
				.containsExactly(new SqsBatch.Message("m1", "a1", null),
						new SqsBatch.Message("m2", "a2", null));
	}

	/**
	 * Một message hỏng không được giết cả batch. Nó bị BỎ ở bước parse và
	 * KHÔNG xuất hiện trong danh sách — nghĩa là nó cũng không nằm trong
	 * `batchItemFailures`, nên SQS coi nó đã xử lý xong và xoá.
	 *
	 * Đó là chủ ý: một message không đọc được `articleId` sẽ KHÔNG BAO GIỜ đọc
	 * được, nên retry nó là đốt tiền để đổi lấy cùng một kết quả. Sweep vẫn là
	 * lưới an toàn cho article tương ứng, vì article đó vẫn thiếu `summary`.
	 */
	@Test
	void bo_qua_message_khong_doc_duoc() {
		Map<String, Object> payload = Map.of("Records", List.of(
				record("m1", "không phải json"),
				record("m2", "{\"khac\":\"field\"}"),
				record("m3", "{\"articleId\":\"a3\"}")));

		assertThat(SqsBatch.parse(payload))
				.containsExactly(new SqsBatch.Message("m3", "a3", null));
	}

	/**
	 * Message do bản code cũ tạo, và message gửi tay bằng `aws sqs send-message`
	 * khi kiểm thử, đều KHÔNG có `traceparent`. Consumer phải chịu được điều đó —
	 * bắt đầu một trace mới thay vì hỏng.
	 *
	 * Đây là mục quan trọng nhất của task này. Một consumer ném vì thiếu metadata
	 * quan sát là một hệ thống mà observability tự nó thành nguồn sự cố.
	 */
	@Test
	void message_khong_co_traceparent_van_parse_duoc() {
		Map<String, Object> payload = Map.of("Records", List.of(
				record("m1", "{\"articleId\":\"abc\"}")));

		assertThat(SqsBatch.parse(payload)).singleElement().satisfies(m -> {
			assertThat(m.articleId()).isEqualTo("abc");
			assertThat(m.traceparent()).isNull();
		});
	}

	/**
	 * `traceparent` đi bằng MESSAGE ATTRIBUTE, không nằm trong body. Phase 3
	 * §17 #12 chốt body chỉ chứa `articleId` vì *"id là thứ duy nhất không bao giờ
	 * cũ"*; thêm trường vào body là mở lại quyết định đó cho một nhu cầu không
	 * thuộc nghiệp vụ.
	 */
	@Test
	void doc_duoc_traceparent_tu_message_attribute() {
		Map<String, Object> payload = Map.of("Records", List.of(Map.of(
				"messageId", "m1",
				"eventSource", "aws:sqs",
				"body", "{\"articleId\":\"abc\"}",
				"messageAttributes", Map.of("traceparent", Map.of(
						"stringValue", "00-0af7651916cd43dd8448eb211c80319c"
								+ "-b7ad6b7169203331-01",
						"dataType", "String")))));

		assertThat(SqsBatch.parse(payload)).singleElement()
				.extracting(SqsBatch.Message::traceparent)
				.isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
	}

	/**
	 * Hình dạng response là HỢP ĐỒNG với Lambda, không phải lựa chọn.
	 * `reportBatchItemFailures` chỉ hiểu đúng key `batchItemFailures` và
	 * `itemIdentifier`; sai một chữ thì Lambda BỎ QUA IM LẶNG và coi cả batch
	 * là thành công — message hỏng biến mất không dấu vết.
	 */
	@Test
	void dung_hinh_dang_batch_item_failures() {
		assertThat(SqsBatch.failures(List.of("m1", "m3")))
				.isEqualTo(Map.of("batchItemFailures", List.of(
						Map.of("itemIdentifier", "m1"),
						Map.of("itemIdentifier", "m3"))));
	}

	@Test
	void khong_hong_thi_danh_sach_rong() {
		assertThat(SqsBatch.failures(List.of()))
				.isEqualTo(Map.of("batchItemFailures", List.of()));
	}
}
