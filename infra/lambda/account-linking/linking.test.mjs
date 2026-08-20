import { test } from 'node:test';
import assert from 'node:assert/strict';

import { linkAccount } from './linking.mjs';

const SILENT = { info() {}, warn() {}, error() {} };

/**
 * Logger ghi lại, để phân biệt hai đường ra TRÔNG GIỐNG NHAU từ bên ngoài:
 * "bỏ qua có chủ ý" (warn, không API nào được gọi) và "nuốt một exception"
 * (error, cũng không API nào được gọi). Chỉ đếm lượt gọi API thì hai đường này
 * không phân biệt được, và test sẽ xanh cả khi guard bị xoá.
 */
function recordingLog() {
	return { infos: [], warns: [], errors: [],
		info(...a) { this.infos.push(a); },
		warn(...a) { this.warns.push(a); },
		error(...a) { this.errors.push(a); } };
}

/**
 * Client giả ghi lại mọi lượt gọi. `throwOn` bơm lỗi vào đúng một API — cách
 * duy nhất kiểm được lời hứa "lỗi SDK thì KHÔNG chặn đăng nhập".
 */
function fakeCognito({ users = [], createdUsername = 'uuid-moi', throwOn = null } = {}) {
	const calls = [];
	const guard = (name) => {
		calls.push(name);
		if (throwOn === name) {
			throw new Error('lỗi SDK giả lập: ' + name);
		}
	};
	return {
		calls,
		args: {},
		async listUsers(userPoolId, filter) {
			guard('listUsers');
			this.args.listUsers = { userPoolId, filter };
			return { Users: users };
		},
		async adminCreateUser(userPoolId, email) {
			guard('adminCreateUser');
			this.args.adminCreateUser = { userPoolId, email };
			return { User: { Username: createdUsername } };
		},
		async adminLinkProviderForUser(userPoolId, username, providerName, providerUserId) {
			guard('adminLinkProviderForUser');
			this.args.adminLinkProviderForUser =
				{ userPoolId, username, providerName, providerUserId };
		},
	};
}

function googleSignIn(email = 'nlinhvu.dev@gmail.com') {
	return {
		userPoolId: 'us-east-1_PdskR1W0U',
		userName: '104556631362906539049',
		request: {
			providerName: 'Google',
			providerType: 'Google',
			attributes: { idToken: { email }, userInfo: { email } },
		},
		response: { userAttributesToMap: {} },
	};
}

const nativeUser = {
	Username: '84c8a478-70d1-7080-f843-b5afa82deae8',
	UserStatus: 'CONFIRMED',
	Attributes: [{ Name: 'email', Value: 'nlinhvu.dev@gmail.com' }],
};

function federatedUser(providerName, userId) {
	return {
		Username: `${providerName}_${userId}`,
		UserStatus: 'EXTERNAL_PROVIDER',
		Attributes: [
			{ Name: 'email', Value: 'nlinhvu.dev@gmail.com' },
			{ Name: 'identities', Value: JSON.stringify([{ providerName, userId }]) },
		],
	};
}

test('chọn user NATIVE làm gốc khi ListUsers trả cả hai loại', async () => {
	// Chọn nhầm profile federated làm gốc là vẫn tách tài khoản, chỉ khác chỗ
	// tách. Thứ tự mảng cố ý để federated ĐỨNG TRƯỚC — `find` lấy phần tử đầu
	// khớp, nên một bản cài chỉ lấy `Users[0]` sẽ đỏ ở đây.
	const cognito = fakeCognito({
		users: [federatedUser('Facebook', '122135814387161914'), nativeUser],
	});

	await linkAccount(googleSignIn(), cognito, SILENT);

	assert.deepEqual(cognito.calls, ['listUsers', 'adminLinkProviderForUser']);
	assert.equal(cognito.args.adminLinkProviderForUser.username, nativeUser.Username);
	assert.equal(cognito.args.adminLinkProviderForUser.providerName, 'Google');
	assert.equal(cognito.args.adminLinkProviderForUser.providerUserId,
		'104556631362906539049');
});

