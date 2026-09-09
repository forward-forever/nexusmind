export interface ApiErrorBody {
  timestamp?: string
  status?: number
  code?: string
  message?: string
  path?: string
}

const configuredBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').trim()
const apiBaseUrl = configuredBaseUrl.endsWith('/')
  ? configuredBaseUrl.slice(0, -1)
  : configuredBaseUrl

export function apiUrl(path: string): string {
  return `${apiBaseUrl}${path}`
}

export class ApiRequestError extends Error {
  readonly status: number
  readonly code: string | null

  constructor(status: number, message: string, code: string | null = null) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = code
  }
}

export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (init?.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(apiUrl(path), { ...init, headers })
  if (!response.ok) {
    throw await toApiRequestError(response)
  }
  return (await response.json()) as T
}

export async function toApiRequestError(response: Response): Promise<ApiRequestError> {
  let body: ApiErrorBody | null = null
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // The server may return an empty or non-JSON proxy error.
  }

  return new ApiRequestError(
    response.status,
    body?.message?.trim() || `Request failed with HTTP ${response.status}`,
    body?.code ?? null,
  )
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}
