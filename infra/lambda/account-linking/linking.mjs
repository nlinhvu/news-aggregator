/**
 * Liên kết một danh tính từ IdP ngoài vào tài khoản native cùng email.
 *
 * Vì sao có file này, tách khỏi `index.mjs`: `index.mjs` import AWS SDK ở tầng
 * module, và SDK không có trong `node_modules` của repo (runtime `nodejs24.x`
 * cấp sẵn). Logic nằm riêng ở đây thì `node --test` chạy được mà không cần cài
 * gì — không `package.json`, không bundler, không toolchain thứ hai.
 *
 * Hợp đồng với `cognito`: ba hàm async, KHÔNG phải client của SDK. Đây là chỗ
 * nối duy nhất giữa logic và SDK, và nó tồn tại để test không phải dựng
 * command object của SDK v3.
 */

const EXTERNAL_PROVIDER = 'EXTERNAL_PROVIDER';

/**
 * Cognito trả `identities` dưới dạng CHUỖI JSON trong mảng attribute, không
 * phải object. Parse hỏng thì coi như chưa liên kết — thà gọi thừa một lượt
 * link còn hơn ném lỗi trong đường đăng nhập.
 */
function identitiesOf(user) {
	const raw = (user.Attributes ?? [])
		.find((attribute) => attribute.Name === 'identities')?.Value;
	if (!raw) {
		return [];
	}
	try {
		const parsed = JSON.parse(raw);
		return Array.isArray(parsed) ? parsed : [];
	}
	catch {
		return [];
	}
}

function hasIdentity(user, providerName, userId) {
	return identitiesOf(user).some((identity) =>
		identity.providerName === providerName
		&& String(identity.userId) === String(userId));
}

/**
 * Facebook KHÔNG phát id_token (nó là OAuth2, không phải OIDC), nên email của
 * nó tới từ `userInfo`. Google thì có cả hai. Đọc `idToken` trước vì claim ở đó
 * đã được Cognito verify chữ ký.
 */
function emailOf(request) {
	const attributes = request?.attributes ?? {};
	const email = attributes.idToken?.email ?? attributes.userInfo?.email;
	return typeof email === 'string' ? email.trim() : undefined;
}

/**
 * `Filter` của `ListUsers` là cú pháp chuỗi có dấu nháy kép bao quanh giá trị,
 * nên một email chứa `"` hoặc `\` làm hỏng câu filter. Không IdP thật nào trả
 * email như vậy; nếu có thì bỏ qua còn hơn gửi đi một filter méo.
 */
function isFilterSafe(email) {
	return !email.includes('"') && !email.includes('\\');
}

export async function linkAccount(event, cognito, log = console) {
	// MỌI đường ra đều trả `event` NGUYÊN TRẠNG, kể cả đường lỗi. Trigger này
	// chạy ĐỒNG BỘ trong đường đăng nhập: ném lỗi ở đây là chặn một người dùng
	// hợp lệ không vào được, còn `return event` chỉ là không liên kết được lần
	// này. Thà có hai tài khoản còn hơn không vào được (ADR-0021 §5).
	//
	// KHÔNG đụng vào `event.response.userAttributesToMap`. Trả `{}` là no-op giữ
	// nguyên mọi attribute của IdP; trả một object thiếu attribute nào là XOÁ
	// attribute đó khỏi profile — kể cả `email`, thứ pool khai là bắt buộc.
	try {
		const { userPoolId, userName, request } = event;
		const providerName = request?.providerName;
		const email = emailOf(request);

		if (!email) {
			// Tài khoản Facebook đăng ký bằng số điện thoại, hoặc Hide My Email
			// của Apple — không có khoá để gộp (ADR-0021 §7).
			log.warn('[account-linking] không có email, bỏ qua', { providerName });
			return event;
		}
		if (!userPoolId || !userName || !providerName) {
			log.warn('[account-linking] event thiếu trường bắt buộc, bỏ qua');
			return event;
		}
		if (!isFilterSafe(email)) {
			log.warn('[account-linking] email không dùng được trong filter, bỏ qua');
			return event;
		}

		const found = await cognito.listUsers(userPoolId, `email = "${email}"`);
		const users = found?.Users ?? [];

		// MỘT phép kiểm, HAI chế độ hỏng khác nhau:
		//
		//   - user native đã mang sẵn danh tính này ⇒ lượt đăng nhập thứ N, không
		//     có gì để làm. Không có nhánh này thì mỗi lượt đăng nhập gọi thừa
		//     một `AdminLinkProviderForUser`.
		//   - danh tính này ĐÃ có profile riêng ⇒ không liên kết được nữa.
		//     `AdminLinkProviderForUser` đòi `SourceUser` là danh tính CHƯA từng
		//     đăng nhập; gọi vào đây chỉ ném lỗi, và tệ hơn là bước
		//     `AdminCreateUser` phía dưới đã kịp tạo một tài khoản native mồ côi.
		//     Đường sửa là xoá profile lệch rồi đăng nhập lại (ADR-0021 §5).
		const linked = users.find((user) => hasIdentity(user, providerName, userName));
		if (linked) {
			if (linked.UserStatus === EXTERNAL_PROVIDER) {
				log.warn('[account-linking] danh tính đã có profile riêng, không'
					+ ' liên kết được — xoá profile rồi đăng nhập lại',
					{ providerName, username: linked.Username });
			}
			return event;
		}

		// Tài khoản native làm GỐC, và đó là cả quyết định của ADR-0021: token
		// luôn mang `sub` của nó dù vào bằng đường nào, và vì nó là user native
		// của một pool bật EMAIL_OTP nên người vào bằng social sau này gõ email
		// vẫn nhận được mã.
		let username = users.find((user) => user.UserStatus !== EXTERNAL_PROVIDER)
			?.Username;

		if (!username) {
			// Pool bật `AllowAdminCreateUserOnly` nên đây là API DUY NHẤT tạo
			// được user. Không `TemporaryPassword`: pool có passwordless factor
			// nên Cognito tạo user KHÔNG mật khẩu thay vì sinh mật khẩu tạm.
			//
			// `SUPPRESS` vì người dùng đang ở giữa luồng đăng nhập — một email
			// "chào mừng, đây là mật khẩu tạm của bạn" ở đúng giây này vừa vô
			// nghĩa vừa đáng ngờ.
			//
			// `email_verified: true` vì Google và Facebook đều xác minh email
			// trước khi trả về. Provider thứ ba phải được đánh giá lại trước khi
			// bật — liên kết theo email là TIN VÀO IdP (ADR-0021 §5).
			const created = await cognito.adminCreateUser(userPoolId, email);
			username = created?.User?.Username;

			if (!username) {
				log.error('[account-linking] AdminCreateUser không trả Username');
				return event;
			}
		}

		// `Cognito_Subject` là tên bắt buộc cho social IdP — Cognito tự đọc
		// `sub`/`id` từ token của Google/Facebook. `ProviderAttributeValue` của
		// đích là USERNAME của tài khoản gốc (pool dùng email làm alias nên
		// username là UUID Cognito sinh), không phải email.
		await cognito.adminLinkProviderForUser(userPoolId, username, providerName, userName);

		log.info('[account-linking] đã liên kết', { providerName, username });
		return event;
	}
	catch (error) {
		log.error('[account-linking] lỗi, bỏ qua liên kết để không chặn đăng nhập',
			error);
		return event;
	}
}
