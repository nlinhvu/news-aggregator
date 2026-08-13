package dev.linhvu.news_aggregator.infra;

import java.util.List;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cognito.AllowedFirstAuthFactors;
import software.amazon.awscdk.services.cognito.CfnUserPoolGroup;
import software.amazon.awscdk.services.cognito.CognitoDomainOptions;
import software.amazon.awscdk.services.cognito.FeaturePlan;
import software.amazon.awscdk.services.cognito.OAuthFlows;
import software.amazon.awscdk.services.cognito.PasswordPolicy;
import software.amazon.awscdk.services.cognito.OAuthScope;
import software.amazon.awscdk.services.cognito.OAuthSettings;
import software.amazon.awscdk.services.cognito.SignInAliases;
import software.amazon.awscdk.services.cognito.SignInPolicy;
import software.amazon.awscdk.services.cognito.StandardAttribute;
import software.amazon.awscdk.services.cognito.StandardAttributes;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.cognito.UserPoolClient;
import software.amazon.awscdk.services.cognito.UserPoolClientOptions;
import software.amazon.awscdk.services.cognito.UserPoolDomain;
import software.amazon.awscdk.services.cognito.UserPoolDomainOptions;
import software.constructs.Construct;

/**
 * Hạ tầng danh tính, tách khỏi `AppStack` vì hai lý do:
 *
 * <ol>
 * <li><b>Vòng đời khác.</b> Xoá `AppStack` là mất compute, deploy lại là xong.
 *     Xoá stack này là mất TOÀN BỘ TÀI KHOẢN NGƯỜI DÙNG, và không có bản sao
 *     nào — Cognito không có PITR. Vì thế nó dùng `RemovalPolicy.RETAIN` ở MỌI
 *     môi trường, kể cả `dev`, khác với ba bảng DynamoDB.</li>
 * <li><b>Thứ tự deploy.</b> Code của `web` đọc `issuer-uri` lúc có người đăng
 *     nhập; pool phải tồn tại trước.</li>
 * </ol>
 *
 * KHÔNG dùng custom domain ở phase này. Domain mặc định của Cognito
 * (`<prefix>.auth.<region>.amazoncognito.com`) miễn phí và không cần chứng chỉ;
 * custom domain đòi thêm một ACM cert và một A record, đổi lấy một URL đẹp hơn
 * mà người dùng nhìn thấy trong ~5 giây. Ngưỡng xem lại: khi domain Cognito
 * trong thanh địa chỉ trở thành vấn đề tin cậy thật với người dùng thật.
 */
public class IdentityStack extends Stack {

	private final UserPool userPool;
	private final UserPoolClient client;
	private final UserPoolDomain domain;

