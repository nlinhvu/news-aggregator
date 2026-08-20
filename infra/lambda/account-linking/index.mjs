/**
 * Entry point của trigger `InboundFederation_ExternalProvider`.
 *
 * File này CỐ Ý mỏng: nó chỉ nối AWS SDK vào logic ở `linking.mjs`. Toàn bộ
 * quyết định nằm bên kia, và bên kia test được bằng `node --test` mà không cần
 * `node_modules`.
 *
 * Import ở TẦNG MODULE chứ không phải trong handler: Lambda nạp module trong
 * pha init, pha đó có ngân sách riêng và KHÔNG tính vào 5 giây mà Cognito cho
 * trigger. Đẩy `import()` vào trong handler là tự lấy ngân sách đăng nhập trả
 * cho việc nạp SDK.
 *
 * Runtime `nodejs24.x` cấp sẵn AWS SDK v3, nên không có `package.json` và không
 * có bundler. Đánh đổi đã biết: AWS khuyến nghị tự đóng gói SDK client để khỏi
 * phụ thuộc bản minor của runtime. Ở đây ba API dùng tới đều ổn định, và cái giá
 * của việc tự đóng gói là một toolchain JS thứ hai trong repo infra — đúng thứ
 * ADR-0021 tính vào chi phí và cố ý không mua.
 */
import {
	AdminCreateUserCommand,
	AdminLinkProviderForUserCommand,
	CognitoIdentityProviderClient,
	ListUsersCommand,
} from '@aws-sdk/client-cognito-identity-provider';

import { linkAccount } from './linking.mjs';

// Ngoài handler: client tái dùng qua các lượt invoke, và việc dựng nó rơi vào
// pha init thay vì vào ngân sách 5 giây.
const client = new CognitoIdentityProviderClient({});

const cognito = {
	// KHÔNG đặt `Limit`: mặc định trả tới 60 user, thừa sức cho một email. Đặt
	// một con số nhỏ là mua chế độ hỏng "tài khoản gốc rơi sang trang sau" —
	// handler sẽ tưởng không có user native và tạo thêm một cái nữa.
	listUsers: (userPoolId, filter) => client.send(new ListUsersCommand({
		UserPoolId: userPoolId,
		Filter: filter,
	})),

	adminCreateUser: (userPoolId, email) => client.send(new AdminCreateUserCommand({
		UserPoolId: userPoolId,
		// Pool dùng `UsernameAttributes = [email]`, nên truyền email vào đây và
		// Cognito tự sinh username dạng UUID. Username thật đọc lại từ response.
		Username: email,
		// Không `TemporaryPassword`: pool có passwordless factor nên Cognito tạo
		// user KHÔNG mật khẩu. Đây là thứ giữ lời hứa của ADR-0017.
		MessageAction: 'SUPPRESS',
		UserAttributes: [
			{ Name: 'email', Value: email },
			{ Name: 'email_verified', Value: 'true' },
		],
	})),

	adminLinkProviderForUser: (userPoolId, username, providerName, providerUserId) =>
		client.send(new AdminLinkProviderForUserCommand({
			UserPoolId: userPoolId,
			DestinationUser: {
				ProviderName: 'Cognito',
				ProviderAttributeValue: username,
			},
			SourceUser: {
				ProviderName: providerName,
				// Tên BẮT BUỘC cho social IdP — Cognito tự đọc `sub` của Google
				// và `id` của Facebook từ token.
				ProviderAttributeName: 'Cognito_Subject',
				ProviderAttributeValue: providerUserId,
			},
		})),
};

export const handler = (event) => linkAccount(event, cognito);
