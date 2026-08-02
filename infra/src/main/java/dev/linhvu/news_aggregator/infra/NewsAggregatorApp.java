package dev.linhvu.news_aggregator.infra;

import software.amazon.awscdk.App;

public class NewsAggregatorApp {
    public static void main(final String[] args) {
        App app = new App();

        new AppStage(app, EnvConfig.DEV);
        new AppStage(app, EnvConfig.QA);
        new AppStage(app, EnvConfig.PROD);

        app.synth();
    }
}

