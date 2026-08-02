package dev.linhvu.news_aggregator;

import org.junit.jupiter.api.Test;

import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {

	static final ApplicationModules MODULES =
			ApplicationModules.of(NewsAggregatorApplication.class);

	@Test
	void validModule() {
		MODULES.verify();
	}
}
