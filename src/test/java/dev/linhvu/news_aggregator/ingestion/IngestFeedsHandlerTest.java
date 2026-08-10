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
	void nhan_dung_job_cua_minh() {
		assertThat(handler.supports(Map.of("job", "ingest-feeds"))).isTrue();
	}

	/**
	 * `supports()` phải kiểm ĐỦ điều kiện của mình. Ba payload dưới đây là ba
	 * nguồn sự kiện thật của chương trình sau Phase 3 — nếu handler này nhận
	 * nhầm một trong số chúng thì nó thắng trong `EventsController.dispatch`
	 * (handler đầu tiên trả true) và nguồn kia im lặng không bao giờ chạy.
	 */
	@Test
	void khong_nhan_payload_cua_nguon_khac() {
		assertThat(handler.supports(Map.of("job", "summarize-sweep"))).isFalse();
		assertThat(handler.supports(Map.of("Records",
				List.of(Map.of("messageId", "m1"))))).isFalse();
		assertThat(handler.supports(Map.of())).isFalse();
	}

	@Test
	void tra_ve_ket_qua_cua_luot_chay() {
		given(runner.run()).willReturn(new IngestResult(42, 7, 0));

		assertThat(handler.handle(Map.of("job", "ingest-feeds")))
				.isEqualTo(new IngestResult(42, 7, 0));
	}
}
