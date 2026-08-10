package dev.linhvu.news_aggregator.summarization;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizeHandlerTest {

	private final SummarizeHandler handler = new SummarizeHandler();

	private static Map<String, Object> sqsPayload(String... messageIds) {
		return Map.of("Records", Arrays.stream(messageIds)
				.map(id -> Map.<String, Object>of("messageId", id,
						"eventSource", "aws:sqs",
						"body", "{\"articleId\":\"" + id + "-article\"}"))
				.toList());
	}

	@Test
	void nhan_payload_sqs() {
		assertThat(handler.supports(sqsPayload("m1"))).isTrue();
	}

	/**
	 * `Records` một mình KHÔNG đủ. S3, DynamoDB Streams và Kinesis đều gửi
	 * payload có key `Records`; nếu một phase sau cắm một trong số chúng vào
	 * thì handler này sẽ nuốt sự kiện của nó và im lặng coi là xong.
	 */
	@Test
	void khong_nhan_records_cua_nguon_khac() {
		assertThat(handler.supports(Map.of("Records", List.of(
				Map.of("eventSource", "aws:s3"))))).isFalse();
		assertThat(handler.supports(Map.of("job", "ingest-feeds"))).isFalse();
		assertThat(handler.supports(Map.of("Records", List.of()))).isFalse();
		assertThat(handler.supports(Map.of())).isFalse();
	}

	/**
	 * SLICE 1: chưa gọi model. Handler chỉ đếm và ACK toàn bộ — thứ đang được
	 * kiểm là ĐƯỜNG ỐNG, không phải logic. Task 10 thay thân method này.
	 */
	@Test
	void slice1_ack_toan_bo_va_khong_bao_hong() {
		assertThat(handler.handle(sqsPayload("m1", "m2")))
				.isEqualTo(Map.of("batchItemFailures", List.of()));
	}
}
