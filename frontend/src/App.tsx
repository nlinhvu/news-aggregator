import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import {
  HttpError,
  fetchArticles,
  fetchMyFeed,
  fetchSelectedSources,
  fetchSources,
  saveSelectedSources,
  type ArticlePage,
  type ArticleSummary,
  type SourceOption,
} from './api'
import { login, logout, useCurrentUser } from './auth'

type Auth = ReturnType<typeof useCurrentUser>

// Gom 5 lần bấm trong một giây thành MỘT `PUT`. Không có nó thì mỗi chip là một
// lượt ghi DynamoDB và một lượt tải lại feed — xem walkthrough slice 4.
const DEBOUNCE_MS = 400

/**
 * Khung trang dùng CHUNG cho mọi trạng thái.
 *
 * Tồn tại vì `App` return sớm ở ba nhánh (lỗi, đang tải, rỗng). Gắn header vào
 * riêng nhánh cuối — như bản phác trong plan — sẽ làm nút "Đăng nhập" BIẾN MẤT
 * đúng lúc site chưa có bài nào, tức lúc người dùng đầu tiên ghé thăm.
 *
 * Nhận `auth` qua props chứ KHÔNG tự gọi `useCurrentUser()`: `App` cũng cần
 * biết ta là ai để chọn giữa `/api/articles` và `/api/my/feed`, và hai lần gọi
 * hook là hai lần gọi `/api/me` cho mỗi lượt tải trang.
 */
function Page({ auth, children }: { auth: Auth; children: ReactNode }) {
  const { user, loading, disabled } = auth

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', maxWidth: '48rem', margin: '3rem auto' }}>
      <header style={{ display: 'flex', justifyContent: 'space-between',
                       alignItems: 'baseline', marginBottom: '1.5rem' }}>
        <h1 style={{ margin: 0 }}>News Aggregator</h1>
        {/* Đang tải thì KHÔNG render gì: hiện nút "Đăng nhập" rồi đổi thành
            email một nhịp sau trông như vừa bị đăng xuất.

            `disabled` (flag `USER_ACCOUNTS` tắt) cũng không render gì — kể cả
            nút "Đăng nhập". `/api/auth/login` khi đó trả 404, nên cái nút ấy chỉ
            là lời mời vào chỗ trống. Phần còn lại của trang không đổi một chữ:
            tắt tính năng không được biến thành sự cố. */}
        {loading || disabled ? null : user ? (
          <span style={{ fontSize: '.9rem' }}>
            {user.email ?? user.sub} · <button onClick={logout}>Đăng xuất</button>
          </span>
        ) : (
          <button onClick={login}>Đăng nhập</button>
        )}
      </header>
      {children}
    </main>
  )
}

/**
 * Hàng chip — BA trạng thái, và trạng thái ẩn danh không phải là "ẩn đi".
 *
 * | Ai | Thấy gì |
 * |---|---|
 * | Ẩn danh | chip mờ, không bấm được, kèm lời mời đăng nhập |
 * | Đã đăng nhập, chưa chọn gì | TẤT CẢ chip đậm |
 * | Đã đăng nhập, đã chọn | chỉ chip đã chọn đậm |
 *
 * Chip mờ chính là lời mời đăng nhập, nên nó phải hiện ra cho người chưa đăng
 * nhập — đó là lý do `GET /api/sources` công khai.
 *
 * `USER_ACCOUNTS` tắt là trạng thái THỨ TƯ và nó không có hàng chip: lọc theo
 * nguồn không tồn tại, `/api/preferences/**` trả 404, và một hàng chip mờ mời
 * đăng nhập trong khi không có chỗ nào để đăng nhập là lời mời vào chỗ trống —
 * cùng lý do header giấu nút "Đăng nhập".
 */
