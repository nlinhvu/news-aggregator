export type ArticleSummary = {
  id: string
  title: string
  publishedAt: string
  canonicalUrl: string
  sourceName: string
  summary?: string   // vắng mặt khi AI_SUMMARIZATION tắt
}

// Đường dẫn TƯƠNG ĐỐI. Vì ADR-0005 chọn same-origin, SPA không có API URL
// riêng theo môi trường — CÙNG MỘT bundle chạy được ở cả ba môi trường.
export async function fetchArticles(limit = 20): Promise<ArticleSummary[]> {
  const res = await fetch(`/api/articles?limit=${limit}`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}
