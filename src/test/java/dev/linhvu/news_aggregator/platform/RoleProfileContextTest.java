package dev.linhvu.news_aggregator.platform;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bốn context, bốn profile. Không có test này thì một bean thiếu wiring ở profile
 * `ingest` sẽ xanh suốt CI và chỉ chết ở lần chạy theo lịch đầu tiên trên môi
 * trường thật — cùng chế độ hỏng với bean `@Lazy` của Phase 3, chỉ khác hình
 * dạng: ở đó bean tồn tại mà không ai dựng, ở đây bean không tồn tại mà không
 * ai hỏi.
 *
 * Mỗi test khẳng định HAI vế: context dựng được với đúng bean của vai nó, VÀ
 * bean của vai khác KHÔNG có mặt. Vế thứ hai mới là vế chứng minh việc tách có
 * tác dụng — vế thứ nhất một mình sẽ xanh y hệt kể cả khi không `@Profile` nào
 * được gắn.
 *
 * <p><b>Vì sao tra theo TÊN bean chứ không theo type.</b> `ArticleController`,
 * `IngestFeedsHandler`, `SummarizeHandler`, `SweepHandler` và
 * `SummarizationQueue` đều **package-private** — kỷ luật Spring Modulith, và
 * `ModuleBoundaryTest` canh đúng điều đó. Nới chúng thành `public` chỉ để test
 * này nhìn thấy là đánh đổi một ranh giới thật lấy một tiện lợi của test.
 * `containsBeanDefinition` cũng hợp câu hỏi hơn: ta hỏi *"profile này có ĐỊNH
 * NGHĨA bean đó không"*, không phải *"dựng được nó không"* — nên nó không chạm
 * tới bean `@Lazy` nào, kể cả `chatClient` (thứ sẽ đi gọi SSM nếu bị dựng).
 *
 * <p>Cái giá của việc tra theo tên: một tên gõ sai làm vế PHỦ ĐỊNH xanh một
 * cách rỗng. Chốt chặn là **mọi tên bị khẳng định VẮNG ở một profile đều được
 * khẳng định CÓ ở một profile khác** — bảng dưới. Gõ sai tên nào thì vế khẳng
 * định của tên đó đỏ.
 *
 * <pre>
 *   bean                          web    admin   ingest   summarize
 *   articleController             CÓ     VẮNG    -        -
 *   sourceController              CÓ     VẮNG    VẮNG     -
 *   myFeedController              CÓ     VẮNG    VẮNG     -
 *   preferencesController         CÓ     VẮNG    -        -
 *   eventsController              VẮNG   VẮNG    CÓ       CÓ
 *   chatClient                    VẮNG   -       -        CÓ
 *   ingestFeedsHandler            -      VẮNG    CÓ       VẮNG
 *   sweepHandler                  -      -       VẮNG     CÓ
 *   summarizeHandler              -      -       -        CÓ
 *   summarizationQueue            -      -       CÓ       -
 *   filterChain                   CÓ     CÓ      -        -
 *   ssmClientRegistrationRepository  CÓ  CÓ      -        -
 * </pre>
 *
 * `@ActiveProfiles` chứ không `properties = "spring.profiles.active=…"`: cái
 * sau không kích hoạt profile trong `@SpringBootTest`, và test sẽ xanh rỗng.
 *
 * KHÔNG có profile `test` — repo này không có `application-test.yaml` và không
 * test nào khác dùng `@ActiveProfiles`. Thêm nó vào chỉ tạo một profile không
 * nạp gì, và một chỗ nữa để tưởng là có cấu hình.
 */
class RoleProfileContextTest {

	@SpringBootTest
	@ActiveProfiles(RoleProfiles.WEB)
	@Import(FlociTestConfiguration.class)
	static class WebProfile {

		@Autowired
		ApplicationContext ctx;

		@Test
		void web_co_duong_doc_va_khong_co_duong_nao_toi_model() {
			assertThat(ctx.containsBeanDefinition("articleController"))
					.as("`web` phục vụ GET /api/articles")
					.isTrue();

			assertThat(ctx.containsBeanDefinition("sourceController"))
					.as("`web` phục vụ GET /api/sources — hàng chip của slice 4")
					.isTrue();

			assertThat(ctx.containsBeanDefinition("myFeedController"))
					.as("`web` phục vụ GET /api/my/feed")
					.isTrue();
			assertThat(ctx.containsBeanDefinition("preferencesController"))
					.as("`web` phục vụ GET/PUT /api/preferences/sources")
					.isTrue();

			assertThat(ctx.containsBeanDefinition("chatClient"))
					.as("`web` không được có ĐỊNH NGHĨA ChatClient — nó không có quyền "
							+ "đọc gemini key, nên một lời gọi nhầm phải chết lúc tra "
							+ "bean chứ không lúc gọi SSM")
					.isFalse();

			assertThat(ctx.containsBeanDefinition("eventsController"))
					.as("`web` không có AWS_LWA_PASS_THROUGH_PATH nên `/events` ở đó là "
							+ "một đường vào không ai dùng")
					.isFalse();
		}
	}

