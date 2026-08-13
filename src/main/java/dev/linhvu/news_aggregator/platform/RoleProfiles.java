package dev.linhvu.news_aggregator.platform;

/**
 * Bốn profile, mỗi cái ứng với một Lambda function (ADR-0020).
 *
 * Hằng số chứ không chuỗi rải rác, vì `@Profile` nhận String và một lỗi chính
 * tả ở đó KHÔNG có triệu chứng nào: bean chỉ đơn giản không được dựng, và
 * function chạy thiếu một nửa công việc mà không lỗi nào nổ ra. Một chỗ duy
 * nhất để grep.
 *
 * Giá trị phải khớp `AppStack.baseEnv` bên repo infra (`SPRING_PROFILES_ACTIVE`
 * = `aws,<profile>`). Hai repo không thấy nhau nên compiler không bắt được
 * lệch — `RoleProfileContextTest` là chốt chặn phía app, `SecurityBoundaryTest`
 * là chốt chặn phía infra.
 */
public final class RoleProfiles {

	public static final String WEB = "web";

	public static final String ADMIN = "admin";

	public static final String INGEST = "ingest";

	public static final String SUMMARIZE = "summarize";

	/**
	 * Hai function phục vụ HTTP từ CloudFront.
	 *
	 * `|` là cú pháp profile expression của Spring — nghĩa là *"hoặc"*, không
	 * phải một chuỗi literal. Viết sẵn ở đây để chỗ dùng không phải nhớ cú pháp.
	 */
	public static final String HTTP = WEB + " | " + ADMIN;

	/** Hai function nhận payload không-HTTP qua `/events`. */
	public static final String EVENT_DRIVEN = INGEST + " | " + SUMMARIZE;

	private RoleProfiles() {
	}
}