test('không có user native thì TẠO rồi liên kết vào chính user vừa tạo', async () => {
	const cognito = fakeCognito({ users: [], createdUsername: 'uuid-vua-tao' });

	await linkAccount(googleSignIn(), cognito, SILENT);

	assert.deepEqual(cognito.calls,
		['listUsers', 'adminCreateUser', 'adminLinkProviderForUser']);
	assert.equal(cognito.args.adminCreateUser.email, 'nlinhvu.dev@gmail.com');
	assert.equal(cognito.args.adminLinkProviderForUser.username, 'uuid-vua-tao');
});

test('danh tính đã liên kết thì KHÔNG gọi lại AdminLinkProviderForUser', async () => {
	// Trigger chạy ở MỌI lượt federated sign-in, không chỉ lượt đầu. Thiếu nhánh
	// này thì mỗi lần đăng nhập là một lượt gọi thừa và một dòng log rác.
	const daLienKet = {
		...nativeUser,
		Attributes: [
			...nativeUser.Attributes,
			{
				Name: 'identities',
				Value: JSON.stringify([
					{ providerName: 'Google', userId: '104556631362906539049' },
				]),
			},
		],
	};
	const cognito = fakeCognito({ users: [daLienKet] });

	await linkAccount(googleSignIn(), cognito, SILENT);

	assert.deepEqual(cognito.calls, ['listUsers']);
});

test('danh tính đã có profile riêng thì KHÔNG tạo user native mồ côi', async () => {
	// Trạng thái pool dev trước Step 4: `Google_104556…` đã đăng nhập một lần nên
	// có profile riêng. `AdminLinkProviderForUser` đòi SourceUser CHƯA tồn tại,
	// nên lượt link chắc chắn hỏng — mà nếu `AdminCreateUser` chạy trước thì ta
	// để lại một tài khoản native không bao giờ được liên kết.
	const cognito = fakeCognito({
		users: [federatedUser('Google', '104556631362906539049')],
	});

	await linkAccount(googleSignIn(), cognito, SILENT);

	assert.deepEqual(cognito.calls, ['listUsers']);
});

test('lỗi SDK thì trả event, KHÔNG ném', async () => {
	// Trigger nằm đồng bộ trong đường đăng nhập: ném lỗi ở đây là chặn một người
	// dùng hợp lệ không vào được hệ thống.
	for (const api of ['listUsers', 'adminCreateUser', 'adminLinkProviderForUser']) {
		const event = googleSignIn();
		const cognito = fakeCognito({ throwOn: api });

		const result = await linkAccount(event, cognito, SILENT);

		assert.equal(result, event, `lỗi ở ${api} vẫn phải trả event nguyên trạng`);
		assert.deepEqual(result.response.userAttributesToMap, {},
			'userAttributesToMap phải là {} — trả object thiếu attribute là XOÁ'
			+ ' attribute đó khỏi profile');
	}
});

test('không có email ở cả idToken lẫn userInfo thì bỏ qua, không gọi API nào', async () => {
	// Tài khoản Facebook đăng ký bằng số điện thoại (ADR-0021 §7).
	const event = googleSignIn();
	event.request.attributes = { idToken: {}, userInfo: {} };
	const cognito = fakeCognito();
	const log = recordingLog();

	const result = await linkAccount(event, cognito, log);

	assert.equal(result, event);
	assert.deepEqual(cognito.calls, []);
	// Hai dòng dưới là thứ làm test này có giá trị. Không có chúng, bỏ guard
	// `!email` vẫn XANH: filter dựng từ `undefined` ném TypeError, `catch` nuốt
	// nó, và không API nào được gọi — y hệt đường đúng. Đã đo bằng mutation.
	assert.deepEqual(log.errors, [],
		'phải là đường bỏ qua có chủ ý, không phải một exception bị nuốt');
	assert.equal(log.warns.length, 1, 'và phải ghi đúng một dòng warn');
});

test('email chỉ có ở userInfo (Facebook không phát id_token) vẫn liên kết được', async () => {
	const event = googleSignIn();
	event.request.providerName = 'Facebook';
	event.request.attributes = { userInfo: { email: 'nlinhvu.dev@gmail.com' } };
	const cognito = fakeCognito({ users: [nativeUser] });

	await linkAccount(event, cognito, SILENT);

	assert.equal(cognito.args.listUsers.filter, 'email = "nlinhvu.dev@gmail.com"');
	assert.equal(cognito.args.adminLinkProviderForUser.providerName, 'Facebook');
});
