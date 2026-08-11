package dev.linhvu.news_aggregator.infra;

import software.amazon.awscdk.Stage;
import software.amazon.awscdk.StageProps;
import software.constructs.Construct;

public class AppStage extends Stage {

	public AppStage(final Construct scope, final EnvConfig cfg) {
		super(scope, cfg.name(), StageProps.builder()
				.env(cfg.awsEnvironment())
				.build());

		DnsStack dns = new DnsStack(this, "DnsStack", cfg);
		DataStack data = new DataStack(this, "DataStack", cfg);
		AppStack appStack = new AppStack(this, "AppStack", cfg,
				data.getArticlesTable(), data.getFeatureTogglesTable(),
				data.getSourcesTable());
		new ObservabilityStack(this, "ObservabilityStack", cfg,
				appStack.getFunction(), appStack.getLogGroup());
		EdgeStack edge = new EdgeStack(this, "EdgeStack", cfg,
				dns.getHostedZone(), dns.getCertificate(), appStack.getFunctionUrl());
		new CicdStack(this, "CicdStack", cfg,
				appStack.getFunction(), edge.getBucket(), edge.getDistribution());
	}
}
