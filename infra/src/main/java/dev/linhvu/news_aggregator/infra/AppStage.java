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
		IdentityStack identity = new IdentityStack(this, "IdentityStack", cfg);
		AppStack appStack = new AppStack(this, "AppStack", cfg,
				data.getArticlesTable(), data.getFeatureTogglesTable(),
				data.getSourcesTable(), data.getSessionsTable(),
				data.getUserPreferencesTable(), identity);
		new ObservabilityStack(this, "ObservabilityStack", cfg,
				appStack.getWebFunction(), appStack.getSummarizeFunction(),
				appStack.getIngestLogGroup(),
				appStack.getScheduleDlq(), appStack.getSummarizeDlq());
		EdgeStack edge = new EdgeStack(this, "EdgeStack", cfg,
				dns.getHostedZone(), dns.getCertificate(), appStack.getFunctionUrl());
		new CicdStack(this, "CicdStack", cfg,
				appStack.getAllFunctions(), edge.getBucket(), edge.getDistribution());
	}
}
