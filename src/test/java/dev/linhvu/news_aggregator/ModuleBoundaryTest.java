package dev.linhvu.news_aggregator;

import org.junit.jupiter.api.Test;

import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {

	static final ApplicationModules MODULES =
			ApplicationModules.of(NewsAggregatorApplication.class);

	/**
	 * Ranh giới module được kiểm chứng bằng máy, không bằng code review
	 * (master §3.1). Test này sẽ đỏ nếu một module import `internal`
	 * của module khác.
	 */
	@Test
	void validModule() {
		MODULES.verify();
	}
}
