/**
 * Module `identity` — trả lời câu hỏi "anh là ai".
 *
 * Sở hữu bảng `sessions`. KHÔNG sở hữu email hay hồ sơ người dùng — cả hai nằm
 * trong Cognito, và bảng của module này chỉ khoá theo `sub` (master §8.4, sửa
 * 2026-08-13).
 *
 * `allowedDependencies = {}` như `platform`: module này chỉ chạm AWS SDK và
 * Spring, không một type nào của module khác. Khai tường minh chứ không bỏ
 * trống — bỏ trống là "chưa ai nghĩ tới", `{}` là một khẳng định.
 */
@ApplicationModule(displayName = "identity", allowedDependencies = {})
package dev.linhvu.news_aggregator.identity;

import org.springframework.modulith.ApplicationModule;
