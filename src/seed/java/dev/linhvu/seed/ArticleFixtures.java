package dev.linhvu.seed;

import java.io.InputStream;
import java.util.List;

import dev.linhvu.news_aggregator.catalog.Article;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fixture dùng chung giữa `SeedApplication` và test T2.
 *
 * Tách ra để test không chỉ đọc cùng FILE mà còn đi qua cùng CODE PATH: một
 * fixture hỏng — sai cú pháp, hoặc mang field không còn tồn tại trên
 * {@link Article} — làm đỏ `./gradlew test` ngay tại máy, thay vì đợi tới lúc
 * chạy seed job trên môi trường thật.
 */
public final class ArticleFixtures {

	private static final String PATH = "/fixtures/articles.json";

	private ArticleFixtures() {
	}

	/**
	 * `FAIL_ON_UNKNOWN_PROPERTIES` phải bật TƯỜNG MINH — Jackson 3 tắt nó mặc
	 * định, ngược với Jackson 2. Không bật thì một field bị đổi tên sẽ được nuốt
	 * im lặng, và cả lý do tồn tại của việc nạp fixture qua {@link Article} thay
	 * vì qua `aws dynamodb batch-write-item` sẽ mất sạch.
	 */
	public static List<Article> load() {
		ObjectMapper mapper = JsonMapper.builder()
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.build();

		try (InputStream in = ArticleFixtures.class.getResourceAsStream(PATH)) {
			if (in == null) {
				throw new IllegalStateException(
						"Không tìm thấy " + PATH + " trên classpath");
			}
			return mapper.readValue(in, mapper.getTypeFactory()
					.constructCollectionType(List.class, Article.class));
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("Không đọc được " + PATH, ex);
		}
	}
}
