package dev.linhvu.news_aggregator.identity;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.linhvu.news_aggregator.FlociTestConfiguration;
import dev.linhvu.news_aggregator.platform.NewsFeature;
import dev.linhvu.news_aggregator.platform.RoleProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.togglz.core.context.FeatureContext;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.repository.FeatureState;
import org.togglz.testing.TestFeatureManagerProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Togglz console CHẠY THẬT: đăng nhập bằng IdP thật, mở trang thật, lật flag
 * thật rồi đọc lại trạng thái từ `FeatureManager`.
 *
 * <p><b>Vì sao không thể là MockMvc.</b> Console là một
 * `ServletRegistrationBean` — một servlet của CONTAINER. MockMvc chỉ chạy filter
 * chain và `DispatcherServlet`, nên với nó `/admin/togglz/index` mãi mãi là 404
 * dù code đúng hay sai. `AdminAccessTest` giữ phần filter chain quyết định;
 * phần còn lại chỉ Tomcat thật mới nói được.
 *
 * <p><b>Profile `admin`.</b> Console sống trên function `admin` (ADR-0020), và
 * luồng đăng nhập vẫn dựng được ở đó vì `AuthController` cùng `SecurityConfig`
 * đều `@Profile(HTTP)`.
 *
 * <p><b>Nhóm `ops` đến từ TOKEN THẬT.</b> `MockOAuth2ServerConfiguration` phát
 * `cognito:groups: ["ops"]`, nên test này là chỗ DUY NHẤT chứng minh
 * `OpsAuthoritiesMapper` đọc đúng tên claim mà IdP phát ra. Mọi test dùng
 * `oidcLogin().authorities(...)` đều nhét thẳng authority vào và đi vòng qua
 * đúng đoạn ánh xạ đó.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles(RoleProfiles.ADMIN)
@Import({ FlociTestConfiguration.class, MockOAuth2ServerConfiguration.class })
class AdminConsoleIT {

	/** `<input type="hidden" name="…" value="…">` do template của Togglz render. */
	private static final Pattern HIDDEN_FIELD = Pattern.compile(
			"<input type=\"hidden\" name=\"([^\"]+)\" value=\"([^\"]*)\"");

	@Autowired
	TestRestTemplate autowired;

	@Autowired
	FeatureManager featureManager;

	private TestRestTemplate rest;

	@BeforeEach
	void khongDiTheoRedirect() {
		this.rest = autowired.withRedirects(HttpRedirects.DONT_FOLLOW);
	}

	/**
	 * Ép `FeatureContext` trả về FeatureManager của Spring — nếu không, console
	 * render một BẢNG RỖNG và mọi khẳng định dưới xanh hoặc đỏ vì lý do sai.
	 *
	 * <p>Console không nhận `FeatureManager` bằng injection: `TogglzConsoleServlet`
	 * dựng một `LazyResolvingFeatureManager` và hỏi `FeatureContext` lúc chạy. Mà
	 * `togglz-testing` (kéo vào theo `togglz-junit`) đăng ký
	 * `FallbackTestFeatureManagerProvider` priority **20**, đứng TRƯỚC provider
	 * của Spring priority 60 — và manager đó không biết feature nào.
	 *
	 * <p>Triệu chứng đúng là thứ đã đo trong lượt chạy đỏ đầu tiên: trang trả
	 * `200`, đủ khung bảng, đủ tab "All Features", và **không một dòng feature
	 * nào**. Không lỗi, không log — chỉ là một bảng trống trông y hệt "chưa có
	 * flag nào được khai".
	 */
	@BeforeEach
	void dungFeatureManagerThatChuKhongPhaiFallback() {
		TestFeatureManagerProvider.setFeatureManager(featureManager);
		FeatureContext.clearCache();
		// Bật `USER_ACCOUNTS` trong state repository THẬT (bảng `feature-toggles`
		// của Floci), không bằng một manager giả.
		//
		// Bắt buộc, vì `UserAccountsGate` khoá `/api/auth/**` khi flag OFF — mà
		// bảng của Floci rỗng nên Togglz rơi về mặc định của enum, tức OFF. Không
		// có dòng này thì `/api/auth/login` trả 404 và luồng đăng nhập chết ở
		// chặng đầu với một NPE trên `Location` rỗng, một triệu chứng không hề
		// nhắc tới feature flag.
		//
		// Cái được kèm theo: lời gọi này đi qua ĐÚNG đường ghi của prod —
		// `FailClosedDynamoDbStateRepository.setFeatureState` → `UpdateItem` trên
		// DynamoDB thật — tức là chính action mà role của `admin` được cấp ở Task 26.
		featureManager.setFeatureState(new FeatureState(NewsFeature.USER_ACCOUNTS, true));
	}

