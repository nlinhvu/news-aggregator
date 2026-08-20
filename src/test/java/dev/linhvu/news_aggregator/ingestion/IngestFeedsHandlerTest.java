package dev.linhvu.news_aggregator.ingestion;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

class IngestFeedsHandlerTest {

	private final IngestionRunner runner = Mockito.mock(IngestionRunner.class);

	private final IngestFeedsHandler handler = new IngestFeedsHandler(runner);

	@Test
	void accepts_only_its_own_job() {
		assertThat(handler.supports(Map.of("job", "ingest-feeds"))).isTrue();
	}

	/**
	 * `supports()` phải kiểm ĐỦ điều kiện của mình. Ba payload dưới đây là ba
	 * nguồn sự kiện thật của chương trình sau Phase 3 — nếu handler này nhận
	 * nhầm một trong số chúng thì nó thắng trong `EventsController.dispatch`
	 * (handler đầu tiên trả true) và nguồn kia im lặng không bao giờ chạy.
	 */
	@Test
	void does_not_accept_a_payload_from_another_producer() {
		assertThat(handler.supports(Map.of("job", "summarize-sweep"))).isFalse();
		assertThat(handler.supports(Map.of("Records",
				List.of(Map.of("messageId", "m1"))))).isFalse();
		assertThat(handler.supports(Map.of())).isFalse();
	}

	@Test
	void returns_the_result_of_the_run() {
		given(runner.run()).willReturn(new IngestResult(42, 7, 0));

		assertThat(handler.handle(Map.of("job", "ingest-feeds")))
				.isEqualTo(new IngestResult(42, 7, 0));
	}
}