function SourceChips({ sources, enabled, canToggle, showLoginHint, onToggle }: {
  sources: SourceOption[]
  enabled: string[]
  canToggle: boolean
  showLoginHint: boolean
  onToggle: (sourceId: string) => void
}) {
  // Chưa tải xong, hoặc `/api/sources` hỏng: không có gì để bấm nên không vẽ
  // khung rỗng.
  if (sources.length === 0) return null

  return (
    <section style={{ marginBottom: '1.5rem' }} aria-label="Lọc theo nguồn">
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '.5rem',
                    opacity: canToggle ? 1 : .45 }}>
        {sources.map(s => {
          const on = enabled.includes(s.sourceId)
          return (
            <button
              key={s.sourceId}
              onClick={() => onToggle(s.sourceId)}
              disabled={!canToggle}
              // Trạng thái bật/tắt của chip phải đọc được bằng máy, không chỉ
              // bằng màu — walkthrough kiểm "chip đậm/mờ" và trình đọc màn hình
              // cũng cần đúng thông tin đó.
              aria-pressed={on}
              style={{
                padding: '.3rem .8rem',
                borderRadius: '999px',
                cursor: canToggle ? 'pointer' : 'default',
                border: '1px solid #333',
                background: on ? '#333' : 'transparent',
                color: on ? '#fff' : '#333',
                fontSize: '.85rem',
              }}
            >
              {s.name}
            </button>
          )
        })}
      </div>
      {/* Chỉ mời khi CHẮC CHẮN là người ẩn danh. Lúc `/api/me` chưa trả lời,
          câu này nhấp nháy đúng một nhịp trước mắt người đã đăng nhập. */}
      {showLoginHint && (
        <p style={{ fontSize: '.8rem', opacity: .7, margin: '.5rem 0 0' }}>
          Đăng nhập để lọc theo nguồn
        </p>
      )}
    </section>
  )
}

/**
 * Đáy danh sách — BỐN trạng thái, và cả bốn dùng chung MỘT chỗ trên màn hình.
 *
 * | trạng thái | thấy gì | observer |
 * |---|---|---|
 * | còn bài | nút "Tải thêm" | đang theo dõi |
 * | đang tải | nút mờ, "Đang tải…" | bị cờ chặn |
 * | hỏng | nút "Thử lại" + câu giải thích | NGỪNG tự bấm |
 * | hết bài, ĐÃ từng cuộn | chữ "Đã hết bài." | đã gỡ |
 * | hết bài, CHƯA từng cuộn | không gì cả | đã gỡ |
 *
 * Hai dòng cuối khác nhau ở `hasScrolled`, và khác biệt đó không phải chi tiết
 * trang trí. Kho chỉ có 15 bài thì trang đầu đã hết bài ngay, và tuyên bố "Đã hết bài"
 * dưới một danh sách vừa đủ một màn hình là nói một điều không ai hỏi —
 * walkthrough slice 1 ghi rõ: *"trang trông y hệt trước Phase 11"*. Nó còn phủ
 * luôn cửa sổ giữa push 1 và push 2, khi API cũ làm `nextCursor` luôn `null`.
 *
 * Nút là `<button>` THẬT chứ không phải một sentinel `<div>` vô hình: người dùng
 * bàn phím phải Tab tới được, và một lượt tải hỏng phải có chỗ để bấm lại.
 * `IntersectionObserver` chỉ là lớp tiện lợi chồng lên trên — bỏ nó đi thì tính
 * năng vẫn dùng được, chỉ là phải bấm tay.
 *
 * Nhánh `error` NGỪNG tự bấm là chốt chặn quan trọng nhất ở đây: thiếu nó, một
 * lỗi bền vững (mạng chết, 503 kéo dài) biến thành vòng lặp tải-hỏng-tải-lại chạy
 * mãi trong lúc nút vẫn nằm trong khung nhìn.
 */
