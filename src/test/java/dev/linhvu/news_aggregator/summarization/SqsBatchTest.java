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
				.containsExactly(new SqsBatch.Message("m1", "a1"),
						new SqsBatch.Message("m2", "a2"));
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
				.containsExactly(new SqsBatch.Message("m3", "a3"));
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
