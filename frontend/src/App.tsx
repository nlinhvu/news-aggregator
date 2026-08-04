import { useEffect, useState } from 'react'
import { fetchArticles, type ArticleSummary } from './api'

export default function App() {
  const [articles, setArticles] = useState<ArticleSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchArticles().then(setArticles).catch(e => setError(e.message))
  }, [])

  if (error) {
    return (
      <main>
        <h1>News Aggregator</h1>
        <p>Không tải được danh sách bài viết: {error}</p>
        <button onClick={() => location.reload()}>Thử lại</button>
      </main>
    )
  }

  if (articles === null) return <main><h1>News Aggregator</h1><p>Đang tải…</p></main>

  // Trạng thái rỗng phải có CHỮ — không phải trang trắng, không phải
  // spinner quay mãi (walkthrough slice 2, mục Edge cases).
  if (articles.length === 0) {
    return <main><h1>News Aggregator</h1><p>Chưa có bài viết nào.</p></main>
  }

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', maxWidth: '48rem', margin: '3rem auto' }}>
      <h1>News Aggregator</h1>
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
    </main>
  )
}
