package dev.linhvu.news_aggregator.identity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import dev.linhvu.news_aggregator.platform.RoleProfiles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Chèn một đoạn script vào HTML của Togglz console để form lật flag gửi tham số
 * qua QUERY STRING với thân RỖNG.
 *
 * <p><b>Không có lớp này, nút bật/tắt trên console KHÔNG BAO GIỜ hoạt động trên
 * AWS</b> — dù nó chạy hoàn hảo ở local, và dù mọi test đều xanh. Đó là toàn bộ
 * lý do nó tồn tại.
 *
 * <p>CloudFront OAC ký request bằng SigV4 nhưng KHÔNG tự băm body: nó lấy payload
 * hash từ header `x-amz-content-sha256` do CLIENT gửi. Một form HTML thì trình
 * duyệt submit thẳng, không thêm header nào được, nên request chết ở Function URL
 * TRƯỚC khi ứng dụng thấy gì. Đo trên dev 2026-08-19:
 *
 * <pre>
 *   POST /admin/togglz/edit  CÓ body, không header
 *     → 403 {"message":"The request signature we calculated does not match…"}  (AWS)
 *   POST /admin/togglz/edit?f=…&amp;enabled=…&amp;_csrf=…  KHÔNG body
 *     → 403 {"timestamp":…,"path":"/admin/togglz/edit"}                        (Spring)
 * </pre>
 *
 * <p>Vế thứ hai là lời giải: không body thì không có gì để băm, và cả
 * `CsrfFilter` của Spring lẫn `EditPageHandler` của Togglz đều đọc bằng
 * `request.getParameter`, thứ lấy tham số từ CẢ query string. Cách còn lại — tự
 * băm body rồi gửi `x-amz-content-sha256` như SPA đang làm cho
 * `PUT /api/preferences/sources` — cũng chạy được, nhưng phải viết lại đoạn băm
 * lần thứ hai ở một nơi không dùng chung được bundle của SPA.
 *
 * <p><b>Chèn vào HTML của thư viện là việc chẳng đặng đừng</b>, và nó có ngưỡng
 * hỏng rõ ràng: `template.html` của Togglz phải còn thẻ `&lt;/body&gt;`. Nếu
 * bản Togglz sau đổi template, script không được chèn và nút bấm im lặng ngừng
 * hoạt động trên AWS — nên `AdminConsoleIT#console_mang_theo_script_vi...` canh
 * chính điều đó.
 *
 * <p>Nửa còn lại của cơ chế nằm ở {@link SpringCsrfTokenProvider}: nó đưa token
 * `_csrf` vào form, còn lớp này đưa form lên đường đi được. Thiếu một trong hai,
 * lượt lật flag trả 403 — chỉ khác là 403 của Spring hay của AWS.
 */
@Component
@Profile(RoleProfiles.ADMIN)
class ConsoleFormRewriteFilter extends OncePerRequestFilter {

	private static final RequestMatcher CONSOLE =
			PathPatternRequestMatcher.pathPattern("/admin/togglz/**");

	private final String publicBaseUrl;

	ConsoleFormRewriteFilter(
			@Value("${news.identity.public-base-url}") String publicBaseUrl) {
		this.publicBaseUrl = publicBaseUrl;
	}

	/**
	 * `capture: true` để chặn được submit TRƯỚC mọi handler khác, và
	 * `preventDefault()` để trình duyệt không gửi bản gốc có body.
	 *
	 * <p>`location.reload()` sau khi xong là bắt buộc: `fetch` không điều hướng,
	 * nên không có nó thì flag đã đổi mà trang vẫn hiện trạng thái cũ — người vận
	 * hành sẽ bấm lần nữa và lật ngược lại thứ mình vừa lật.
	 */
	private static final String SCRIPT = """
			<script>
			document.addEventListener('submit', function (e) {
			  var form = e.target;
			  if (!form || String(form.method).toLowerCase() !== 'post') { return; }
			  e.preventDefault();
			  var query = new URLSearchParams(new FormData(form)).toString();
			  var url = form.action + (form.action.indexOf('?') < 0 ? '?' : '&') + query;
			  fetch(url, { method: 'POST', credentials: 'same-origin' })
			    .then(function () { location.reload(); });
			}, true);
			</script>
			""";

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !CONSOLE.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		BufferingResponse buffered =
				new BufferingResponse(response, request, publicBaseUrl);
		chain.doFilter(request, buffered);

		if (buffered.daChuyenHuong()) {
			// Không có thân để chèn script, và response đã commit — chạm vào nữa
			// chỉ tạo một `IllegalStateException` ở chỗ không ai đọc.
			return;
		}

		byte[] body = buffered.body();
		String contentType = buffered.getContentType();
		if (contentType != null && contentType.startsWith("text/html")) {
			String html = new String(body, StandardCharsets.UTF_8);
			// `replace` một lần ở thẻ ĐÓNG cuối, không phải regex: template chỉ có
			// đúng một `</body>` và một biểu thức phức tạp hơn ở đây chỉ thêm chỗ
			// để sai.
			if (html.contains("</body>")) {
				html = html.replace("</body>", SCRIPT + "</body>");
			}
			body = html.getBytes(StandardCharsets.UTF_8);
		}