	public IdentityStack(final Construct scope, final String id, final EnvConfig cfg) {
		super(scope, id, StackProps.builder()
				.env(cfg.awsEnvironment())
				.terminationProtection(true)   // MỌI môi trường — xem javadoc
				.build());

		// Prefix của managed login: `<prefix>.auth.<region>.amazoncognito.com` là
		// URL người dùng thấy lúc đăng nhập, và là gốc của `getLogoutUri()`.
		String domainPrefix = "na-" + cfg.tagPrefix() + "-auth";

		this.userPool = UserPool.Builder.create(this, "UserPool")
				.userPoolName("na-" + cfg.tagPrefix() + "-users")
				.selfSignUpEnabled(true)
				.signInAliases(SignInAliases.builder().email(true).build())
				.standardAttributes(StandardAttributes.builder()
						.email(StandardAttribute.builder()
								.required(true).mutable(true).build())
						.build())
				.removalPolicy(RemovalPolicy.RETAIN)
				.featurePlan(FeaturePlan.ESSENTIALS)
				// PASSWORD có mặt vì COGNITO BẮT BUỘC, không phải vì ta chọn. Bản
				// trước dùng `addPropertyOverride` để ghi đè danh sách thành
				// [EMAIL_OTP, WEB_AUTHN]; nó synth xanh và CHẾT trên môi trường
				// thật (2026-08-13):
				//
				//   "Invalid request provided: PASSWORD should be configured as
				//    one of the allowed first auth factors."
				//
				// Nghĩa là validation của L2 (`PasswordAuthenticationCannotDisabled`)
				// chép đúng luật service, và escape hatch chỉ dời chỗ chết từ synth
				// sang deploy. ADR-0017 vì thế đổi tầng chứ không đổi mục tiêu:
				// pool PHẢI liệt kê PASSWORD, nhưng không người dùng nào phải có
				// mật khẩu — Cognito cho phép đăng ký không mật khẩu khi pool có
				// passwordless factor.
				//
				// smsOtp CỐ Ý để mặc định (false): SMS tốn tiền theo tin nhắn và
				// đòi account được kích hoạt gửi SMS — xem spec §10.
				.signInPolicy(SignInPolicy.builder()
						.allowedFirstAuthFactors(AllowedFirstAuthFactors.builder()
								.password(true)
								.emailOtp(true)
								.passkey(true)
								.build())
						.build())
				// Hệ quả trực tiếp của dòng trên: cửa mật khẩu KHÔNG đóng được,
				// nên nó phải được canh. Từ giây Cognito ép PASSWORD vào danh
				// sách, "sẽ không ai đặt mật khẩu" là một Ý ĐỊNH chứ không phải
				// ràng buộc kỹ thuật, và chính sách này là thứ duy nhất đứng giữa
				// ý định đó với một tài khoản có mật khẩu `123456`.
				//
				// 12 ký tự chứ không phải 8 (mức tối thiểu `AwsSolutions-COG1`
				// đòi): không ai trong luồng thiết kế phải GÕ mật khẩu này, nên
				// độ dài không mua sự bất tiện nào.
				.passwordPolicy(PasswordPolicy.builder()
						.minLength(12)
						.requireLowercase(true)
						.requireUppercase(true)
						.requireDigits(true)
						.requireSymbols(true)
						.build())
				// KHÔNG khai `passkeyRelyingPartyId`, và đó là kết luận đã trả giá
				// bằng một lượt deploy (2026-08-13): đặt nó bằng domain Cognito thì
				// synth xanh và deploy chết —
				//
				//   "RelyingPartyId cannot be reserved domain other than User Pool's
				//    prefix domain"
				//
				// Giá trị `*.amazoncognito.com` chỉ hợp lệ khi nó LÀ prefix domain
				// của chính pool, mà `UserPoolDomain` là resource RIÊNG tạo SAU pool
				// (nó cần pool id). Tại thời điểm pool ra đời, pool chưa có domain
				// nào để giá trị đó khớp — không thứ tự nào trong một lượt deploy
				// sửa được.
				//
				// Bỏ trống là hợp lệ: CDK ghi default "No authentication domain", và
				// AWS chỉ BẮT BUỘC khai RP ID khi pool có CUSTOM DOMAIN — thứ phase
				// này cố ý không dùng (xem javadoc của class).
				//
				// Xem `SecurityBoundaryTest#pool_khong_khai_relying_party_id_vi_no_khong_deploy_duoc`
				// về đường sửa nếu QA passkey cho thấy Cognito không tự suy RP ID.
				.build();

		// Nhóm `ops` — toàn bộ mô hình phân quyền của chương trình (TDD §14.1).
		// Xuống token thành claim `cognito:groups`.
		CfnUserPoolGroup.Builder.create(this, "OpsGroup")
				.userPoolId(userPool.getUserPoolId())
				.groupName("ops")
				.description("Được vào mặt phẳng vận hành /admin/*")
				.build();

		this.domain = userPool.addDomain("Domain", UserPoolDomainOptions.builder()
				.cognitoDomain(CognitoDomainOptions.builder()
						.domainPrefix(domainPrefix)
						.build())
				.build());

		this.client = userPool.addClient("WebClient", UserPoolClientOptions.builder()
				// Confidential client: backend giữ secret, trình duyệt không bao
				// giờ thấy nó. Public client + PKCE cũng chạy được và bớt một
				// secret, nhưng với một OAuth client chạy phía server thì
				// confidential là mặc định của ngành — xem TDD §17 #2.
				.generateSecret(true)
				.oAuth(OAuthSettings.builder()
						.flows(OAuthFlows.builder()
								.authorizationCodeGrant(true)
								// implicit trả token thẳng vào URL trình duyệt.
								.implicitCodeGrant(false)
								.build())
						.scopes(List.of(OAuthScope.OPENID, OAuthScope.EMAIL))
						// Đường callback nằm TRONG /api/* để đi qua đúng behavior
						// CloudFront đã có — xem TDD §7.
						.callbackUrls(List.of("https://" + cfg.appDomain()
								+ "/api/auth/callback/cognito"))
						.logoutUrls(List.of("https://" + cfg.appDomain() + "/"))
						.build())
				.build());

		CfnOutput.Builder.create(this, "IssuerUri").value(getIssuerUri()).build();
		CfnOutput.Builder.create(this, "ClientId")
				.value(client.getUserPoolClientId()).build();
		CfnOutput.Builder.create(this, "LogoutUri").value(getLogoutUri()).build();
	}

	public String getIssuerUri() {
		return "https://cognito-idp." + getRegion() + ".amazonaws.com/"
				+ userPool.getUserPoolId();
	}

	public String getLogoutUri() {
		return domain.baseUrl() + "/logout";
	}

	public UserPoolClient getClient() {
		return client;
	}

	public UserPool getUserPool() {
		return userPool;
	}
}