	/**
	 * `FeatureContext` cache FeatureManager trong một field STATIC còn Gradle
	 * chạy cả suite trong một JVM, nên không dọn thì manager của class này rò
	 * sang class khác kèm cả Spring context đã đóng.
	 */
	@AfterEach
	void traLaiFeatureManagerVeNguyenTrang() {
		TestFeatureManagerProvider.setFeatureManager(null);
		FeatureContext.clearCache();
	}

	/**
	 * Vế mà cả Task 27 tồn tại để chứng minh: console CÓ MẶT.
	 *
	 * <p>Trước khi có `togglz-console` trên classpath, `togglz.console.enabled:
	 * true` im lặng không làm gì — `TogglzConsoleConfiguration` là
	 * `@ConditionalOnClass(TogglzConsoleServlet.class)` và starter KHÔNG kéo
	 * artifact đó theo. Triệu chứng khi ấy giống hệt "chưa cấu hình": 404, không
	 * một dòng log.
	 */
	@Test
	void nguoi_thuoc_ops_mo_duoc_console_va_thay_du_flag() {
		String cookie = dangNhap();

		ResponseEntity<String> index = exchange(at("/admin/togglz/index"),
				HttpMethod.GET, cookie, String.class);

		assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
		// Đủ CẢ HAI flag, không chỉ "trang có nội dung". Danh sách này đến từ
		// `EnumBasedFeatureProvider` (enum `NewsFeature`) chứ không từ một `Scan`
		// bảng — đó cũng là lý do role của `admin` không có `dynamodb:Scan`.
		assertThat(index.getBody())
				.contains(NewsFeature.AI_SUMMARIZATION.name())
				.contains(NewsFeature.USER_ACCOUNTS.name());
	}

	/**
	 * CSRF: form do THƯ VIỆN render phải mang được token của Spring Security.
	 *
	 * <p>Đây là câu trả lời thực nghiệm cho câu hỏi 2 ở §3 của spec. Không có
	 * `SpringCsrfTokenProvider`, form chỉ mang `togglz_csrf` — `CsrfFilter` của
	 * Spring nằm trước servlet và trả 403, tức nút bật/tắt trên console không
	 * bao giờ hoạt động.
	 *
	 * <p>Test đọc field từ CHÍNH trang console rồi gửi lại, chứ không tự dựng
	 * token: một test tự dựng `_csrf` sẽ xanh kể cả khi template không render
	 * field nào — mà "template có render hay không" chính là điều cần kiểm.
	 */
	@Test
	void lat_flag_tu_console_di_lot_csrf_va_doi_that_trang_thai() {
		String cookie = dangNhap();
		ResponseEntity<String> index = exchange(at("/admin/togglz/index"),
				HttpMethod.GET, cookie, String.class);

		assertThat(csrfFieldName(index.getBody()))
				.as("form của console phải mang field CSRF của Spring, không chỉ togglz_csrf")
				.isEqualTo("_csrf");

		boolean truoc = featureManager.isActive(NewsFeature.AI_SUMMARIZATION);

		ResponseEntity<String> flip = post(at("/admin/togglz/edit"), cookie,
				formLatFlag(index.getBody(), NewsFeature.AI_SUMMARIZATION, !truoc));

		assertThat(flip.getStatusCode())
				.as("403 ở đây nghĩa là CsrfFilter chặn — form thiếu token của Spring")
				.isNotEqualTo(HttpStatus.FORBIDDEN);
		// Đọc lại từ `FeatureManager`, KHÔNG từ trang console: một trang render
		// đúng nhưng không ghi được xuống bảng vẫn thoả mọi vế ở trên.
		assertThat(featureManager.isActive(NewsFeature.AI_SUMMARIZATION))
				.as("lật flag phải đổi trạng thái THẬT trong state repository")
				.isEqualTo(!truoc);
	}

