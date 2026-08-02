package dev.linhvu.news_aggregator.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class NewsAggregatorApp {
    public static void main(final String[] args) {
        App app = new App();

        StackProps toolingProps = StackProps.builder()
                .env(Environment.builder()
                        .account(EnvConfig.TOOLING_ACCOUNT)
                        .region("us-east-1")
                        .build())
                .build();

        new OidcHubStack(app, "OidcHubStack", toolingProps);
        new RegistryStack(app, "RegistryStack", toolingProps);

        new AppStage(app, EnvConfig.DEV);
        new AppStage(app, EnvConfig.QA);
        new AppStage(app, EnvConfig.PROD);

        app.synth();
    }
}