function ListFooter({ hasMore, hasScrolled, loading, error, onLoad }: {
  hasMore: boolean
  hasScrolled: boolean
  loading: boolean
  error: string | null
  onLoad: () => void
}) {
  const buttonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!hasMore || loading || error) return
    const el = buttonRef.current
    if (!el) return
    // `rootMargin` 400px: bắt đầu tải TRƯỚC khi người đọc chạm đáy. Không có nó
    // thì họ nhìn thấy "Đang tải…" mỗi 20 bài, và cảm giác là cuộn bị khựng chứ
    // không phải cuộn vô hạn.
    const observer = new IntersectionObserver(
      ([e]) => { if (e.isIntersecting) onLoad() },
      { rootMargin: '400px' })
    observer.observe(el)
    return () => observer.disconnect()
  }, [hasMore, loading, error, onLoad])

  if (!hasMore) {
    return hasScrolled ? (
      <p style={{ textAlign: 'center', opacity: .6, fontSize: '.85rem',
                  margin: '2rem 0' }}>
        Đã hết bài.
      </p>
    ) : null
  }

  return (
    <div style={{ textAlign: 'center', margin: '1.5rem 0' }}>
      {error && (
        <p role="status" style={{ fontSize: '.85rem', color: '#a33' }}>{error}</p>
      )}
      <button
        ref={buttonRef}
        onClick={onLoad}
        disabled={loading}
        // Trạng thái phải đọc được bằng máy, không chỉ bằng việc nút mờ đi —
        // cùng nguyên tắc mà `aria-pressed` trên hàng chip đã áp dụng.
        aria-busy={loading}
        style={{ padding: '.5rem 1.4rem', borderRadius: '999px',
                 border: '1px solid #333', background: 'transparent',
                 cursor: loading ? 'default' : 'pointer' }}
      >
        {loading ? 'Đang tải…' : error ? 'Thử lại' : 'Tải thêm'}
      </button>
    </div>
  )
}

