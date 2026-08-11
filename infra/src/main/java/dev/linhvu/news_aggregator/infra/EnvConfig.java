package dev.linhvu.news_aggregator.infra;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.RemovalPolicy;

public record EnvConfig(
		String name,
		String account,
		String region,
		String zoneName,
		String appDomain,
		RemovalPolicy removalPolicy,
		boolean terminationProtection,
		String tagPrefix,

		/**
		 * ARN của web ACL do CloudFront TỰ TẠO khi account bật pricing plan.
		 *
		 * `null` nghĩa là account chưa bật plan nào — khi đó không khai báo
		 * `WebACLId` và CloudFront không đòi.
		 *
		 * Giá trị này KHÔNG biết trước lần create đầu tiên: CloudFront chỉ tạo
		 * web ACL khi distribution ra đời. Quy trình cho môi trường mới là để
		 * `null`, deploy lần đầu, rồi lấy ARN bằng
		 * `aws wafv2 list-web-acls --scope CLOUDFRONT` và điền vào đây. Bỏ bước
		 * đó thì lần deploy EdgeStack THỨ HAI sẽ fail, không phải lần đầu.
		 */
		String wafWebAclArn,

		/**
		 * Nhịp chạy ingestion. `null` nghĩa là **không tạo Schedule** cho môi
		 * trường này.
		 *
		 * `qa` để `null` có chủ đích: ba môi trường cùng poll blog gốc mỗi giờ
		 * là ×3 lượng request từ một org, và master §8.4 coi lịch sự với nguồn
		 * là ràng buộc chứ không phải phép xã giao. `qa` tồn tại để kiểm chứng
		 * deploy, không để tích luỹ dữ liệu.
		 *
		 * `prod` 1 giờ là con số master §3.1 yêu cầu: "bài mới trong vòng tối đa
		 * một giờ". Đó là yêu cầu của prod, không phải của cả ba.
		 */
		Duration ingestionRate,

		/**
		 * Nhịp chạy sweep. `null` nghĩa là **không tạo Schedule** cho môi trường
		 * này.
		 *
		 * Thưa hơn `ingestionRate` có chủ đích: sweep là LƯỚI AN TOÀN, không phải
		 * đường chính — `ArticleAddedListener` đã lo bài mới trong vòng vài phút.
		 * Ở nhịp 1 giờ, một bài hỏng vĩnh viễn sinh 48 message DLQ trong cửa sổ
		 * 48h và DLQ mất hẳn tác dụng làm tín hiệu; ở 6 giờ còn 8, vẫn đọc được.
		 *
		 * `qa` để `null` cùng lý do `ingestionRate`: qa tồn tại để kiểm chứng
		 * deploy, không để tích luỹ dữ liệu — và không có ingest thì cũng không
		 * có gì để sweep.
		 */
		Duration sweepRate
) {

	public static final EnvConfig DEV = new EnvConfig(
			"Dev", "440783445107", "us-east-1",
			"na-dev.linhvu.dev", "news.na-dev.linhvu.dev",
			RemovalPolicy.DESTROY, false, "dev",
			"arn:aws:wafv2:us-east-1:440783445107:global/webacl/"
					+ "CreatedByCloudFront-7ba3b475/82fc786b-fdcc-412e-9c40-053693386dc1",
			Duration.hours(6), Duration.hours(24));

	public static final EnvConfig QA = new EnvConfig(
			"Qa", "517353742264", "us-east-1",
			"na-qa.linhvu.dev", "news.na-qa.linhvu.dev",
			RemovalPolicy.DESTROY, false, "qa", null,
			null, null);

	public static final EnvConfig PROD = new EnvConfig(
			"Prod", "778799435139", "us-east-1",
			"news.linhvu.dev", "news.linhvu.dev",
			RemovalPolicy.RETAIN, true, "prod", null,
			Duration.hours(1), Duration.hours(6));


	/**
	 * Đích đến của mọi cảnh báo, cả ba môi trường. Một hộp thư, ba topic —
	 * TDD §17 #2 giải thích vì sao không gộp thành một topic dùng chung.
	 *
	 * Vì cả ba đổ về cùng hộp thư, TÊN ALARM phải mang tiền tố môi trường,
	 * nếu không mail không nói được nó từ đâu tới.
	 */
	public static final String OPERATOR_EMAIL = "vungoclinh2710@gmail.com";

	public static final String TOOLING_ACCOUNT = "237076104209";
	public static final String TOOLING_REGION = "us-east-1";
	public static final String ECR_REPOSITORY_NAME = "news-aggregator";

	public Environment awsEnvironment() {
		return Environment.builder().account(account).region(region).build();
	}
}
