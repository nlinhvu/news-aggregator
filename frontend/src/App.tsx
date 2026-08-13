import { useEffect, useState, type ReactNode } from 'react'
import { fetchArticles, type ArticleSummary } from './api'
import { login, logout, useCurrentUser } from './auth'

/**
 * Khung trang dùng CHUNG cho mọi trạng thái.
 *
 * Tồn tại vì `App` return sớm ở ba nhánh (lỗi, đang tải, rỗng). Gắn header vào
 * riêng nhánh cuối — như bản phác trong plan — sẽ làm nút "Đăng nhập" BIẾN MẤT
 * đúng lúc site chưa có bài nào, tức lúc người dùng đầu tiên ghé thăm.
 */
function Page({ children }: { children: ReactNode }) {
  const { user, loading, disabled } = useCurrentUser()

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

export default function App() {
  const [articles, setArticles] = useState<ArticleSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchArticles().then(setArticles).catch(e => setError(e.message))
  }, [])

  if (error) {
    return (
      <Page>
        <p>Không tải được danh sách bài viết: {error}</p>
        <button onClick={() => location.reload()}>Thử lại</button>
      </Page>
    )
  }

  if (articles === null) return <Page><p>Đang tải…</p></Page>

  // Trạng thái rỗng phải có CHỮ — không phải trang trắng, không phải
  // spinner quay mãi (walkthrough slice 2, mục Edge cases).
  if (articles.length === 0) {
    return <Page><p>Chưa có bài viết nào.</p></Page>
  }

  return (
    <Page>
      <ul style={{ listStyle: 'none', padding: 0 }}>
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
    </Page>
  )
}
