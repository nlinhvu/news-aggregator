package dev.linhvu.news_aggregator.platform;

import org.togglz.core.Feature;
import org.togglz.core.annotation.Label;
import org.togglz.core.context.FeatureContext;

/**
 * `project-requirements.md` #4 yêu cầu đích danh chuẩn bị sẵn flag cho các
 * feature tương lai, nên khai báo cả enum ở Phase 1 KHÔNG phải YAGNI — nó
 * là yêu cầu. Tất cả mặc định OFF.
 */
public enum NewsFeature implements Feature {

	@Label("Hiển thị bản tóm tắt do AI sinh")
	AI_SUMMARIZATION,

	@Label("Lấy full text khi RSS chỉ có excerpt (Phase 5)")
	WEB_SCRAPING,

	@Label("Phân loại và gắn tag tự động (Phase 6)")
	AUTO_CATEGORIZATION,

	@Label("Đăng nhập và tài khoản người dùng (Phase 7)")
	USER_ACCOUNTS,

	@Label("Dịch tiêu đề và tóm tắt (Phase 8)")
	SMART_TRANSLATION,

	@Label("Chatbot hỏi đáp trên kho article (Phase 9)")
	CHATBOT;

	public boolean isActive() {
		return FeatureContext.getFeatureManager().isActive(this);
	}
}
