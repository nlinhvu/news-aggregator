package dev.linhvu.news_aggregator;

import java.lang.reflect.RecordComponent;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import org.junit.jupiter.api.Test;

import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleBoundaryTest {

	/**
	 * Món nợ mà ADR-0012 §7 hẹn từ Phase 2, tới hạn ở Phase 3.
	 *
	 * Cycle `catalog ↔ summarization` là CÓ THẬT và được chấp nhận có ý thức:
	 * `summarization` nghe `ArticleAdded` (catalog phát), `catalog` nghe
	 * `ArticleSummarized` (summarization phát). Đó là cycle đúng nghĩa EDA và
	 * Modulith cho phép nó LÚC RUNTIME; chỉ `verify()` mới từ chối nó, vì ở tầng
	 * biên dịch một tham chiếu tới type là một dependency.
	 *
	 * Predicate này loại event type khỏi phạm vi phân tích. Cạnh do tham chiếu
	 * event tạo ra biến mất; mọi vi phạm ranh giới THẬT — một module import class
	 * `internal` của module khác — vẫn bị bắt.
	 *
	 * CÁI MẤT, nói đúng kích thước: `verify()` không còn bắt được một event
	 * record lén mang theo type nội bộ của publisher. Khoảng trống đó có biên rõ
	 * và được bịt bằng `event_record_chi_chua_string` — test đó từ nay là thứ
	 * DUY NHẤT canh chỗ này, nên không được nới nó.
	 */
	static final ApplicationModules MODULES = ApplicationModules.of(
			NewsAggregatorApplication.class,
			DescribedPredicate.describe("event type",
					type -> type.getPackageName().endsWith(".events")));

	/**
	 * Ranh giới module được kiểm chứng bằng máy, không bằng code review
	 * (master §3.1). Test này sẽ đỏ nếu một module import `internal`
	 * của module khác.
	 */
	@Test
	void validModule() {
		MODULES.verify();
	}

	/**
	 * Điều kiện tiên quyết của ADR-0012. Phase 3 sẽ loại event type khỏi phạm vi
	 * `ApplicationModules.verify()` để chấp nhận cycle `catalog ↔ summarization`;
	 * khoảng trống đó chỉ bịt được nếu event record không thể mang theo type nội
	 * bộ của publisher. Test này phải có TRƯỚC cái predicate kia.
	 */
	@Test
	void event_record_chi_chua_string() {
		List<Class<?>> eventTypes = List.of(
				dev.linhvu.news_aggregator.ingestion.events.ArticleDiscovered.class,
				dev.linhvu.news_aggregator.catalog.events.ArticleAdded.class,
				dev.linhvu.news_aggregator.summarization.events.ArticleSummarized.class);

		for (Class<?> type : eventTypes) {
			assertThat(type.isRecord())
					.as("%s phải là record", type.getSimpleName()).isTrue();
			for (RecordComponent component : type.getRecordComponents()) {
				assertThat(component.getType())
						.as("%s.%s phải là String", type.getSimpleName(),
								component.getName())
						.isEqualTo(String.class);
			}
		}
	}

	/**
	 * `CanonicalUrl.articleId` (ingestion) và `CatalogIds.articleId` (catalog)
	 * là trùng lặp có ý thức. Test này là thứ giữ cho chúng không trôi khỏi nhau.
	 * Cả hai đều package-private nên test phải nằm đúng package tương ứng —
	 * dùng phản chiếu ở đây thay vì mở visibility của production code.
	 *
	 * Nhiều URL chứ không một: hai hàm cùng trả hằng số cũng làm một phép so
	 * đơn lẻ xanh, mà "cùng sai" thì lệch id là vĩnh viễn — dedupe chặn ghi lại.
	 */
	@Test
	void hai_module_suy_ra_cung_mot_id() throws Exception {
		var ingestion = Class.forName(
						"dev.linhvu.news_aggregator.ingestion.CanonicalUrl")
				.getDeclaredMethod("articleId", String.class);
		var catalog = Class.forName(
						"dev.linhvu.news_aggregator.catalog.CatalogIds")
				.getDeclaredMethod("articleId", String.class);
		ingestion.setAccessible(true);
		catalog.setAccessible(true);

		for (String url : List.of("https://spring.io/blog/post",
				"https://a.test/1", "https://a.test/2", "")) {
			assertThat(catalog.invoke(null, url))
					.as("id của %s phải khớp giữa hai module", url)
					.isEqualTo(ingestion.invoke(null, url));
		}
	}
}