	/**
	 * Script rewrite form PHẢI có mặt trong trang, và flag phải lật được bằng
	 * đúng hình dạng request mà script ấy gửi: **query string, thân RỖNG**.
	 *
	 * <p>Đây là chốt chặn cho một chế độ hỏng mà local KHÔNG BAO GIỜ lộ ra: ở
	 * local không có CloudFront nên form gốc (có body) chạy hoàn hảo, còn trên
	 * AWS nó nhận 403 của AWS trước khi tới ứng dụng. Đo trên dev 2026-08-19 —
	 * xem `ConsoleFormRewriteFilter`.
	 *
	 * <p>Vế "có script" một mình là chưa đủ, và vế "POST query string lật được
	 * flag" một mình cũng chưa đủ: cái đầu xanh khi script chèn vào nhưng sai
	 * cách gửi, cái sau xanh khi server chấp nhận nhưng trang không mang script
	 * nên trình duyệt vẫn gửi bản có body. Phải có CẢ HAI.
	 */
	@Test
	void console_mang_script_rewrite_va_lat_duoc_flag_bang_query_string() {
		String cookie = dangNhap();
		ResponseEntity<String> index = exchange(at("/admin/togglz/index"),
				HttpMethod.GET, cookie, String.class);

		assertThat(index.getBody())
				.as("thiếu script thì trình duyệt gửi form CÓ BODY và AWS chặn ở CloudFront")
				.contains("new URLSearchParams(new FormData(form))")
				.contains("method: 'POST'");

		// `Content-Length` phải khai ĐỘ DÀI SAU KHI CHÈN. Vế này canh phần dễ hỏng
		// nhất của `BufferingResponse`: console gọi `flushBuffer()` ngay sau khi
		// ghi xong, và nếu wrapper uỷ quyền xuống response THẬT thì response bị
		// commit trước, `setContentLength` sau đó bị bỏ qua lặng lẽ, và trang đi
		// ra dạng chunked không có `Content-Length` nào.
		assertThat(index.getHeaders().getContentLength())
				.as("Content-Length phải khớp thân ĐÃ chèn script")
				.isEqualTo(index.getBody().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

		String csrf = csrfValue(index.getBody());
		boolean truoc = featureManager.isActive(NewsFeature.AI_SUMMARIZATION);

		// ĐÚNG hình dạng script gửi: tham số ở query string, thân RỖNG.
		String query = "?f=" + NewsFeature.AI_SUMMARIZATION.name()
				+ (truoc ? "" : "&enabled=enabled")
				+ "&_csrf=" + csrf;
		ResponseEntity<String> flip = rest.exchange(at("/admin/togglz/edit" + query),
				HttpMethod.POST, new HttpEntity<>(cookieHeaders(cookie)), String.class);

		assertThat(flip.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
		assertThat(featureManager.isActive(NewsFeature.AI_SUMMARIZATION))
				.as("POST thân rỗng + query string phải lật được flag THẬT")
				.isEqualTo(!truoc);
		// Redirect THỨ HAI của console, và là cái người vận hành gặp ngay sau khi
		// bấm nút: `EditPageHandler` gọi `sendRedirect("index")` — TƯƠNG ĐỐI THẬT
		// SỰ, không có dấu `/` đầu. Nối chuỗi thay vì `URI.resolve` sẽ biến nó
		// thành `/index` (404); để nguyên thì nó mang host của Function URL.
		assertThat(flip.getHeaders().getLocation().toString())
				.isEqualTo("http://localhost:8080/admin/togglz/index");
	}

	/**
	 * `/admin/togglz` (không có `/index`) là URL người vận hành GÕ VÀO, và chặng
	 * 302 của nó phải trỏ về DOMAIN CÔNG KHAI.
	 *
	 * <p>Redirect này do THƯ VIỆN phát, không phải code của ta:
	 * `InitialRedirectHandler` gọi `sendRedirect(request.getRequestURI() +
	 * "/index")` — một đường dẫn TƯƠNG ĐỐI. Servlet container biến nó thành tuyệt
	 * đối bằng `Host` của REQUEST, mà sau CloudFront thì `Host` là Function URL
	 * (`ALL_VIEWER_EXCEPT_HOST_HEADER` strip host của viewer vì SigV4 đòi host của
	 * Function URL).
	 *
	 * <p>ĐÃ ĐO trên dev 2026-08-19, với tài khoản ĐÃ ở trong nhóm `ops`:
	 * <pre>
	 *   GET https://news.na-dev.linhvu.dev/admin/togglz
	 *     → 302 https://mmwu…lyvoh.lambda-url.us-east-1.on.aws/admin/togglz/index
	 *     → Forbidden
	 * </pre>
	 * Function URL có `AuthType=AWS_IAM`, trình duyệt không ký SigV4 được. Đăng
	 * nhập ĐÚNG, nhóm ĐÚNG, phân quyền ĐÚNG — và người vận hành vẫn không vào
	 * được, với một thông báo không nhắc gì tới ba thứ đó.
	 *
	 * <p><b>Test này tái hiện được ở local vì `public-base-url` (`localhost:8080`)
	 * KHÁC cổng ngẫu nhiên của server test.</b> Đó là điều kiện duy nhất khiến
	 * lỗi lộ ra — một IT chạy đúng cổng 8080 sẽ xanh trong khi prod hỏng.
	 */
	@Test
	void chang_302_cua_console_tro_ve_domain_cong_khai_khong_phai_host_cua_request() {
		String cookie = dangNhap();

		ResponseEntity<Void> toIndex = exchange(at("/admin/togglz"),
				HttpMethod.GET, cookie, Void.class);

		assertThat(toIndex.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(toIndex.getHeaders().getLocation().toString())
				.as("dựng từ host của REQUEST thì sau CloudFront nó là Function URL, "
						+ "và trình duyệt nhận Forbidden")
				.isEqualTo("http://localhost:8080/admin/togglz/index");
	}

	/**
	 * Mỗi lần đổi flag để lại ĐÚNG một dòng log có `sub`, tên flag, giá trị cũ và
	 * mới — mục Observability của walkthrough slice 5.
	 *
	 * <p>Đây là chỗ duy nhất trả lời được *"ai tắt `AI_SUMMARIZATION` lúc 3 giờ
	 * sáng"*. CloudTrail chỉ thấy execution role của `admin`, tức cùng một danh
	 * tính cho mọi người vận hành.
	 *
	 * <p>Test phải chạy trên luồng THẬT: `SecurityContextHolder` chỉ có principal
	 * trong thread xử lý request của một phiên đã đăng nhập. Gọi thẳng
	 * `setFeatureState` trong một unit test sẽ log `sub=khong-ro` và xanh vì lý
	 * do sai.
	 */
	@Test
	void moi_lan_doi_flag_de_lai_mot_dong_log_co_sub_va_gia_tri_cu_moi() {
		String cookie = dangNhap();
		ResponseEntity<String> index = exchange(at("/admin/togglz/index"),
				HttpMethod.GET, cookie, String.class);
		String csrf = csrfValue(index.getBody());
		boolean truoc = featureManager.isActive(NewsFeature.AI_SUMMARIZATION);

		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
				LoggerFactory.getLogger(
						"dev.linhvu.news_aggregator.platform.TogglzConfig$FailClosedDynamoDbStateRepository");
		logs.start();
		logger.addAppender(logs);
		try {
			rest.exchange(at("/admin/togglz/edit?f=" + NewsFeature.AI_SUMMARIZATION.name()
							+ (truoc ? "" : "&enabled=enabled") + "&_csrf=" + csrf),
					HttpMethod.POST, new HttpEntity<>(cookieHeaders(cookie)), String.class);
		}
		finally {
			logger.detachAppender(logs);
			logs.stop();
		}

		assertThat(logs.list).singleElement()
				.extracting(ILoggingEvent::getFormattedMessage).asString()
				.contains("sub=3f0a2c58-6b1e-4d7a-9f21-0c9a1b2d3e4f")
				.contains("flag=" + NewsFeature.AI_SUMMARIZATION.name())
				.contains("truoc=" + (truoc ? "ON" : "OFF"))
				.contains("sau=" + (truoc ? "OFF" : "ON"))
				// ID token của mock server mang `email: dev@local`, nên vế này đo
				// trên dữ liệu thật chứ không trên một map tự dựng.
				.doesNotContain("dev@local");
	}

	/**
	 * Cổng `ops` với một PHIÊN THẬT, không phải với authority nhét tay.
	 *
	 * <p>`AdminAccessTest` kiểm cùng câu hỏi bằng `oidcLogin().authorities()` —
	 * thứ đi vòng qua `OpsAuthoritiesMapper`. Ở đây token do IdP phát ra, nên
	 * nếu tên claim đọc sai thì test này đỏ còn test kia vẫn xanh.
	 */
	@Test
	void an_danh_bi_day_sang_dang_nhap_chu_khong_nhan_trang_trang() {
		ResponseEntity<Void> anDanh = exchange(at("/admin/togglz/index"),
				HttpMethod.GET, null, Void.class);

		assertThat(anDanh.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(anDanh.getHeaders().getLocation().toString())
				.isEqualTo("http://localhost:8080/api/auth/login/cognito");
	}

	/**
	 * Lấy `<form>` mà console render cho một feature rồi đổi đúng ô `enabled`.
	 *
	 * <p>Console gửi `enabled=enabled` để BẬT và bỏ hẳn field để TẮT — đó là
	 * cách một checkbox HTML hoạt động, không phải `enabled=false`. Gửi `false`
	 * sẽ được đọc là "có mặt" và flag bật lên, tức test xanh theo chiều ngược.
	 */
	private MultiValueMap<String, String> formLatFlag(String html, NewsFeature feature,
			boolean bat) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		Matcher matcher = HIDDEN_FIELD.matcher(html);
		while (matcher.find()) {
			if ("_csrf".equals(matcher.group(1))) {
				form.add("_csrf", matcher.group(2));
			}
		}
		form.add("f", feature.name());
		if (bat) {
			form.add("enabled", "enabled");
		}
		return form;
	}

	private static String csrfValue(String html) {
		Matcher matcher = HIDDEN_FIELD.matcher(html);
		while (matcher.find()) {
			if ("_csrf".equals(matcher.group(1))) {
				return matcher.group(2);
			}
		}
		throw new AssertionError("trang console không có field _csrf nào");
	}

	private static HttpHeaders cookieHeaders(String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, cookie);
		return headers;
	}

	private static String csrfFieldName(String html) {
		Matcher matcher = HIDDEN_FIELD.matcher(html);
		while (matcher.find()) {
			if ("_csrf".equals(matcher.group(1))) {
				return matcher.group(1);
			}
		}
		return "KHÔNG CÓ field _csrf nào trong trang";
	}

	/** Trọn luồng authorization code, trả về cookie phiên — khuôn của `LoginFlowIT`. */
	private String dangNhap() {
		ResponseEntity<Void> toEntryPoint = get(at("/api/auth/login"), null);
		String cookie = sessionCookie(toEntryPoint, null);

		ResponseEntity<Void> toIdp = get(
				onTestServer(toEntryPoint.getHeaders().getLocation()), cookie);
		cookie = sessionCookie(toIdp, cookie);

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", "3f0a2c58-6b1e-4d7a-9f21-0c9a1b2d3e4f");
		form.add("claims", "{}");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		ResponseEntity<Void> toCallback = rest.exchange(
				toIdp.getHeaders().getLocation(), HttpMethod.POST,
				new HttpEntity<>(form, headers), Void.class);
		assertThat(toCallback.getStatusCode()).isEqualTo(HttpStatus.FOUND);

		ResponseEntity<Void> afterCallback = get(
				onTestServer(toCallback.getHeaders().getLocation()), cookie);
		assertThat(afterCallback.getHeaders().getLocation().toString())
				.as("đăng nhập phải THÀNH CÔNG, không rơi vào failureUrl")
				.doesNotContain("login=failed");
		return sessionCookie(afterCallback, cookie);
	}

	private URI at(String pathAndQuery) {
		return URI.create(rest.getRootUri() + pathAndQuery);
	}

	private URI onTestServer(URI location) {
		String query = location.getRawQuery();
		return at(query == null ? location.getRawPath()
				: location.getRawPath() + "?" + query);
	}

	private static String sessionCookie(ResponseEntity<?> response, String current) {
		List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		if (setCookies == null || setCookies.isEmpty()) {
			return current;
		}
		StringBuilder merged = new StringBuilder();
		for (String setCookie : setCookies) {
			if (!merged.isEmpty()) {
				merged.append("; ");
			}
			merged.append(setCookie.split(";", 2)[0]);
		}
		return merged.toString();
	}

	private ResponseEntity<Void> get(URI uri, String cookie) {
		return exchange(uri, HttpMethod.GET, cookie, Void.class);
	}

	private <T> ResponseEntity<T> exchange(URI uri, HttpMethod method, String cookie,
			Class<T> type) {
		HttpHeaders headers = new HttpHeaders();
		if (cookie != null) {
			headers.add(HttpHeaders.COOKIE, cookie);
		}
		return rest.exchange(uri, method, new HttpEntity<>(headers), type);
	}

	/**
	 * POST dạng form, CỐ Ý KHÔNG gửi header `X-XSRF-TOKEN`.
	 *
	 * <p>Đó là điểm mấu chốt: trình duyệt submit form của console không thêm
	 * header nào được, nên token PHẢI đi trong thân form. Thêm header ở đây sẽ
	 * làm test xanh kể cả khi `SpringCsrfTokenProvider` không tồn tại.
	 */
	private ResponseEntity<String> post(URI uri, String cookie,
			MultiValueMap<String, String> form) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, cookie);
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		return rest.exchange(uri, HttpMethod.POST, new HttpEntity<>(form, headers),
				String.class);
	}
}
