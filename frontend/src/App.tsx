import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  HttpError,
  fetchArticles,
  fetchMyFeed,
  fetchSelectedSources,
  fetchSources,
  saveSelectedSources,
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
function HangChip({ sources, dangBat, batDuoc, moiDangNhap, onToggle }: {
  sources: SourceOption[]
  dangBat: string[]
  batDuoc: boolean
  moiDangNhap: boolean
  onToggle: (sourceId: string) => void
}) {
  // Chưa tải xong, hoặc `/api/sources` hỏng: không có gì để bấm nên không vẽ
  // khung rỗng.
  if (sources.length === 0) return null

  return (
    <section style={{ marginBottom: '1.5rem' }} aria-label="Lọc theo nguồn">
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '.5rem',
                    opacity: batDuoc ? 1 : .45 }}>
        {sources.map(s => {
          const bat = dangBat.includes(s.sourceId)
          return (
            <button
              key={s.sourceId}
              onClick={() => onToggle(s.sourceId)}
              disabled={!batDuoc}
              // Trạng thái bật/tắt của chip phải đọc được bằng máy, không chỉ
              // bằng màu — walkthrough kiểm "chip đậm/mờ" và trình đọc màn hình
              // cũng cần đúng thông tin đó.
              aria-pressed={bat}
              style={{
                padding: '.3rem .8rem',
                borderRadius: '999px',
                cursor: batDuoc ? 'pointer' : 'default',
                border: '1px solid #333',
                background: bat ? '#333' : 'transparent',
                color: bat ? '#fff' : '#333',
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
      {moiDangNhap && (
        <p style={{ fontSize: '.8rem', opacity: .7, margin: '.5rem 0 0' }}>
          Đăng nhập để lọc theo nguồn
        </p>
      )}
    </section>
  )
}

export default function App() {
  const auth = useCurrentUser()
  const [sources, setSources] = useState<SourceOption[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [articles, setArticles] = useState<ArticleSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [thongBao, setThongBao] = useState<string | null>(null)
  const [dangCapNhat, setDangCapNhat] = useState(false)

  // Lựa chọn mà SERVER đã xác nhận. Cập nhật optimistic ghi đè `selected` ngay
  // lúc bấm, nên nếu `PUT` hỏng thì đây là chỗ duy nhất còn giữ sự thật.
  const daXacNhan = useRef<string[]>([])
  const hen = useRef<number | undefined>(undefined)

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
  useEffect(() => () => window.clearTimeout(hen.current), [])

  // Feed thì NGƯỢC LẠI: phải đợi `/api/me` trả lời mới biết gọi endpoint nào.
  // Gọi `/api/articles` trước rồi đổi sang `/api/my/feed` là hai lượt tải cho
  // mỗi người đã đăng nhập, kèm một nhịp hiện feed chưa lọc.
  useEffect(() => {
    if (auth.loading) return

    let huy = false
    const tai: Promise<[ArticleSummary[], string[]]> = auth.user
      ? Promise.all([fetchMyFeed(), fetchSelectedSources()])
      : Promise.all([fetchArticles(), Promise.resolve<string[]>([])])

    tai.then(([bai, chon]) => {
      if (huy) return
      daXacNhan.current = chon
      setSelected(chon)
      setArticles(bai)
    }).catch(e => {
      if (!huy) setError(moTaLoi(e))
    })

    return () => {
      huy = true
    }
  }, [auth.loading, auth.user])

  const idNguon = sources.map(s => s.sourceId)
  // Rỗng = TẤT CẢ, nên hàng chip cũng phải hiện đậm hết — người vừa đăng nhập
  // lần đầu thấy đúng feed lúc ẩn danh, không phải một hàng chip tắt ngóm.
  //
  // Lọc theo `idNguon` vì lựa chọn cũ có thể còn id của nguồn vừa bị tắt: chip
  // của nó không còn để bấm, và gửi lại id đó sẽ nhận `400`.
  const dangBat = selected.length === 0
    ? idNguon
    : idNguon.filter(id => selected.includes(id))

  function doiChip(sourceId: string) {
    const sau = dangBat.includes(sourceId)
      ? dangBat.filter(id => id !== sourceId)
      : [...dangBat, sourceId]
    // Chọn HẾT lưu thành rỗng: cùng nghĩa với backend, nhưng dạng rỗng còn đúng
    // cả về sau — thêm nguồn mới thì người này thấy ngay, thay vì bị đóng băng ở
    // danh sách của hôm nay.
    const moi = sau.length === idNguon.length ? [] : sau

    setSelected(moi)          // optimistic: màn hình đổi ngay
    setThongBao(null)
    window.clearTimeout(hen.current)
    hen.current = window.setTimeout(() => void luu(moi), DEBOUNCE_MS)
  }

  async function luu(chon: string[]) {
    try {
      await saveSelectedSources(chon)
    }
    catch (e) {
      // HOÀN TÁC. Để chip ở trạng thái vừa bấm sau một lượt ghi hỏng là màn
      // hình nói dối về thứ đã lưu — và nó chỉ lộ ra ở lần tải trang sau.
      setSelected(daXacNhan.current)
      setThongBao(e instanceof HttpError && e.status === 401
        ? 'Phiên đã hết hạn. Đăng nhập lại để lưu lựa chọn.'
        : 'Không lưu được lựa chọn. Thử lại sau.')
      return
    }

    daXacNhan.current = chon
    setDangCapNhat(true)
    try {
      setArticles(await fetchMyFeed())
    }
    catch (e) {
      // Lựa chọn ĐÃ lưu xong: không hoàn tác chip, chỉ nói feed chưa tải lại
      // được. Hoàn tác ở đây sẽ hiện một trạng thái trái với thứ đang nằm trong
      // bảng.
      setThongBao(moTaLoi(e))
    }
    finally {
      setDangCapNhat(false)
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

  const chip = (
    <HangChip
      sources={sources}
      dangBat={dangBat}
      // `USER_ACCOUNTS` tắt ⇒ không hàng chip; chưa biết ta là ai ⇒ chưa bấm được.
      batDuoc={!!auth.user}
      moiDangNhap={!auth.loading && !auth.user && !auth.disabled}
      onToggle={doiChip}
    />
  )

  return (
    <Page auth={auth}>
      {!auth.disabled && chip}
      {thongBao && (
        <p role="status" style={{ fontSize: '.85rem', color: '#a33' }}>{thongBao}</p>
      )}
      {/* Trạng thái rỗng phải có CHỮ — không phải trang trắng, không phải
          spinner quay mãi (walkthrough slice 2, mục Edge cases). Câu chữ khác
          nhau theo lý do rỗng: "chưa có bài nào" và "nguồn bạn chọn chưa có
          bài" dẫn tới hai hành động khác nhau. */}
      {articles.length === 0 ? (
        <p>{dangBat.length < idNguon.length
          ? 'Chưa có bài nào từ nguồn đã chọn.'
          : 'Chưa có bài viết nào.'}</p>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0, opacity: dangCapNhat ? .5 : 1 }}>
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
      )}
    </Page>
  )
}

/**
 * `503` là lỗi TẠM THỜI của một phụ thuộc (fan-out theo nguồn hỏng), không phải
 * site hỏng — xem `MyFeedController#catalogHong`. Dịch nó thành "HTTP 503" là
 * ném đúng cái mã ấy vào mặt người đọc mà không nói họ nên làm gì.
 */
function moTaLoi(e: unknown): string {
  if (e instanceof HttpError && e.status === 503) {
    return 'Nguồn tin tạm thời không đọc được. Thử lại sau ít phút.'
  }
  return `Không tải được danh sách bài viết: ${e instanceof Error ? e.message : String(e)}`
}
