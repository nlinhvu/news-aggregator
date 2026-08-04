package dev.linhvu.news_aggregator.catalog;

import java.util.Comparator;
import java.util.List;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.seed.ArticleFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(FlociTestConfiguration.class)
class ArticleRepositoryTest {

	@Autowired
	ArticleRepository repository;

	/**
	 * Nạp ĐÚNG fixture mà `SeedApplication` ghi vào dev/qa/prod — dữ liệu test
	 * và dữ liệu đã seed không trôi khỏi nhau. Bảng do `FlociTestConfiguration`
	 * tạo, xem lý do ở đó.
	 *
	 * Chèn theo `publishedAt` TĂNG DẦN, tức NGƯỢC hẳn thứ tự mong đợi ở output.
	 * Chèn theo đúng thứ tự trong file thì test vẫn xanh kể cả khi query trả về
	 * theo thứ tự chèn — mất sạch khả năng bắt lỗi sắp xếp, mà sắp xếp lại đúng
	 * là thứ `gsi-recent` sinh ra để làm.
	 */
	@BeforeEach
	void napDuLieu() {
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
	void tra_ve_article_moi_nhat_truoc() {
		List<String> mongDoi = ArticleFixtures.load().stream()
				.sorted(Comparator.comparing(Article::getPublishedAt).reversed())
				.map(Article::getArticleId)
				.toList();

		assertThat(repository.findRecent(10))
				.extracting(Article::getArticleId)
				.containsExactlyElementsOf(mongDoi);
	}

	@Test
	void ton_trong_limit() {
		assertThat(repository.findRecent(2)).hasSize(2);
	}
}
