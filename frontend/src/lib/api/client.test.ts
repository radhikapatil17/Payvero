import { beforeEach, describe, expect, it, vi } from 'vitest'
import { __resetRefreshStateForTests, apiRequest, ApiError } from './client'
import { tokens } from './tokens'

/**
 * A fetch stub that counts calls per path, so a test can assert how many times
 * the refresh endpoint was hit rather than only that requests eventually
 * succeeded. That distinction is the whole point here: an implementation that
 * fires one refresh per 401 still "works", and still gets the user's session
 * revoked by the server for replaying a spent token.
 */
function stubFetch(handler: (path: string, init: RequestInit) => Promise<Response> | Response) {
  const calls: string[] = []
  const spy = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input)
    calls.push(path)
    return handler(path, init)
  })
  vi.stubGlobal('fetch', spy)
  return {
    calls,
    countFor: (path: string) => calls.filter((call) => call === path).length,
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const REFRESH_RESPONSE = {
  accessToken: 'access-2',
  refreshToken: 'refresh-2',
  tokenType: 'Bearer',
  expiresInSeconds: 300,
}

beforeEach(() => {
  vi.unstubAllGlobals()
  __resetRefreshStateForTests()
  tokens.clear()
})

describe('single-flight refresh', () => {
  it('issues exactly one refresh when many requests expire at once', async () => {
    tokens.set('access-1', 'refresh-1')

    let refreshResolved = false
    const fetchStub = stubFetch(async (path) => {
      if (path === '/api/auth/refresh') {
        // Held open briefly so every 401 lands while the refresh is still in
        // flight. Without this the test could pass on timing alone.
        await new Promise((resolve) => setTimeout(resolve, 20))
        refreshResolved = true
        return json(REFRESH_RESPONSE)
      }
      return refreshResolved ? json({ ok: path }) : json({ error: 'UNAUTHENTICATED' }, 401)
    })

    const results = await Promise.all([
      apiRequest('/api/accounts'),
      apiRequest('/api/transfers'),
      apiRequest('/api/statements'),
      apiRequest('/api/accounts'),
      apiRequest('/api/transfers'),
    ])

    // The assertion that matters: one refresh, not one per expired request.
    expect(fetchStub.countFor('/api/auth/refresh')).toBe(1)
    expect(results).toHaveLength(5)
    expect(tokens.getAccessToken()).toBe('access-2')
  })

  it('sends the retried requests with the renewed token, not the expired one', async () => {
    tokens.set('access-1', 'refresh-1')
    const seenAuthHeaders: string[] = []
    let refreshed = false

    stubFetch(async (path, init) => {
      if (path === '/api/auth/refresh') {
        refreshed = true
        return json(REFRESH_RESPONSE)
      }
      seenAuthHeaders.push(new Headers(init.headers).get('Authorization') ?? '')
      return refreshed ? json({ ok: true }) : json({}, 401)
    })

    await Promise.all([apiRequest('/api/accounts'), apiRequest('/api/transfers')])

    expect(seenAuthHeaders.slice(0, 2)).toEqual(['Bearer access-1', 'Bearer access-1'])
    expect(seenAuthHeaders.slice(2)).toEqual(['Bearer access-2', 'Bearer access-2'])
  })

  it('does not attempt a refresh when the refresh call itself fails', async () => {
    tokens.set('access-1', 'refresh-1')
    const fetchStub = stubFetch(async (path) =>
      path === '/api/auth/refresh' ? json({}, 401) : json({}, 401),
    )

    await expect(apiRequest('/api/accounts')).rejects.toBeInstanceOf(ApiError)

    expect(fetchStub.countFor('/api/auth/refresh')).toBe(1)
    // A failed refresh signs the session out rather than leaving a token that
    // will only fail again on the next request.
    expect(tokens.getRefreshToken()).toBeNull()
    expect(tokens.getAccessToken()).toBeNull()
  })

  it('never refreshes in response to an auth endpoint 401', async () => {
    tokens.set('access-1', 'refresh-1')
    const fetchStub = stubFetch(async () => json({ error: 'INVALID_CREDENTIALS' }, 401))

    await expect(
      apiRequest('/api/auth/login', { method: 'POST', body: '{}' }),
    ).rejects.toBeInstanceOf(ApiError)

    // A bad password must not spend a refresh token, and refreshing after a
    // failed refresh would recurse.
    expect(fetchStub.countFor('/api/auth/refresh')).toBe(0)
  })

  it('retries a request only once, so a still-401 response does not loop', async () => {
    tokens.set('access-1', 'refresh-1')
    const fetchStub = stubFetch(async (path) =>
      path === '/api/auth/refresh' ? json(REFRESH_RESPONSE) : json({}, 401),
    )

    await expect(apiRequest('/api/accounts')).rejects.toBeInstanceOf(ApiError)

    expect(fetchStub.countFor('/api/accounts')).toBe(2)
    expect(fetchStub.countFor('/api/auth/refresh')).toBe(1)
  })
})

describe('error surfacing', () => {
  it('carries fieldErrors through so a form can map them onto inputs', async () => {
    stubFetch(async () =>
      json(
        {
          status: 400,
          error: 'VALIDATION_FAILED',
          message: 'One or more fields are invalid',
          fieldErrors: { email: 'must not be blank' },
        },
        400,
      ),
    )

    let error!: ApiError
    try {
      await apiRequest('/api/auth/register', { method: 'POST', body: '{}' })
    } catch (caught) {
      error = caught as ApiError
    }

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.code).toBe('VALIDATION_FAILED')
    expect(error.fieldErrors.email).toBe('must not be blank')
    expect(error.hasFieldErrors).toBe(true)
  })

  it('survives an error body that is not our error shape', async () => {
    stubFetch(async () => new Response('<html>gateway timeout</html>', { status: 504 }))

    let error!: ApiError
    try {
      await apiRequest('/api/accounts')
    } catch (caught) {
      error = caught as ApiError
    }

    expect(error.status).toBe(504)
    expect(error.code).toBe('UNKNOWN')
    expect(error.hasFieldErrors).toBe(false)
  })
})
