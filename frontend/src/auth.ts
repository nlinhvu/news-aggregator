import { useEffect, useState } from 'react'

export type CurrentUser = { sub: string; email?: string; groups: string[] }

// 401 KHÔNG phải lỗi ở đây — nó là câu trả lời hợp lệ cho "tôi là ai": ẩn danh.
// Ném exception ở 401 sẽ biến trạng thái BÌNH THƯỜNG NHẤT của site thành một
// error boundary.
export async function fetchCurrentUser(): Promise<CurrentUser | null> {
  const res = await fetch('/api/me')
  if (res.status === 401) return null
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

// Cookie XSRF-TOKEN cố ý KHÔNG httpOnly để đọc được ở đây; cookie phiên thì
// ngược lại. Đó là toàn bộ khác biệt giữa hai cookie, và nhầm chiều là mất
// hoặc CSRF protection hoặc mô hình BFF.
function csrfToken(): string | undefined {
  const raw = document.cookie
    .split('; ')
    .find(c => c.startsWith('XSRF-TOKEN='))
    ?.split('=')[1]
  return raw ? decodeURIComponent(raw) : undefined
}

// Chưa có caller nào hôm nay: đường ghi duy nhất của SPA là đăng xuất, mà nó
// đi bằng form (xem `logout`). Giữ lại vì đây là bề mặt TDD §7 công bố cho mọi
// lời gọi POST/PUT/DELETE bằng `fetch` về sau, và vì nó dùng CHUNG `csrfToken()`
// với `logout` nên hai chỗ không thể trôi khỏi nhau.
export function csrfHeader(): Record<string, string> {
  const token = csrfToken()
  return token ? { 'X-XSRF-TOKEN': token } : {}
}

// Điều hướng THẬT, không phải `fetch`. Luồng đăng nhập là một chuỗi redirect
// xuyên site; `fetch` sẽ đi theo redirect trong nền và không bao giờ hiển thị
// trang đăng nhập của Cognito.
export function login(): void {
  window.location.href = '/api/auth/login'
}

// POST KHÔNG BODY, token đi trong header — rồi mới điều hướng.
//
// Bản đầu dùng form POST với field `_csrf`. Nó CHẾT trên AWS và chết trước khi
// tới ứng dụng: CloudFront OAC không ký request body khi origin là Lambda
// Function URL, nên mọi request mang body đều trượt chữ ký SigV4 và nhận
// `The request signature we calculated does not match…`. Form luôn có body;
// `fetch` không body thì không.
//
// Hệ quả kéo theo: `fetch` không đọc được `Location` của redirect, nên endpoint
// trả URL đăng xuất trong THÂN response và ta tự điều hướng. Phải là điều hướng
// thật — trang đăng xuất của Cognito nằm khác origin.
export async function logout(): Promise<void> {
  const res = await fetch('/api/auth/logout', {
    method: 'POST',
    headers: csrfHeader(),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const { logoutUrl } = (await res.json()) as { logoutUrl: string }
  window.location.href = logoutUrl
}

/**
 * `loading` tách RIÊNG khỏi `user`, không gộp vào một sentinel `undefined`.
 *
 * Hai trạng thái này phải phân biệt được, nếu không header sẽ nhấp nháy nút
 * "Đăng nhập" một nhịp rồi mới đổi thành email — với người đã đăng nhập thì đó
 * trông như vừa bị đăng xuất.
 */
export function useCurrentUser(): { user: CurrentUser | null; loading: boolean } {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let huy = false
    fetchCurrentUser()
      // Lỗi mạng ⇒ coi như ẩn danh. Trang tin phải đọc được kể cả khi đường
      // danh tính hỏng — đó là driver #3 của ADR-0018.
      .catch(() => null)
      .then(u => {
        if (!huy) {
          setUser(u)
          setLoading(false)
        }
      })
    return () => {
      huy = true
    }
  }, [])

  return { user, loading }
}
