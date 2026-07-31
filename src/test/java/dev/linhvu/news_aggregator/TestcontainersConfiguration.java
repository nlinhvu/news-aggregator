//package dev.linhvu.news_aggregator;
//
//import org.springframework.boot.test.context.TestConfiguration;
//import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
//import org.springframework.context.annotation.Bean;
//import org.testcontainers.cassandra.CassandraContainer;
//import org.testcontainers.grafana.LgtmStackContainer;
//import org.testcontainers.ollama.OllamaContainer;
//import org.testcontainers.postgresql.PostgreSQLContainer;
//import org.testcontainers.utility.DockerImageName;
//
//@TestConfiguration(proxyBeanMethods = false)
//class TestcontainersConfiguration {
//
//	@Bean
//	@ServiceConnection
//	CassandraContainer cassandraContainer() {
//		return new CassandraContainer(DockerImageName.parse("cassandra:latest"));
//	}
//
//	@Bean
//	@ServiceConnection
//	LgtmStackContainer grafanaLgtmContainer() {
//		return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"));
//	}
//
//	@Bean
//	@ServiceConnection
//	OllamaContainer ollamaContainer() {
//		return new OllamaContainer(DockerImageName.parse("ollama/ollama:latest"));
//	}
//
//	@Bean
//	@ServiceConnection
//	PostgreSQLContainer postgresContainer() {
//		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
//	}
//
//}
