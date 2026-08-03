package dev.linhvu.news_aggregator.infra;

import software.amazon.awscdk.Stage;
import software.amazon.awscdk.StageProps;
import software.constructs.Construct;

public class AppStage extends Stage {

	public AppStage(final Construct scope, final EnvConfig cfg) {
		super(scope, cfg.name(), StageProps.builder()
				.env(cfg.awsEnvironment())
				.build());

		new DnsStack(this, "DnsStack", cfg);
		AppStack appStack = new AppStack(this, "AppStack", cfg);
	}
}