	/**
	 * Profile của function thứ tư (ADR-0020), và test này là chốt chặn DUY NHẤT
	 * cho câu hỏi *"context của `admin` có dựng được không"* trước khi nó lên
	 * môi trường thật.
	 *
	 * `admin` là vai kỳ lạ nhất trong bốn vai: nó không có endpoint nào của
	 * riêng mình cho tới khi Togglz console được bật, nhưng nó dựng TOÀN BỘ
	 * chain đăng nhập vì `SecurityConfig` là `@Profile(HTTP)`. Nghĩa là một lỗi
	 * wiring ở đó chết trên `admin` y hệt như trên `web` — chỉ khác là không
	 * smoke test nào chạm tới `admin` được (nó nằm sau nhóm `ops`), nên nếu test
	 * này không dựng context thì KHÔNG GÌ dựng.
	 *
	 * Vế phủ định là vế trả lời câu hỏi "vì sao không cứ dùng `web`": mặt phẳng
	 * vận hành không được phục vụ một byte nội dung nào. Nếu `ArticleController`
	 * lỡ mang `@Profile(HTTP)`, `/api/articles` sẽ sống trên CẢ `admin` — và
	 * không có triệu chứng nào, vì CloudFront không route `/api/*` tới đó.
	 */
	@SpringBootTest
	@ActiveProfiles(RoleProfiles.ADMIN)
	@Import(FlociTestConfiguration.class)
	static class AdminProfile {

		@Autowired
		ApplicationContext ctx;

		@Test
		void admin_co_chain_dang_nhap_va_khong_phuc_vu_noi_dung_nao() {
			assertThat(ctx.containsBeanDefinition("filterChain"))
					.as("`SecurityConfig` là @Profile(HTTP) nên `admin` cũng dựng chain "
							+ "— đó là thứ chặn `/admin/**` sau `ROLE_ops`")
					.isTrue();
			assertThat(ctx.containsBeanDefinition("ssmClientRegistrationRepository"))
					.as("chain của `admin` phải dựng được ClientRegistration — đó là lý "
							+ "do role của nó có ssm:GetParameter + kms:Decrypt")
					.isTrue();

			for (String beanPhucVuNoiDung : java.util.List.of("articleController",
					"sourceController", "myFeedController", "preferencesController")) {
				assertThat(ctx.containsBeanDefinition(beanPhucVuNoiDung))
						.as("mặt phẳng vận hành KHÔNG phục vụ nội dung: " + beanPhucVuNoiDung)
						.isFalse();
			}

			assertThat(ctx.containsBeanDefinition("eventsController"))
					.as("`admin` không có AWS_LWA_PASS_THROUGH_PATH — `/events` ở đó là "
							+ "một đường vào không ai dùng")
					.isFalse();
			assertThat(ctx.containsBeanDefinition("ingestFeedsHandler"))
					.as("`admin` không chạy việc nền nào")
					.isFalse();
		}
	}

	@SpringBootTest
	@ActiveProfiles(RoleProfiles.INGEST)
	@Import(FlociTestConfiguration.class)
	static class IngestProfile {

		@Autowired
		ApplicationContext ctx;

		@Test
		void ingest_co_handler_va_co_duong_day_hang_doi() {
			assertThat(ctx.containsBeanDefinition("eventsController")).isTrue();
			assertThat(ctx.containsBeanDefinition("ingestFeedsHandler")).isTrue();

			// `ingest` ĐỌC bảng `sources` (AP5) nhưng không phục vụ HTTP cho ai —
			// controller ở đây là một đường vào không ai gọi, trên một function
			// không có Function URL.
			assertThat(ctx.containsBeanDefinition("sourceController"))
					.as("`ingest` không phục vụ endpoint đọc nào")
					.isFalse();
			assertThat(ctx.containsBeanDefinition("myFeedController"))
					.as("`ingest` không có bề mặt người dùng nào")
					.isFalse();

			// Vế QUAN TRỌNG NHẤT của test này: `ArticleAdded` phát trong lượt
			// ingest, nên đường đẩy vào SQS phải sống Ở ĐÂY. Gắn
			// `@Profile(SUMMARIZE)` lên `SummarizationQueue` sẽ làm bài mới không
			// bao giờ được đẩy vào hàng đợi, và triệu chứng là sự im lặng. Đây
			// cũng là lý do role của `ingest` có `sqs:SendMessage`.
			assertThat(ctx.containsBeanDefinition("summarizationQueue"))
					.as("`ArticleAdded` phát trong lượt ingest — đường đẩy SQS phải "
							+ "sống ở đây, không ở `summarize`")
					.isTrue();

			assertThat(ctx.containsBeanDefinition("sweepHandler"))
					.as("sweep chạy trên `summarize`, không trên `ingest` — ADR-0020 "
							+ "cắt theo ranh giới nghiệp vụ, không theo nguồn kích hoạt")
					.isFalse();
		}
	}

	@SpringBootTest
	@ActiveProfiles(RoleProfiles.SUMMARIZE)
	@Import(FlociTestConfiguration.class)
	static class SummarizeProfile {

		@Autowired
		ApplicationContext ctx;

		@Test
		void summarize_co_ca_handler_sqs_lan_sweep() {
			assertThat(ctx.containsBeanDefinition("summarizeHandler")).isTrue();
			assertThat(ctx.containsBeanDefinition("sweepHandler")).isTrue();
			// Vế khẳng định cho `chatClient`, thứ chứng minh cái tên trong vế phủ
			// định của `WebProfile` là tên THẬT chứ không phải một chuỗi gõ sai.
			assertThat(ctx.containsBeanDefinition("chatClient")).isTrue();

			assertThat(ctx.containsBeanDefinition("ingestFeedsHandler")).isFalse();
		}
	}
}