export default function App() {
  const auth = useCurrentUser()
  const [sources, setSources] = useState<SourceOption[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [articles, setArticles] = useState<ArticleSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [updating, setUpdating] = useState(false)
  // `null` = HẾT BÀI. Trạng thái ban đầu cũng là `null` vì lúc đó chưa tải gì —
  // và đúng lúc đó `articles` cũng là `null`, nên đáy trang không render.
  const [cursor, setCursor] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null)
  // Trình đọc màn hình không tự biết danh sách vừa dài ra. Thiếu dòng thông báo
  // này thì với họ việc bấm nút KHÔNG CÓ HIỆU QUẢ GÌ.
  const [loadAnnouncement, setLoadAnnouncement] = useState('')
  // Đã nối được ít nhất một trang. Quyết định có nói "Đã hết bài" hay im lặng —
  // xem javadoc của `ListFooter`.
  const [hasScrolled, setHasScrolled] = useState(false)

  // Lựa chọn mà SERVER đã xác nhận. Cập nhật optimistic ghi đè `selected` ngay
  // lúc bấm, nên nếu `PUT` hỏng thì đây là chỗ duy nhất còn giữ sự thật.
  const confirmed = useRef<string[]>([])
  const debounce = useRef<number | undefined>(undefined)

  // Danh sách nguồn KHÔNG chờ `/api/me`: nó công khai và hàng chip phải hiện ra
  // cho cả người ẩn danh. Chạy song song nghĩa là khi `USER_ACCOUNTS` tắt, kết
  // quả này không ai dùng — một request thừa cho một trạng thái kill switch,
  // đổi lấy việc hàng chip không phải xếp hàng sau `/api/me` ở trạng thái
  // thường ngày.
  //
  // Hỏng thì NUỐT: mất hàng chip đã đủ tệ, biến một tính năng phụ thành trang
  // lỗi thì là hồi quy đường đọc chính.
  useEffect(() => {
    fetchSources().then(setSources).catch(() => setSources([]))
  }, [])

  // Rời trang giữa lúc đang chờ debounce: huỷ lượt ghi, đừng để nó chạy vào
  // khoảng không.
  useEffect(() => () => window.clearTimeout(debounce.current), [])

  // Feed thì NGƯỢC LẠI: phải đợi `/api/me` trả lời mới biết gọi endpoint nào.
  // Gọi `/api/articles` trước rồi đổi sang `/api/my/feed` là hai lượt tải cho
  // mỗi người đã đăng nhập, kèm một nhịp hiện feed chưa lọc.
  useEffect(() => {
    if (auth.loading) return

    let cancelled = false
    const initial: Promise<[ArticlePage, string[]]> = auth.user
      ? Promise.all([fetchMyFeed(), fetchSelectedSources()])
      : Promise.all([fetchArticles(), Promise.resolve<string[]>([])])

    initial.then(([page, selectedIds]) => {
      if (cancelled) return
      confirmed.current = selectedIds
      setSelected(selectedIds)
      setArticles(page.items)
      setCursor(page.nextCursor)
    }).catch(e => {
      if (!cancelled) setError(describeError(e))
    })

    return () => {
      cancelled = true
    }
  }, [auth.loading, auth.user])

  const allSourceIds = sources.map(s => s.sourceId)
  // Rỗng = TẤT CẢ, nên hàng chip cũng phải hiện đậm hết — người vừa đăng nhập
  // lần đầu thấy đúng feed lúc ẩn danh, không phải một hàng chip tắt ngóm.
  //
  // Lọc theo `allSourceIds` vì lựa chọn cũ có thể còn id của nguồn vừa bị tắt:
  // chip của nó không còn để bấm, và gửi lại id đó sẽ nhận `400`.
  const enabled = selected.length === 0
    ? allSourceIds
    : allSourceIds.filter(id => selected.includes(id))

  function toggleChip(sourceId: string) {
    const next = enabled.includes(sourceId)
      ? enabled.filter(id => id !== sourceId)
      : [...enabled, sourceId]
    // Chọn HẾT lưu thành rỗng: cùng nghĩa với backend, nhưng dạng rỗng còn đúng
    // cả về sau — thêm nguồn mới thì người này thấy ngay, thay vì bị đóng băng ở
    // danh sách của hôm nay.
    const updated = next.length === allSourceIds.length ? [] : next

    setSelected(updated)          // optimistic: màn hình đổi ngay
    setNotice(null)
    window.clearTimeout(debounce.current)
    debounce.current = window.setTimeout(() => void save(updated), DEBOUNCE_MS)
  }

  /**
   * `useCallback` không phải để tối ưu: `onLoad` là dependency của effect dựng
   * observer, nên một hàm mới mỗi lần render sẽ tháo và dựng lại observer liên
   * tục.
   */
  const loadMore = useCallback(async () => {
    if (cursor === null || loadingMore) return
    setLoadingMore(true)
    setLoadMoreError(null)
    try {
      const page = auth.user ? await fetchMyFeed(cursor) : await fetchArticles(cursor)
      // NỐI, không thay thế — vị trí cuộn của người đọc không được nhảy.
      setArticles(prev => [...(prev ?? []), ...page.items])
      setCursor(page.nextCursor)
      setHasScrolled(true)
      setLoadAnnouncement(`Đã tải thêm ${page.items.length} bài`)
    }
    catch (e) {
      // KHÔNG gỡ phần đã tải. Người đọc mất mạng ở bài thứ 60 thì vẫn còn 60 bài
      // để đọc; xoá sạch chúng là biến một lỗi tải thành một lỗi mất dữ liệu.
      //
      // `401` có câu chữ RIÊNG: phiên hết hạn giữa lúc cuộn là chuyện thường
      // (TTL 30 ngày trượt), và "Không tải được danh sách bài viết: HTTP 401"
      // không nói cho người đọc biết họ cần làm gì. Phân biệt bằng
      // `HttpError.status`, không bằng so chuỗi — xem javadoc của `HttpError`.
      setLoadMoreError(e instanceof HttpError && e.status === 401
        ? 'Phiên đã hết hạn. Đăng nhập lại để xem tiếp.'
        : describeError(e))
    }
    finally {
      setLoadingMore(false)
    }
  }, [cursor, loadingMore, auth.user])

  async function save(selectedIds: string[]) {
    try {
      await saveSelectedSources(selectedIds)
    }
    catch (e) {
      // HOÀN TÁC. Để chip ở trạng thái vừa bấm sau một lượt ghi hỏng là màn
      // hình nói dối về thứ đã lưu — và nó chỉ lộ ra ở lần tải trang sau.
      setSelected(confirmed.current)
      setNotice(e instanceof HttpError && e.status === 401
        ? 'Phiên đã hết hạn. Đăng nhập lại để lưu lựa chọn.'
        : 'Không lưu được lựa chọn. Thử lại sau.')
      return
    }

    confirmed.current = selectedIds
    setUpdating(true)
    try {
      const page = await fetchMyFeed()
      setArticles(page.items)
      // Cursor cũ KHÔNG hỏng khi tập nguồn đổi, nhưng giữ nó lại sẽ hiện ra như
      // một danh sách bắt đầu từ giữa. Về đầu là hành vi đúng (TDD §5.4).
      setCursor(page.nextCursor)
      setLoadMoreError(null)
      setHasScrolled(false)
      window.scrollTo({ top: 0 })
    }
    catch (e) {
      // Lựa chọn ĐÃ lưu xong: không hoàn tác chip, chỉ nói feed chưa tải lại
      // được. Hoàn tác ở đây sẽ hiện một trạng thái trái với thứ đang nằm trong
      // bảng.
      setNotice(describeError(e))
    }
    finally {
      setUpdating(false)
    }
  }

  if (error) {
    return (
      <Page auth={auth}>
        <p>{error}</p>
        <button onClick={() => location.reload()}>Thử lại</button>
      </Page>
    )
  }

  if (articles === null) return <Page auth={auth}><p>Đang tải…</p></Page>

  const chipRow = (
    <SourceChips
      sources={sources}
      enabled={enabled}
      // `USER_ACCOUNTS` tắt ⇒ không hàng chip; chưa biết ta là ai ⇒ chưa bấm được.
      canToggle={!!auth.user}
      showLoginHint={!auth.loading && !auth.user && !auth.disabled}
      onToggle={toggleChip}
    />
  )

  return (
    <Page auth={auth}>
      {!auth.disabled && chipRow}
      {notice && (
        <p role="status" style={{ fontSize: '.85rem', color: '#a33' }}>{notice}</p>
      )}
      {/* Trạng thái rỗng phải có CHỮ — không phải trang trắng, không phải
          spinner quay mãi (walkthrough slice 2, mục Edge cases). Câu chữ khác
          nhau theo lý do rỗng: "chưa có bài nào" và "nguồn bạn chọn chưa có
          bài" dẫn tới hai hành động khác nhau. */}
      {articles.length === 0 ? (
        <p>{enabled.length < allSourceIds.length
          ? 'Chưa có bài nào từ nguồn đã chọn.'
          : 'Chưa có bài viết nào.'}</p>
      ) : (
        <>
          <ul style={{ listStyle: 'none', padding: 0, opacity: updating ? .5 : 1 }}>
            {articles.map(a => (
              <li key={a.id} style={{ marginBottom: '1.5rem' }}>
                {/* Luôn dẫn về bài gốc — master §3.2 và §8.4 */}
                <a href={a.canonicalUrl} target="_blank" rel="noopener noreferrer">
                  <strong>{a.title}</strong>
                </a>
                <div style={{ fontSize: '.85rem', opacity: .7 }}>
                  {a.sourceName} · {new Date(a.publishedAt).toLocaleDateString('vi-VN')}
                </div>
                {a.summary && <p style={{ marginTop: '.4rem' }}>{a.summary}</p>}
              </li>
            ))}
          </ul>
          {/* Vùng chỉ dành cho trình đọc màn hình. Ẩn bằng clip chứ KHÔNG bằng
              `display:none` — cái sau làm trình đọc bỏ qua luôn. */}
          <p role="status" style={{ position: 'absolute', width: 1, height: 1,
                                    overflow: 'hidden', clip: 'rect(0 0 0 0)',
                                    whiteSpace: 'nowrap' }}>
            {loadAnnouncement}
          </p>
          <ListFooter
            hasMore={cursor !== null}
            hasScrolled={hasScrolled}
            loading={loadingMore}
            error={loadMoreError}
            onLoad={loadMore}
          />
        </>
      )}
    </Page>
  )
}

/**
 * `503` là lỗi TẠM THỜI của một phụ thuộc (fan-out theo nguồn hỏng), không phải
 * site hỏng — xem `MyFeedController#catalogUnavailable`. Dịch nó thành "HTTP 503" là
 * ném đúng cái mã ấy vào mặt người đọc mà không nói họ nên làm gì.
 */
function describeError(e: unknown): string {
  if (e instanceof HttpError && e.status === 503) {
    return 'Nguồn tin tạm thời không đọc được. Thử lại sau ít phút.'
  }
  return `Không tải được danh sách bài viết: ${e instanceof Error ? e.message : String(e)}`
}
