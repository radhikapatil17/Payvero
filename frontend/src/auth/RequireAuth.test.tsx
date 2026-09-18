import { screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { RequireAuth } from './RequireAuth'
import { RequireAdmin } from './RequireAdmin'
import { __resetRefreshStateForTests } from '@/lib/api/client'
import { tokens } from '@/lib/api/tokens'
import { renderWithProviders } from '@/test/renderWithProviders'

const PROTECTED_TEXT = 'Your balance is 1,178,348'

/**
 * Records every mount, so a test can assert the guarded subtree was never
 * constructed rather than only that it is absent by the time assertions run.
 * Checking the DOM after the fact cannot tell the difference between "never
 * rendered" and "rendered and then removed", and the second one is the flash.
 */
const mountSpy = vi.fn()

function ProtectedPage() {
  mountSpy()
  return <p>{PROTECTED_TEXT}</p>
}

function LoginPage() {
  return <p>Sign in to Payvero</p>
}

/**
 * Spied for the same reason as ProtectedPage. An admin screen leaking to a
 * signed-in USER is worse than one leaking to an anonymous visitor: the user
 * is real, the render is real, and only the redirect is late.
 */
const adminMountSpy = vi.fn()

function AdminPage() {
  adminMountSpy()
  return <p>Fraud review queue</p>
}

function renderGuardedApp(route = '/dashboard') {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<p>Home</p>} />
      <Route element={<RequireAuth />}>
        <Route path="/dashboard" element={<ProtectedPage />} />
      </Route>
      <Route element={<RequireAdmin />}>
        <Route path="/admin" element={<AdminPage />} />
      </Route>
    </Routes>,
    { route },
  )
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/**
 * A refresh the test finishes, rather than one it waits out.
 *
 * `hold` keeps the session resolving until `release()` is called, so "still
 * resolving" is a state the test pins open instead of a window it races to
 * assert inside. A wall-clock delay makes the same assertion only probably
 * true, and it stops being true on a loaded machine.
 */
function stubSlowSession({
  hold = false,
  refreshSucceeds,
  role = 'USER',
}: {
  hold?: boolean
  refreshSucceeds: boolean
  role?: 'USER' | 'ADMIN'
}) {
  let open: (() => void) | null = null
  const gate = hold ? new Promise<void>((resolve) => (open = resolve)) : Promise.resolve()

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/auth/refresh') {
        await gate
        return refreshSucceeds
          ? json({
              accessToken: 'access-2',
              refreshToken: 'refresh-2',
              tokenType: 'Bearer',
              expiresInSeconds: 300,
            })
          : json({ error: 'INVALID_REFRESH_TOKEN' }, 401)
      }
      if (path === '/api/auth/me') {
        return json({
          userId: 'u-1',
          email: 'alice@payvero.dev',
          role,
          createdAt: '2026-08-01T00:00:00Z',
        })
      }
      return json({}, 404)
    }),
  )

  return { release: () => open?.() }
}

beforeEach(() => {
  vi.unstubAllGlobals()
  __resetRefreshStateForTests()
  tokens.clear()
  mountSpy.mockClear()
  adminMountSpy.mockClear()
})

describe('RequireAuth', () => {
  it('never renders the guarded page while a slow session is resolving', async () => {
    tokens.set('', 'refresh-1')
    const session = stubSlowSession({ hold: true, refreshSucceeds: true })

    renderGuardedApp()

    // Resolving: a loader, and crucially nothing from behind the guard.
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.queryByText(PROTECTED_TEXT)).not.toBeInTheDocument()
    expect(mountSpy).not.toHaveBeenCalled()

    // The refresh is held open, so this is not "not yet" — it cannot resolve
    // until the test says so, and the guard still shows nothing protected.
    await Promise.resolve()
    expect(screen.queryByText(PROTECTED_TEXT)).not.toBeInTheDocument()
    expect(mountSpy).not.toHaveBeenCalled()

    // Only once the session actually resolves does the page appear.
    session.release()
    await waitFor(() => expect(screen.getByText(PROTECTED_TEXT)).toBeInTheDocument())
    expect(mountSpy).toHaveBeenCalledTimes(1)
  })

  it('redirects without ever mounting the guarded page when the refresh fails', async () => {
    tokens.set('', 'refresh-1')
    stubSlowSession({ refreshSucceeds: false })

    renderGuardedApp()

    expect(screen.queryByText(PROTECTED_TEXT)).not.toBeInTheDocument()

    await waitFor(() => expect(screen.getByText('Sign in to Payvero')).toBeInTheDocument())

    // The point of the test: the protected component was never constructed,
    // not merely removed after a redirect.
    expect(mountSpy).not.toHaveBeenCalled()
  })

  it('goes straight to sign-in when there is no stored session', async () => {
    stubSlowSession({ refreshSucceeds: false })

    renderGuardedApp()

    await waitFor(() => expect(screen.getByText('Sign in to Payvero')).toBeInTheDocument())
    expect(mountSpy).not.toHaveBeenCalled()
    // With nothing stored there is nothing to refresh, so no token is spent.
    expect(vi.mocked(fetch)).not.toHaveBeenCalledWith(
      '/api/auth/refresh',
      expect.anything(),
    )
  })
})

describe('RequireAdmin', () => {
  /**
   * A USER has a valid session, so the guard cannot lean on the anonymous
   * path: it has to wait for the resolved role before deciding. The spy is the
   * whole point — asserting the text is gone afterwards would pass on a guard
   * that rendered the queue, painted it, and then navigated away.
   */
  it('never constructs an admin page for a signed-in USER, even mid-resolve', async () => {
    tokens.set('', 'refresh-1')
    const session = stubSlowSession({ hold: true, refreshSucceeds: true, role: 'USER' })

    renderGuardedApp('/admin')

    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(adminMountSpy).not.toHaveBeenCalled()

    await Promise.resolve()
    expect(adminMountSpy).not.toHaveBeenCalled()

    session.release()
    await waitFor(() => expect(screen.getByText('Home')).toBeInTheDocument())
    expect(adminMountSpy).not.toHaveBeenCalled()
  })

  it('shows a loader rather than admin content while the role is still unknown', async () => {
    tokens.set('', 'refresh-1')
    const session = stubSlowSession({ hold: true, refreshSucceeds: true, role: 'ADMIN' })

    renderGuardedApp('/admin')

    // Even the user who will be allowed through must not see it early: until
    // the server answers, the client does not know the role.
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(adminMountSpy).not.toHaveBeenCalled()

    session.release()
    await waitFor(() => expect(screen.getByText('Fraud review queue')).toBeInTheDocument())
    expect(adminMountSpy).toHaveBeenCalledTimes(1)
  })

  it('sends an anonymous visitor to sign-in, not to the dashboard', async () => {
    stubSlowSession({ refreshSucceeds: false })

    renderGuardedApp('/admin')

    await waitFor(() => expect(screen.getByText('Sign in to Payvero')).toBeInTheDocument())
    expect(adminMountSpy).not.toHaveBeenCalled()
  })
})
