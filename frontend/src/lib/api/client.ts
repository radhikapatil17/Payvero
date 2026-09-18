import { authChannel, withRefreshLock } from './authChannel'
import { tokens } from './tokens'
import type { AuthResponse, ErrorBody } from './types'

const AUTH_PREFIX = '/api/auth'
const REFRESH_PATH = `${AUTH_PREFIX}/refresh`

/**
 * Empty by default, which means same-origin: `npm run dev` proxies /api to the
 * backend, so the browser never makes a cross-origin call.
 *
 * That is convenient but it also means CORS is not exercised locally, and a
 * misconfiguration would first appear on a split-origin deployment. Setting
 * VITE_API_BASE_URL to the backend origin points the client straight at it,
 * which is how CORS gets tested deliberately rather than discovered.
 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

function url(path: string) {
  return `${BASE_URL}${path}`
}

/** Carries the server's error body so a form can map fieldErrors onto inputs. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: Record<string, string>

  constructor(status: number, body: Partial<ErrorBody> | null) {
    super(body?.message ?? 'Something went wrong')
    this.name = 'ApiError'
    this.status = status
    this.code = body?.error ?? 'UNKNOWN'
    this.fieldErrors = body?.fieldErrors ?? {}
  }

  get hasFieldErrors() {
    return Object.keys(this.fieldErrors).length > 0
  }
}

/**
 * The refresh currently in flight *in this tab*, shared by every caller that
 * needs one.
 *
 * This is a correctness requirement, not a performance nicety. A second
 * concurrent refresh would present the same token twice, which the API treats
 * as a replay and answers by revoking the entire session lineage. Ten requests
 * expiring together must therefore produce exactly one refresh call.
 */
let refreshInFlight: Promise<string | null> | null = null

async function performRefresh(): Promise<string | null> {
  const presentedToken = tokens.getRefreshToken()
  if (!presentedToken) return null

  return withRefreshLock(async () => {
    // Re-check after acquiring: another tab may have rotated while we queued,
    // in which case the token we were about to spend is already spent.
    const currentToken = tokens.getRefreshToken()
    if (currentToken && currentToken !== presentedToken) {
      return tokens.getAccessToken()
    }

    const response = await fetch(url(REFRESH_PATH), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: currentToken }),
    })

    if (!response.ok) {
      tokens.clear()
      authChannel.announceSignedOut()
      return null
    }

    const body = (await response.json()) as AuthResponse
    tokens.set(body.accessToken, body.refreshToken)
    authChannel.announceRefreshed(body.accessToken, body.refreshToken)
    return body.accessToken
  })
}

export function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = performRefresh().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

function send(path: string, init: RequestInit): Promise<Response> {
  const accessToken = tokens.getAccessToken()
  const headers = new Headers(init.headers)
  if (!headers.has('Content-Type') && init.body) {
    headers.set('Content-Type', 'application/json')
  }
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }
  return fetch(url(path), { ...init, headers })
}

/**
 * One 401 triggers at most one retry, and never for the auth endpoints
 * themselves — refreshing in response to a failed refresh would recurse.
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const response = await send(path, init)

  if (response.status !== 401 || path.startsWith(AUTH_PREFIX)) {
    return response
  }

  const renewedToken = await refreshAccessToken()
  if (!renewedToken) {
    return response
  }
  return send(path, init)
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await apiFetch(path, init)

  if (!response.ok) {
    throw new ApiError(response.status, await readErrorBody(response))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

async function readErrorBody(response: Response): Promise<Partial<ErrorBody> | null> {
  try {
    return (await response.json()) as ErrorBody
  } catch {
    // A proxy or gateway failure will not be shaped like our error body.
    return null
  }
}

/** Test seam: resets the module-level single-flight state between cases. */
export function __resetRefreshStateForTests() {
  refreshInFlight = null
}
