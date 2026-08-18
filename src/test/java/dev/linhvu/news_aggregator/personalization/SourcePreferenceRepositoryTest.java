package dev.linhvu.news_aggregator.personalization;

import java.util.List;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(FlociTestConfiguration.class)
class SourcePreferenceRepositoryTest {

	@Autowired
	SourcePreferenceRepository repository;

	/**
	 * Người dùng chưa từng chọn gì ⇒ `Optional.empty()`, KHÔNG phải một item
	 * rỗng và cũng không phải exception. `MyFeedController` dịch nó thành "tất
	 * cả nguồn", nên một `null` lọt ra từ đây là một trang trắng.
	 */
	@Test
	void chua_tung_chon_thi_khong_co_item() {
		assertThat(repository.findByUserId("chua-bao-gio-ghi")).isEmpty();
	}

	@Test
	void ghi_roi_doc_lai_dung_danh_sach() {
		repository.save("sub-1", List.of("spring-blog", "aws-news"));

		assertThat(repository.findByUserId("sub-1"))
				.get()
				.extracting(SourcePreferences::getSourceIds)
				.isEqualTo(List.of("spring-blog", "aws-news"));
	}

	/**
	 * "Bỏ chọn hết" là một DANH SÁCH RỖNG, không phải xoá item — đó là lý do
	 * `web` không cần `dynamodb:DeleteItem` trên bảng này.
	 *
	 * Vế đáng canh: DynamoDB nhận list rỗng (khác Set rỗng, thứ nó TỪ CHỐI), và
	 * item đọc lại phải cho ra `[]` chứ không phải `null`. Nếu nó cho ra null,
	 * người vừa bỏ chọn hết sẽ thấy feed đầy đủ trở lại và tưởng nút hỏng.
	 */
	@Test
	void bo_chon_het_la_danh_sach_rong_chu_khong_phai_xoa_item() {
		repository.save("sub-2", List.of("spring-blog"));

		repository.save("sub-2", List.of());

		assertThat(repository.findByUserId("sub-2"))
				.get()
				.extracting(SourcePreferences::getSourceIds)
				.isEqualTo(List.of());
	}

	/** `updatedAt` để người vận hành biết lựa chọn có còn được dùng không. */
	@Test
	void ghi_kem_moc_thoi_gian() {
		repository.save("sub-3", List.of("aws-news"));

		assertThat(repository.findByUserId("sub-3"))
				.get()
				.extracting(SourcePreferences::getUpdatedAt)
				.asString()
				.startsWith("20");
	}
}
