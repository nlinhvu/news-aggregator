package dev.linhvu.news_aggregator.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeedExcerptTest {

	@Test
	void bo_tag_html_va_giu_chu() {
		assertThat(FeedExcerpt.clean(
				"<p>Spring Boot 4.1 introduces <code>@ImportHttpServices</code>.</p>", 2000))
				.isEqualTo("Spring Boot 4.1 introduces @ImportHttpServices.");
	}

	@Test
	void gop_khoang_trang_va_bo_xuong_dong() {
		assertThat(FeedExcerpt.clean("Dòng một.\n\n   Dòng hai.\t\tDòng ba.", 2000))
				.isEqualTo("Dòng một. Dòng hai. Dòng ba.");
	}

	/**
	 * Feed dùng CDATA và entity lẫn lộn. `&amp;` phải thành `&`, không được để
	 * nguyên — model đọc `&amp;lt;` thành rác, và một excerpt đầy entity trông
	 * như code chứ không như văn xuôi.
	 */
	@Test
	void giai_ma_entity_co_ban() {
		assertThat(FeedExcerpt.clean(
				"A &amp; B &lt;tag&gt; &quot;q&quot; &#39;s&#39; &nbsp;end", 2000))
				.isEqualTo("A & B <tag> \"q\" 's' end");
	}

	/**
	 * `&amp;` phải giải mã CUỐI CÙNG. Giải mã trước thì `&amp;lt;` thành `&lt;`
	 * rồi thành `<` — hai lần, và feed viết `&amp;lt;` là đang muốn hiện ra chữ
	 * `&lt;` chứ không phải mở một tag.
	 */
	@Test
	void khong_giai_ma_entity_hai_lan() {
		assertThat(FeedExcerpt.clean("A &amp;lt;b&amp;gt; B", 2000))
				.isEqualTo("A &lt;b&gt; B");
	}

	/**
	 * Cắt tại RANH GIỚI TỪ. Cắt cứng làm từ cuối đứt đôi, và chỗ đó là cuối
	 * input — đúng nơi model chú ý nhất.
	 */
	@Test
	void cat_tai_ranh_gioi_tu() {
		String raw = "một hai ba bốn năm sáu bảy tám chín mười";

		assertThat(FeedExcerpt.clean(raw, 12)).isEqualTo("một hai ba");
	}

	/**
	 * Không có khoảng trắng nào trong `maxChars` ký tự đầu — thường là URL dài —
	 * thì cắt cứng. Nhánh này PHẢI trả chuỗi có nội dung: trả rỗng ở đây là lách
	 * qua được nhánh `isEmpty()` bên trên và phá vỡ hợp đồng "null chứ không
	 * chuỗi rỗng" mà `attribute_exists(excerpt)` của Task 13 dựa vào.
	 */
	@Test
	void cat_cung_khi_khong_co_khoang_trang_nao() {
		assertThat(FeedExcerpt.clean(
				"https://example.test/mot-duong-dan-rat-dai-khong-he-co-khoang-trang", 20))
				.isEqualTo("https://example.test");
	}

	@Test
	void khong_cat_khi_van_con_ngan_hon_tran() {
		assertThat(FeedExcerpt.clean("ngắn thôi", 2000)).isEqualTo("ngắn thôi");
	}

	/**
	 * Trả `null` chứ KHÔNG chuỗi rỗng, và khác biệt đó có hệ quả tận DynamoDB:
	 * `null` thì enhanced client không ghi attribute nào, còn chuỗi rỗng thì ghi.
	 * Sweep của Task 13 lọc bằng `attribute_exists(excerpt)` — với chuỗi rỗng nó
	 * sẽ nhặt về những bài không bao giờ tóm tắt được, mỗi lượt, mãi mãi.
	 */
	@Test
	void tra_null_khi_khong_dung_duoc() {
		assertThat(FeedExcerpt.clean(null, 2000)).isNull();
		assertThat(FeedExcerpt.clean("", 2000)).isNull();
		assertThat(FeedExcerpt.clean("   \n\t  ", 2000)).isNull();
		assertThat(FeedExcerpt.clean("<div><img src=\"x\"/></div>", 2000)).isNull();
	}

	/**
	 * `<script>` và `<style>` phải bị bỏ CẢ NỘI DUNG, không chỉ bỏ tag. Bỏ mỗi
	 * tag thì JavaScript nằm lại trong excerpt và model tóm tắt một đoạn code.
	 */
	@Test
	void bo_ca_noi_dung_cua_script_va_style() {
		assertThat(FeedExcerpt.clean(
				"Trước<script>var x = 1;</script>Sau<style>.a{color:red}</style>Cuối", 2000))
				.isEqualTo("TrướcSauCuối");
	}
}
