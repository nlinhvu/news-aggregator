package dev.linhvu.news_aggregator.catalog;

import java.util.List;

import dev.linhvu.news_aggregator.FlociTestConfiguration;
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
	 * Chỉ nạp dữ liệu — bảng do `FlociTestConfiguration` tạo, xem lý do ở đó.
	 */
	@BeforeEach
	void napDuLieu() {
		repository.save(article("a", "2026-01-01T00:00:00Z", "Bài cũ nhất"));
		repository.save(article("c", "2026-03-01T00:00:00Z", "Bài mới nhất"));
		repository.save(article("b", "2026-02-01T00:00:00Z", "Bài ở giữa"));
	}

	private Article article(String id, String publishedAt, String title) {
		Article a = new Article();
		a.setArticleId(id);
		a.setListBucket(Article.LIST_BUCKET);
		a.setPublishedAt(publishedAt);
		a.setTitle(title);
		a.setCanonicalUrl("https://example.com/" + id);
		a.setSourceName("Example Blog");
		return a;
	}

	@Test
	void tra_ve_article_moi_nhat_truoc() {
		List<Article> found = repository.findRecent(10);

		assertThat(found).extracting(Article::getTitle)
				.containsExactly("Bài mới nhất", "Bài ở giữa", "Bài cũ nhất");
	}

	@Test
	void ton_trong_limit() {
		assertThat(repository.findRecent(2)).hasSize(2);
	}
}
