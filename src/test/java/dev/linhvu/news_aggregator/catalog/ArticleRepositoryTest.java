package dev.linhvu.news_aggregator.catalog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.ArticleFixtures;
import dev.linhvu.news_aggregator.platform.TracePropagation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(FlociTestConfiguration.class)
class ArticleRepositoryTest {

	@Autowired
	ArticleRepository repository;

	@Autowired
	DynamoDbEnhancedClient enhancedClient;

	@Value("${news.catalog.table-name}")
	String tableName;

	/**
	 * Nạp fixture qua `ArticleFixtures` chứ không dựng `Article` tại chỗ, để
	 * fixture hỏng làm đỏ test ngay tại máy. Bảng do `FlociTestConfiguration`
	 * tạo, xem lý do ở đó.
	 *
	 * Phase 1 thì đây còn là fixture DÙNG CHUNG với `SeedApplication`, nên nó
	 * cũng bảo đảm dữ liệu test khớp dữ liệu đã seed ở dev/qa/prod. Task 7 xoá
	 * `SeedApplication` nên vế đó hết hiệu lực — bảng `articles` từ nay do
	 * ingestion thật nạp.
	 *
	 * Chèn theo `publishedAt` TĂNG DẦN, tức NGƯỢC hẳn thứ tự mong đợi ở output.
	 * Chèn theo đúng thứ tự trong file thì test vẫn xanh kể cả khi query trả về
	 * theo thứ tự chèn — mất sạch khả năng bắt lỗi sắp xếp, mà sắp xếp lại đúng
	 * là thứ `gsi-recent` sinh ra để làm.
	 */
	@BeforeEach
	void loadFixtures() {
		ArticleFixtures.load().stream()
				.sorted(Comparator.comparing(Article::getPublishedAt))
				.forEach(repository::save);
	}

	/**
	 * Thứ tự mong đợi được SUY RA từ fixture chứ không viết cứng, nên thêm bớt
	 * article trong fixture không làm test này gãy một cách vô cớ.
	 *
	 * Sắp xếp bằng Java ở đây là một oracle độc lập thật: nó không dùng chung
	 * cơ chế nào với việc DynamoDB trả item theo range key của GSI.
	 */
	@Test
	void returns_articles_newest_first() {
		List<String> expected = ArticleFixtures.load().stream()
				.sorted(Comparator.comparing(Article::getPublishedAt).reversed())
				.map(Article::getArticleId)
				.toList();

		assertThat(repository.findRecent(10, null))
				.extracting(Article::getArticleId)
				.containsExactlyElementsOf(expected);
	}

	@Test
	void respects_limit() {
		assertThat(repository.findRecent(2, null)).hasSize(2);
	}

	/**
	 * AP11. Fixture cố ý xen kẽ hai nguồn theo thời gian (`spring-blog` 07-28 và
	 * 07-22, `aws-news` 07-26 và 07-20), nên một bản dựng nối kết quả từng nguồn
	 * lại mà QUÊN merge-sort sẽ trả về đúng bốn phần tử ấy theo SAI thứ tự —
	 * `containsExactly` là thứ phân biệt hai bản dựng đó.
	 *
	 * Thứ tự mong đợi suy ra từ fixture, không viết cứng, đúng lối
	 * `returns_articles_newest_first`.
	 */
	@Test
	void fan_out_returns_exactly_the_selected_sources_newest_first() {
		assertThat(repository.findRecentBySources(SELECTED, 20))
				.extracting(Article::getArticleId)
				.containsExactlyElementsOf(expectedBySources(SELECTED));
	}

	/**
	 * Rỗng = TẤT CẢ nguồn (TDD §17 #10). Hiểu ngược lại — "không nguồn nào" —
	 * biến trạng thái bình thường nhất của một người dùng mới (chưa chọn gì)
	 * thành một trang trống.
	 *
	 * So CẢ THỨ TỰ chứ không chỉ tập phần tử: nhánh rỗng phải uỷ quyền thẳng cho
	 * `findRecent`, tức đi qua `gsi-recent-v2` chứ không fan-out qua mọi nguồn.
	 * Một bản dựng "rỗng ⇒ fan-out tất cả nguồn" sẽ ĐÁNH RƠI bài chưa backfill —
	 * xem test dưới.
	 */
	@Test
	void an_empty_set_means_all_sources() {
		List<String> allIds = repository.findRecent(20, null).stream()
				.map(Article::getArticleId)
				.toList();

		assertThat(repository.findRecentBySources(List.of(), 20))
				.extracting(Article::getArticleId)
				.containsExactlyElementsOf(allIds);
	}

