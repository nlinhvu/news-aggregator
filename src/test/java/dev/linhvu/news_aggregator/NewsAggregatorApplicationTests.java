package dev.linhvu.news_aggregator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NewsAggregatorApplicationTests {

	@Test
	void contextLoads() {
	}

}
