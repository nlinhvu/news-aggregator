import { csrfHeader } from './auth'

export type ArticleSummary = {
  id: string
  title: string
  publishedAt: string
  canonicalUrl: string
  sourceName: string
  summary?: string   // vắng mặt khi AI_SUMMARIZATION tắt
}

/** Hai trường, đúng bằng `GET /api/sources` — xem `SourceOptionDto`. */
export type SourceOption = {
  sourceId: string
  name: string
}

/**
 * Mã trạng thái phải ĐI KÈM lỗi, không nằm trong câu chữ.
 *
 * SPA phản ứng khác nhau với `401` (phiên hết hạn ⇒ hoàn tác lựa chọn) và
 * `503` (fan-out hỏng ⇒ mời thử lại). Phân biệt hai cái đó bằng cách so chuỗi
 * `"HTTP 401"` là thứ hỏng im lặng ngay lần đầu ai đó sửa câu thông báo.
 */
export class HttpError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`HTTP ${status}`)
    this.status = status
  }
}

// Đường dẫn TƯƠNG ĐỐI. Vì ADR-0005 chọn same-origin, SPA không có API URL
// riêng theo môi trường — CÙNG MỘT bundle chạy được ở cả ba môi trường.
async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(path)
  if (!res.ok) throw new HttpError(res.status)
  return res.json() as Promise<T>
}

export function fetchArticles(limit = 20): Promise<ArticleSummary[]> {
  return getJson<ArticleSummary[]>(`/api/articles?limit=${limit}`)
}

/**
 * Cùng hình dạng với `/api/articles` (TDD §7) nên chỉ khác đúng URL — kể cả
 * việc `summary` vắng mặt khi `AI_SUMMARIZATION` tắt.
 */
export function fetchMyFeed(limit = 20): Promise<ArticleSummary[]> {
  return getJson<ArticleSummary[]>(`/api/my/feed?limit=${limit}`)
}

/** CÔNG KHAI: hàng chip phải render được (dạng mờ) trước khi biết ta là ai. */
export function fetchSources(): Promise<SourceOption[]> {
  return getJson<SourceOption[]>('/api/sources')
}

export async function fetchSelectedSources(): Promise<string[]> {
  const { sourceIds } = await getJson<{ sourceIds: string[] }>('/api/preferences/sources')
  // Rỗng = TẤT CẢ nguồn, không phải "không nguồn nào" — cùng quy ước với
  // backend (`SourcePreferences.getSourceIds`).
  return sourceIds ?? []
}

export async function saveSelectedSources(sourceIds: string[]): Promise<void> {
  await sendJson('PUT', '/api/preferences/sources', { sourceIds })
}

/**
 * MỌI request MANG BODY của SPA đi qua đây — không phải để cho gọn, mà vì
 * `x-amz-content-sha256`.
 *
 * CloudFront OAC ký request bằng SigV4 nhưng KHÔNG tự băm body; nó lấy payload
 * hash từ header này do client gửi lên. Thiếu nó, `PUT` nhận 403 của AWS và
 * KHÔNG BAO GIỜ tới ứng dụng — còn local thì không qua CloudFront nên vẫn xanh.
 * Đó là lý do chỗ tính hash phải là MỘT: chỗ nào quên thì chỉ AWS mới lộ.
 *
 * `body` được tính MỘT lần rồi vừa băm vừa gửi. Băm object rồi `JSON.stringify`
 * lại lần nữa ở chỗ khác là hai chuỗi khác nhau — cùng triệu chứng 403 chỉ trên
 * AWS.
 */
async function sendJson(method: string, path: string, payload: unknown): Promise<Response> {
  const body = JSON.stringify(payload)
  const res = await fetch(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'x-amz-content-sha256': await sha256Hex(body),
      ...csrfHeader(),
    },
    body,
  })
  if (!res.ok) throw new HttpError(res.status)
  return res
}

async function sha256Hex(body: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(body))
  return [...new Uint8Array(digest)]
    .map(b => b.toString(16).padStart(2, '0'))
    .join('')
}
