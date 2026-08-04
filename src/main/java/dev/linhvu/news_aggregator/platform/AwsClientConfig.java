package dev.linhvu.news_aggregator.platform;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
public class AwsClientConfig {

	@Bean
	@Lazy
	DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
		return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
	}
}
