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
 * Envelope của hai endpoint danh sách.
 *
 * `nextCursor === null` là tín hiệu DUY NHẤT của "hết bài". KHÔNG suy ra từ
 * `items.length < limit`: đường fan-out lọc lại ở tầng ứng dụng nên một trang
 * còn đầy dữ liệu vẫn có thể trả ít hơn `limit`.
 */
export type ArticlePage = {
  items: ArticleSummary[]
  nextCursor: string | null
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

/**
 * NHÁNH TƯƠNG THÍCH — sống đúng một chu kỳ deploy, xoá ở Task 9.
 *
 * `web-deploy` (sync S3) luôn về đích trước `app-deploy` (dựng image rồi promote
 * qua ba môi trường), nên bundle này chắc chắn có lúc chạy trước API mới. Không
 * có nhánh dưới đây thì `d.items` là `undefined` và trang chủ thành trang trắng.
 *
 * API cũ trả mảng trần ⇒ coi như một trang duy nhất, không có trang sau.
 */
function asPage(d: ArticleSummary[] | ArticlePage): ArticlePage {
  return Array.isArray(d) ? { items: d, nextCursor: null } : d
}

// `URLSearchParams` chứ không nối chuỗi: cursor là base64url nên `-` và `_` an
// toàn, nhưng nối tay là chỗ mà lần sau ai đó thêm một tham số có `&` sẽ hỏng.
function buildPath(base: string, cursor?: string, limit = 20): string {
  const q = new URLSearchParams({ limit: String(limit) })
  if (cursor) q.set('cursor', cursor)
  return `${base}?${q}`
}

export async function fetchArticles(cursor?: string, limit = 20): Promise<ArticlePage> {
  return asPage(await getJson<ArticleSummary[] | ArticlePage>(
    buildPath('/api/articles', cursor, limit)))
}

/**
 * Cùng hình dạng với `/api/articles` (TDD §7) nên chỉ khác đúng URL — kể cả
 * việc `summary` vắng mặt khi `AI_SUMMARIZATION` tắt, và kể cả hợp đồng cursor.
 */
export async function fetchMyFeed(cursor?: string, limit = 20): Promise<ArticlePage> {
  return asPage(await getJson<ArticleSummary[] | ArticlePage>(
    buildPath('/api/my/feed', cursor, limit)))
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
