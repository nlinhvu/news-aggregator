package dev.linhvu.news_aggregator.summarization;

import java.util.List;
import java.util.stream.IntStream;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.catalog.events.ArticleAdded;
import dev.linhvu.news_aggregator.platform.NewsFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.togglz.core.context.FeatureContext;
import org.togglz.core.manager.FeatureManager;
import org.togglz.junit5.AllEnabled;
import org.togglz.testing.TestFeatureManagerProvider;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(FlociTestConfiguration.class)
class SummarizationQueueTest {

	@Autowired
	SummarizationQueue queue;

	// `SqsAsyncClient`, KHÔNG phải `SqsClient` — spring-cloud-aws 4.1.0 không có
	// bean sync nào. Xem Task 3 Step 6.
	@Autowired
	SqsAsyncClient sqs;

	/**
	 * FeatureManager THẬT do `TogglzAutoConfiguration` dựng. Phải giữ tham chiếu
	 * vì trong test nó KHÔNG tự đến tay `FeatureContext` — xem
	 * {@link #dungFeatureManagerThat()}.
	 */
	@Autowired
	FeatureManager featureManager;

	@Autowired
	SummarizationRunMetrics metrics;

	@Autowired
	ApplicationEventPublisher publisher;

	@Value("${news.summarization.queue-url}")
	String queueUrl;

	@BeforeEach
	void purge() {
		sqs.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl).build()).join();
		// Bean singleton sống qua nhiều test y hệt cách nó sống qua nhiều lượt
		// invoke Lambda. Không reset thì hạn mức của test này đã bị test chạy
		// trước ăn mất, và `chan_dung_han_muc_moi_luot` ra 24 thay vì 25.
		metrics.reset();
	}

	/**
	 * `FeatureContext` cache FeatureManager trong một field static theo context
	 * class loader, và Gradle chạy mọi test class trong cùng một JVM. Không dọn
	 * thì manager rò qua lại giữa các test class — kèm cả Spring context đã đóng.
	 */
	@AfterEach
	void traLaiFeatureManagerVeNguyenTrang() {
		TestFeatureManagerProvider.setFeatureManager(null);
		FeatureContext.clearCache();
	}

	/**
	 * Ép `FeatureContext` trả về FeatureManager của Spring.
	 *
	 * Trong test mà KHÔNG làm gì thì flag không phải "mặc định OFF" mà là
	 * "BẬT HẾT": `togglz-testing` (kéo vào theo `togglz-junit`, không tách được
	 * vì `@AllEnabled` nằm cùng jar) đăng ký `FallbackTestFeatureManagerProvider`
	 * và `FallbackTestFeatureManager.isActive` trả TRUE cho mọi feature. Không
	 * gọi hàm này thì `khong_gui_gi_khi_flag_tat` đỏ vì thư viện test, không phải
	 * vì code sai. Lập luận đầy đủ ở `TogglzGateTest#dungFeatureManagerThat`.
	 *
	 * Gọi trong THÂN test chứ không phải `@BeforeEach`: callback của
	 * `@AllEnabled` chạy TRƯỚC `@BeforeEach`, nên đặt ở đó sẽ đè mất
	 * TestFeatureManager và giết luôn hai test kia.
	 */
	private void dungFeatureManagerThat() {
		TestFeatureManagerProvider.setFeatureManager(featureManager);
		FeatureContext.clearCache();
	}

	private int soMessageTrongQueue() {
		return sqs.receiveMessage(ReceiveMessageRequest.builder()
				.queueUrl(queueUrl).maxNumberOfMessages(10)
				.waitTimeSeconds(1).build()).join().messages().size();
	}

	@Test
	@AllEnabled(NewsFeature.class)
	void gui_message_khi_flag_bat() {
		assertThat(queue.enqueue("a1")).isTrue();
		assertThat(soMessageTrongQueue()).isEqualTo(1);
	}

	/**
	 * PRODUCER #1 — đường tươi — phải THẬT SỰ nối `ArticleAdded` vào queue.
	 *
	 * `ArticleAddedListener` chỉ có đúng một dòng thân hàm, và không test nào
	 * khác nhìn tới nó: xoá dòng đó thì cả suite vẫn xanh, còn hậu quả là toàn
	 * bộ đường tươi biến mất và mọi bài phải chờ lượt sweep. Không có gì đỏ, chỉ
	 * là tóm tắt tới chậm vài tiếng.
	 *
	 * Đây cũng là cạnh `summarization → catalog :: events` của cycle ADR-0012.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void duong_tuoi_day_message_khi_co_article_moi() {
		publisher.publishEvent(new ArticleAdded("a-moi", "Nguồn Test",
				"https://a.test/moi", "Tiêu đề", "2026-08-10T10:00:00Z"));

		assertThat(soMessageTrongQueue()).isEqualTo(1);
	}

	/**
	 * TẦNG CHẶN TIẾT KIỆM TIỀN.
	 *
	 * `ArticleController` đã chặn ở tầng hiển thị từ Phase 1, nhưng tầng đó chỉ
	 * giấu dữ liệu — tiền vẫn bị tiêu. Chặn ở đây nghĩa là flag OFF thì KHÔNG
	 * message nào được tạo, nên không lời gọi model nào xảy ra.
	 *
	 * Và nhờ ADR-0014, tắt flag KHÔNG mất bài: sweep sẽ nhặt lại khi bật.
	 */
	@Test
	void khong_gui_gi_khi_flag_tat() {
		dungFeatureManagerThat();

		assertThat(queue.enqueue("a1")).isFalse();
		assertThat(soMessageTrongQueue()).isZero();
	}

	/**
	 * Hạn mức mỗi lượt là thứ master §7 yêu cầu, và nó sống ở ĐÚNG MỘT chỗ —
	 * cổng này — nên cả hai producer (đường tươi và sweep) đều bị nó áp.
	 *
	 * Phần dư KHÔNG bị bỏ im lặng: nó vẫn thiếu `summary` nên lượt sweep sau
	 * nhặt lại. Đó là khác biệt then chốt với phương án push thuần.
	 */
	@Test
	@AllEnabled(NewsFeature.class)
	void chan_dung_han_muc_moi_luot() {
		List<String> ids = IntStream.range(0, 40).mapToObj(i -> "a" + i).toList();

		int sent = queue.enqueueAll(ids);

		assertThat(sent).isEqualTo(25);
	}
}
