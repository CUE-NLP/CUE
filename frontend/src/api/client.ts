export type ApiResponse<T> = { result: 'SUCCESS' | 'FAIL'; code: string; message: string; data?: T }
export type Tokens = { accessToken: string; refreshToken: string; tokenType: string }
const tokenKey = 'cue.auth'
export const authStore = {
  get: (): Tokens | null => {
    try {
      return JSON.parse(sessionStorage.getItem(tokenKey) || 'null') as Tokens | null
    } catch {
      return null
    }
  },
  set: (tokens: Tokens) => sessionStorage.setItem(tokenKey, JSON.stringify(tokens)),
  clear: () => sessionStorage.removeItem(tokenKey),
}
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status: number,
  ) {
    super(message)
  }
}
let refreshInFlight: Promise<Tokens> | null = null

async function send<T>(path: string, method: string, body?: unknown, token?: string): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let payload: ApiResponse<T>
  try {
    payload = (await response.json()) as ApiResponse<T>
  } catch {
    throw new ApiError(
      'NETWORK',
      '서버 응답을 읽지 못했습니다. 백엔드 실행 상태를 확인해 주세요.',
      response.status,
    )
  }
  if (!response.ok || payload.result === 'FAIL')
    throw new ApiError(
      payload.code || 'UNKNOWN',
      payload.message || '요청에 실패했습니다.',
      response.status,
    )
  return payload.data as T
}
export async function request<T>(
  path: string,
  method = 'GET',
  body?: unknown,
  requiresAuth = true,
): Promise<T> {
  const tokens = authStore.get()
  if (requiresAuth && !tokens) throw new ApiError('A006', '로그인이 필요합니다.', 401)
  try {
    return await send<T>(path, method, body, requiresAuth ? tokens?.accessToken : undefined)
  } catch (error) {
    if (
      !(error instanceof ApiError) ||
      error.code !== 'A004' ||
      !requiresAuth ||
      !tokens?.refreshToken
    )
      throw error
    if (!refreshInFlight)
      refreshInFlight = send<Tokens>('/api/auth/refresh', 'POST', {
        refreshToken: tokens.refreshToken,
      })
        .then((next) => {
          authStore.set(next)
          return next
        })
        .catch((err) => {
          authStore.clear()
          throw err
        })
        .finally(() => {
          refreshInFlight = null
        })
    const next = await refreshInFlight
    return send<T>(path, method, body, next.accessToken)
  }
}
