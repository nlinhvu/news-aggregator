/**
 * Module `identity` — trả lời câu hỏi "anh là ai".
 *
 * Sở hữu bảng `sessions`. KHÔNG sở hữu email hay hồ sơ người dùng — cả hai nằm
 * trong Cognito, và bảng của module này chỉ khoá theo `sub` (master §8.4, sửa
 * 2026-08-13).
 *
 * `allowedDependencies = { "platform" }`: Task 9 khai `{}` vì lúc đó module chỉ
 * chạm AWS SDK và Spring. Task 10 chạm `RoleProfiles` để phân vai theo function,
 * nên cạnh sang `platform` là THẬT và phải khai — `ModuleBoundaryTest#validModule`
 * đỏ nếu không.
 */
@ApplicationModule(displayName = "identity", allowedDependencies = { "platform" })
package dev.linhvu.news_aggregator.identity;

import org.springframework.modulith.ApplicationModule;