	/**
	 * `gsi-by-source` là SPARSE INDEX: item không có attribute `sourceId` thì
	 * không nằm trong index, nên bài của Phase 1–3 biến mất khỏi feed ĐÃ LỌC
	 * trong khi vẫn còn nguyên ở feed công khai.
	 *
	 * Đây là lý do Task 21 (backfill) là ĐIỀU KIỆN TIÊN QUYẾT của slice chứ
	 * không phải việc dọn dẹp làm sau cũng được — và nó là kiểu hỏng im lặng:
	 * không lỗi, không log, chỉ là vài bài không bao giờ hiện ra.
	 *
	 * Vế `isNotEmpty` đứng đầu vì hai vế dưới đều xanh RỖNG nếu ai đó thêm
	 * `sourceId` cho mọi bài trong fixture.
	 */
	@Test
	void an_article_without_a_backfilled_sourceId_disappears_from_the_filtered_feed() {
		List<String> orphan = ArticleFixtures.load().stream()
				.filter(a -> a.getSourceId() == null)
				.map(Article::getArticleId)
				.toList();
		assertThat(orphan)
				.as("fixture phải giữ ít nhất một bài chưa backfill")
				.isNotEmpty();

		assertThat(repository.findRecentBySources(SELECTED, 20))
				.extracting(Article::getArticleId)
				.doesNotContainAnyElementsOf(orphan);
		assertThat(repository.findRecent(20, null))
				.extracting(Article::getArticleId)
				.containsAll(orphan);
	}

	/**
	 * `limit` áp lên kết quả ĐÃ GỘP, không phải lên từng query.
	 *
	 * Bản dựng quên vế này vẫn xanh ở mọi test trên: nó trả `limit` bài MỖI
	 * NGUỒN, tức 2 nguồn × 20 = 40 bài cho một request xin 20. Triệu chứng không
	 * phải lỗi mà là một trang dài gấp đôi.
	 */
	@Test
	void limit_applies_to_the_merged_result_not_to_each_source() {
		assertThat(repository.findRecentBySources(SELECTED, 2))
				.extracting(Article::getArticleId)
				.containsExactlyElementsOf(expectedBySources(SELECTED).subList(0, 2));
	}

	/**
	 * MỘT query hỏng ⇒ CẢ lời gọi hỏng. Trả kết quả một phần khiến người dùng
	 * tưởng nguồn đó không có bài mới — sai lệch im lặng, tệ hơn hẳn một lỗi
	 * nhìn thấy được (TDD §11).
	 *
	 * Ép hỏng bằng một `sourceId` null — SDK từ chối nó khi dựng `Key`. KHÔNG
	 * dùng chuỗi rỗng: DynamoDB thật từ chối giá trị rỗng cho attribute khoá
	 * nhưng Floci thì nhận (đã đo), nên một test dựa vào đó chỉ chứng minh hành
	 * vi của emulator. Cũng KHÔNG mock: đường chạy hỏng phải đi qua đúng chỗ
	 * `ExecutionException` được bóc ra khỏi `Future`.
	 */
	@Test
	void one_broken_query_fails_the_whole_call_without_partial_results() {
		// `Arrays.asList` chứ không `List.of` — `List.of` từ chối phần tử null.
		assertThatThrownBy(() -> repository.findRecentBySources(
				Arrays.asList("spring-blog", null), 20))
				.isInstanceOf(RuntimeException.class);
	}

	/**
	 * Fan-out chạy trên executor ĐÃ bắc trace context, không phải một
	 * `newVirtualThreadPerTaskExecutor()` trần.
	 *
	 * Executor tự viết nằm ngoài tầm `ContextPropagatingTaskDecorator` mà Boot
	 * gắn cho `TaskExecutor` do Spring quản (xem `TracePropagation`), nên bỏ
	 * `wrap` ra thì span của các query fan-out mất cha: `/api/my/feed` hiện
	 * trong X-Ray như một request không có việc gì bên trong, và mọi dòng log
	 * sinh trong đó mất `trace_id`. KHÔNG có gì đỏ ở tầng compile.
	 *
	 * Test này ghép với `IngestionRunnerTest#log_trong_vong_fetch_song_song_
	 * mang_cung_trace_id` thành một chuỗi đầy đủ: cái kia chứng minh `wrap` thật
	 * sự bắc được context, cái này chứng minh chỗ gọi ở đây có dùng nó. Dựng
	 * repository bằng tay với một spy thay vì `@MockitoSpyBean` để khỏi sinh
	 * thêm một Spring context (và một container Floci) chỉ cho một assertion.
	 */
	@Test
	void fan_out_runs_on_an_executor_that_bridges_the_trace_context() {
		TracePropagation spied = spy(new TracePropagation());
		ArticleRepository isolated = new ArticleRepository(enhancedClient, spied, tableName);

		isolated.findRecentBySources(SELECTED, 20);

		verify(spied).wrap(any());
	}

	/** Hai nguồn có bài xen kẽ nhau theo thời gian — xem fixture. */
	private static final List<String> SELECTED = List.of("spring-blog", "aws-news");

	/**
	 * `contains` trên `List.of(...)` ném NPE khi phần tử là null, mà bài chưa
	 * backfill có `sourceId` null — nên vế kiểm null phải đứng trước.
	 */
	private static List<String> expectedBySources(List<String> sourceIds) {
		return ArticleFixtures.load().stream()
				.filter(a -> a.getSourceId() != null && sourceIds.contains(a.getSourceId()))
				.sorted(Comparator.comparing(Article::getPublishedAt).reversed())
				.map(Article::getArticleId)
				.toList();
	}
}
