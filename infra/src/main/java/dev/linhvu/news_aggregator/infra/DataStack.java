package dev.linhvu.news_aggregator.infra;

import java.util.List;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.ProjectionType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.constructs.Construct;

public class DataStack extends Stack {

	/**
	 * Tên GSI của AP1. Là hằng số public vì AppStack cấp quyền `dynamodb:Query`
	 * trỏ THẲNG vào ARN của index này — hai nơi viết rời cùng một chuỗi thì lệch
	 * nhau lúc nào không hay, và hậu quả là AccessDenied lúc runtime chứ không
	 * phải lỗi lúc synth.
	 */
	public static final String RECENT_INDEX_NAME = "gsi-recent";

	private final Table articlesTable;

	public DataStack(final Construct scope, final String id, final EnvConfig cfg) {
		super(scope, id, StackProps.builder()
				.env(cfg.awsEnvironment())
				.terminationProtection(cfg.terminationProtection())
				.build());

		this.articlesTable = Table.Builder.create(this, "ArticlesTable")
				.partitionKey(Attribute.builder()
						.name("articleId").type(AttributeType.STRING).build())
				.billingMode(BillingMode.PAY_PER_REQUEST)
				.removalPolicy(cfg.removalPolicy())
				.pointInTimeRecoverySpecification(
						PointInTimeRecoverySpecification.builder()
								.pointInTimeRecoveryEnabled(cfg.terminationProtection())
								.build())
				.build();

		// AP1: lấy N article mới nhất. Partition key HẰNG SỐ "ALL" —
		// xem TDD §6 "Worked example" về vì sao đây không phải hot partition
		// ở khối lượng này (tới hạn sau ~68 năm).
		this.articlesTable.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
				.indexName(RECENT_INDEX_NAME)
				.partitionKey(Attribute.builder()
						.name("listBucket").type(AttributeType.STRING).build())
				.sortKey(Attribute.builder()
						.name("publishedAt").type(AttributeType.STRING).build())
				.projectionType(ProjectionType.INCLUDE)
				.nonKeyAttributes(List.of(
						"title", "canonicalUrl", "sourceName", "summary"))
				.build());

		CfnOutput.Builder.create(this, "ArticlesTableName")
				.value(articlesTable.getTableName()).build();
	}

	public Table getArticlesTable() {
		return articlesTable;
	}
}