		// Đặt LẠI content length: thân đã dài ra, và giá trị cũ do console đặt
		// (nếu có) sẽ cắt cụt trang ở đúng chỗ script bắt đầu.
		response.setContentLength(body.length);
		response.getOutputStream().write(body);
	}

	/**
	 * Giữ toàn bộ thân response trong bộ nhớ để sửa được trước khi gửi đi.
	 *
	 * <p><b>`flushBuffer()` phải là no-op.</b> `RequestHandlerBase.writeResponse`
	 * của Togglz gọi nó ngay sau khi ghi xong, và bản mặc định của
	 * `HttpServletResponseWrapper` uỷ quyền thẳng xuống response THẬT — tức
	 * response bị COMMIT trước khi lớp này kịp sửa gì.
	 *
	 * <p>Hậu quả đo được bằng mutation, và nó nhỏ hơn vẻ ngoài: thân VẪN đi ra
	 * đúng (stream còn mở, script vẫn được chèn), nhưng `setContentLength` sau
	 * commit bị BỎ QUA LẶNG LẼ và trang đi ra dạng chunked, không `Content-Length`
	 * nào. Đó là lý do `AdminConsoleIT` khẳng định `Content-Length` khớp thân ĐÃ
	 * chèn — không có vế ấy thì dòng no-op này là code không ai canh.
	 *
	 * <p>Trang console lớn nhất chỉ vài chục KB (bảng flag lấy từ enum
	 * `NewsFeature`), nên giữ cả thân trong bộ nhớ không phải rủi ro. Nếu một
	 * ngày console phục vụ file lớn thì `shouldNotFilter` là chỗ để loại nó ra.
	 */
	private static final class BufferingResponse extends HttpServletResponseWrapper {

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final HttpServletRequest request;
		private final String publicBaseUrl;
		private ServletOutputStream stream;
		private PrintWriter writer;
		private boolean daChuyenHuong;

		private BufferingResponse(HttpServletResponse response,
				HttpServletRequest request, String publicBaseUrl) {
			super(response);
			this.request = request;
			this.publicBaseUrl = publicBaseUrl;
		}

		boolean daChuyenHuong() {
			return daChuyenHuong;
		}

		/**
		 * Biến `Location` TƯƠNG ĐỐI của console thành TUYỆT ĐỐI theo
		 * `public-base-url`.
		 *
		 * <p><b>Không có lớp này thì người vận hành ĐÚNG nhóm `ops` vẫn không vào
		 * được console.</b> ĐÃ ĐO trên dev 2026-08-19:
		 *
		 * <pre>
		 *   GET https://news.na-dev.linhvu.dev/admin/togglz
		 *     → 302 https://mmwu…lyvoh.lambda-url.us-east-1.on.aws/admin/togglz/index
		 *     → Forbidden
		 * </pre>
		 *
		 * <p>Console gọi `sendRedirect` với đường dẫn tương đối
		 * (`InitialRedirectHandler` dùng `getRequestURI() + "/index"`,
		 * `EditPageHandler` dùng thẳng `"index"`), và servlet container biến nó
		 * thành tuyệt đối bằng `Host` của REQUEST — mà sau CloudFront, `Host` đó
		 * là Function URL với `AuthType=AWS_IAM`. Đăng nhập đúng, nhóm đúng, phân
		 * quyền đúng, và trình duyệt vẫn nhận Forbidden.
		 *
		 * <p>Đây là cùng một cái bẫy mà `AuthController.login()` và
		 * `SecurityConfig.defaultSuccessUrl` đã tránh bằng cách tự dựng URL tuyệt
		 * đối. Khác biệt duy nhất: redirect này do THƯ VIỆN phát, nên chặn ở
		 * response là chỗ duy nhất với tới được.
		 *
		 * <p>`URI.resolve` chứ không nối chuỗi: nó xử lý đúng CẢ HAI dạng theo
		 * luật RFC 3986 — `/admin/togglz/index` (tuyệt đối trong host) và `index`
		 * (tương đối với thư mục của request hiện tại). Nối chuỗi sẽ biến dạng thứ
		 * hai thành `/index`, tức 404.
		 */
		@Override
		public void sendRedirect(String location) throws IOException {
			this.daChuyenHuong = true;
			super.sendRedirect(tuyetDoi(location));
		}

		@Override
		public void sendRedirect(String location, int sc, boolean clearBuffer)
				throws IOException {
			this.daChuyenHuong = true;
			super.sendRedirect(tuyetDoi(location), sc, clearBuffer);
		}

		private String tuyetDoi(String location) {
			if (location.startsWith("http://") || location.startsWith("https://")) {
				return location;
			}
			return URI.create(publicBaseUrl + request.getRequestURI())
					.resolve(location).toString();
		}

		byte[] body() throws IOException {
			if (writer != null) {
				writer.flush();
			}
			else if (stream != null) {
				stream.flush();
			}
			return buffer.toByteArray();
		}

		@Override
		public ServletOutputStream getOutputStream() {
			if (stream == null) {
				stream = new ServletOutputStream() {
					@Override
					public void write(int b) {
						buffer.write(b);
					}

					@Override
					public boolean isReady() {
						return true;
					}

					@Override
					public void setWriteListener(WriteListener listener) {
						throw new UnsupportedOperationException(
								"console của Togglz ghi đồng bộ, không dùng IO bất đồng bộ");
					}
				};
			}
			return stream;
		}

		@Override
		public PrintWriter getWriter() {
			if (writer == null) {
				writer = new PrintWriter(
						new java.io.OutputStreamWriter(buffer, StandardCharsets.UTF_8), true);
			}
			return writer;
		}

		@Override
		public void flushBuffer() {
			// CỐ Ý không uỷ quyền — xem Javadoc của lớp.
		}

		/**
		 * Nuốt luôn `setContentLength`: giá trị console đặt là độ dài thân GỐC,
		 * còn thân gửi đi dài hơn vì có script. Để nó đi xuống thì trang bị cắt
		 * cụt đúng tại chỗ script bắt đầu — một lỗi trông như HTML hỏng ngẫu nhiên.
		 */
		@Override
		public void setContentLength(int len) {
			// no-op
		}

		@Override
		public void setContentLengthLong(long len) {
			// no-op
		}
	}
}
