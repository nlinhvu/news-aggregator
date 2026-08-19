package dev.linhvu.news_aggregator.identity;

import jakarta.servlet.http.HttpServletRequest;

import org.togglz.servlet.spi.CSRFToken;
import org.togglz.servlet.spi.CSRFTokenProvider;

import org.springframework.security.web.csrf.CsrfToken;

/**
 * Đưa CSRF token của Spring Security vào form mà Togglz console tự render.
 *
 * <p><b>Vì sao cần.</b> Console là HTML của một thư viện — ta không sửa được
 * template của nó. Form lật flag POST về `/admin/togglz/edit`, và `CsrfFilter`
 * của Spring Security nằm TRƯỚC servlet ấy, nên nếu form không mang token của
 * Spring thì mọi lượt lật flag trả 403. Không cấu hình nào của Togglz sửa được
 * điều đó: token của nó tên `togglz_csrf`, còn Spring hỏi `_csrf`.
 *
 * <p><b>Vì sao KHÔNG loại `/admin/**` khỏi CSRF thay vì viết class này.</b> Đó
 * là lời giải đầu tiên nghĩ ra và nó tệ hơn theo hai cách. Một: nó bỏ hẳn bảo
 * vệ CSRF cho đúng cái endpoint có quyền mạnh nhất trong hệ. Hai: thứ còn lại
 * để thay thế — `TogglzCSRFTokenCache` — là một map STATIC trong JVM với TTL 10
 * phút, nên trên Lambda nó hỏng ngắt quãng: token phát ra ở execution
 * environment này không có trong cache của environment kia. Token của Spring
 * nằm trong cookie `XSRF-TOKEN`, không phụ thuộc instance.
 *
 * <p><b>Được nạp bằng `ServiceLoader`, KHÔNG phải bean Spring.</b> Đăng ký ở
 * `META-INF/services/org.togglz.servlet.spi.CSRFTokenProvider`. Nghĩa là:
 * constructor phải `public` và không tham số, class phải `public`, và không có
 * gì được inject vào đây — mọi thứ phải lấy từ `request`. Xoá file services đó
 * đi thì class này im lặng không bao giờ chạy.
 *
 * <p>`ServiceLoader` gộp mọi file cùng tên trên classpath, nên provider của
 * chính Togglz vẫn còn và form sẽ mang CẢ HAI hidden field. Vô hại: Togglz
 * không kiểm token của nó nữa (`togglz.console.validate-csrf-token: false`),
 * còn Spring chỉ đọc field của mình.
 */
public class SpringCsrfTokenProvider implements CSRFTokenProvider {

	/**
	 * Trả `null` khi không có token, và `null` là hợp đồng của SPI này —
	 * `IndexPageHandler` bỏ qua provider trả null thay vì render một field rỗng.
	 *
	 * <p>Token nằm ở attribute mang TÊN CLASS chứ không phải `_csrf`:
	 * `SecurityConfig` gọi `setCsrfRequestAttributeName(null)` để tắt cơ chế nạp
	 * lười, và với `null` thì `CsrfTokenRequestAttributeHandler` chỉ đặt attribute
	 * mặc định này. Đọc theo `"_csrf"` sẽ luôn ra `null` — form thiếu field, mọi
	 * lượt lật flag 403, và không có lỗi nào chỉ về đây.
	 */
	@Override
	public CSRFToken getToken(HttpServletRequest request) {
		CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		if (token == null) {
			return null;
		}
		// `getToken()` chứ không `getParameterName()` một mình: giá trị là một
		// `Supplier` lười và chính lời gọi này mới sinh ra nó cùng cookie.
		return new CSRFToken(token.getParameterName(), token.getToken());
	}
}
