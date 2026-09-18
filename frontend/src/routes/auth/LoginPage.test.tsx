import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { LoginPage } from './LoginPage'
import { RegisterPage } from './RegisterPage'
import { __resetRefreshStateForTests } from '@/lib/api/client'
import { tokens } from '@/lib/api/tokens'
import { renderWithProviders } from '@/test/renderWithProviders'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** Answers the auth endpoints; every case supplies its own failure shape. */
function stubAuth(onAuth: (path: string) => Response | Promise<Response>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/auth/me') {
        return json({
          userId: 'u-1',
          email: 'alice@payvero.dev',
          role: 'USER',
          createdAt: '2026-08-01T00:00:00Z',
        })
      }
      return onAuth(path)
    }),
  )
}

function renderAuthRoutes(route = '/login') {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/" element={<p>Dashboard</p>} />
    </Routes>,
    { route },
  )
}

async function submitLogin(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Email'), 'alice@payvero.dev')
  await user.type(screen.getByLabelText('Password'), 'demo-password-123')
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

beforeEach(() => {
  vi.unstubAllGlobals()
  __resetRefreshStateForTests()
  tokens.clear()
})

describe('server field errors', () => {
  /**
   * The distinction that matters: not "an error appeared somewhere" but "this
   * error is attached to this input". A message rendered loose on the page
   * looks the same to a sighted user scanning quickly and tells a screen
   * reader nothing about which field to fix.
   */
  it('attaches a field error to that specific input and announces it', async () => {
    const user = userEvent.setup()
    stubAuth(() =>
      json(
        {
          status: 400,
          error: 'VALIDATION_FAILED',
          message: 'One or more fields are invalid',
          fieldErrors: { email: 'must be a well-formed email address' },
        },
        400,
      ),
    )

    renderAuthRoutes()
    await submitLogin(user)

    const emailInput = await screen.findByLabelText('Email')
    const passwordInput = screen.getByLabelText('Password')

    // Attached to email: marked invalid, and its accessible description is the
    // server's message, which is only true if aria-describedby points at it.
    await waitFor(() => expect(emailInput).toHaveAttribute('aria-invalid', 'true'))
    expect(emailInput).toHaveAccessibleDescription('must be a well-formed email address')

    // And not attached to the field the server said nothing about.
    expect(passwordInput).not.toHaveAttribute('aria-invalid')
    expect(passwordInput).not.toHaveAccessibleDescription(
      'must be a well-formed email address',
    )
  })

  it('routes each field error to its own input when several arrive', async () => {
    const user = userEvent.setup()
    stubAuth(() =>
      json(
        {
          status: 400,
          error: 'VALIDATION_FAILED',
          message: 'One or more fields are invalid',
          fieldErrors: {
            email: 'must not be blank',
            password: 'size must be between 12 and 200',
          },
        },
        400,
      ),
    )

    renderAuthRoutes('/register')
    await user.type(screen.getByLabelText('Email'), 'someone@payvero.dev')
    await user.type(screen.getByLabelText('Password'), 'a-long-enough-password')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    await waitFor(() =>
      expect(screen.getByLabelText('Email')).toHaveAccessibleDescription('must not be blank'),
    )
    // The password field keeps its hint and gains its own message, rather than
    // inheriting the email one.
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      /size must be between 12 and 200/,
    )
    expect(screen.getByLabelText('Password')).not.toHaveAccessibleDescription(/must not be blank/)
  })
})

describe('errors that name no field', () => {
  /**
   * A form that discards an error the server bothered to explain looks broken:
   * the user presses the button and nothing happens.
   */
  it.each([
    ['INVALID_CREDENTIALS', 401, 'Invalid email or password'],
    ['RATE_LIMIT_EXCEEDED', 429, 'Too many transfers in a short period; try again shortly'],
    ['IDEMPOTENCY_KEY_REUSED', 422, 'This idempotency key was already used for a different request'],
    ['REQUEST_IN_PROGRESS', 409, 'A request with this idempotency key is still in progress'],
    ['INSUFFICIENT_FUNDS', 422, 'Insufficient available balance for this movement'],
  ])('surfaces %s as a form-level alert', async (code, status, message) => {
    const user = userEvent.setup()
    stubAuth(() => json({ status, error: code, message, fieldErrors: {} }, status))

    renderAuthRoutes()
    await submitLogin(user)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(message)

    // Field-less means exactly that: no input is blamed for it.
    expect(screen.getByLabelText('Email')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByLabelText('Password')).not.toHaveAttribute('aria-invalid')
  })

  /**
   * The case that silently loses errors in most implementations: the server
   * validates a field this build's form does not have. Calling setError for an
   * input that renders nowhere means the message disappears entirely.
   */
  it('does not swallow a field error for an input this form does not render', async () => {
    const user = userEvent.setup()
    stubAuth(() =>
      json(
        {
          status: 400,
          error: 'VALIDATION_FAILED',
          message: 'One or more fields are invalid',
          fieldErrors: { totpCode: 'must not be blank' },
        },
        400,
      ),
    )

    renderAuthRoutes()
    await submitLogin(user)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('totpCode: must not be blank')
  })

  it('still explains itself when the response is not our error shape at all', async () => {
    const user = userEvent.setup()
    stubAuth(() => new Response('<html>502</html>', { status: 502 }))

    renderAuthRoutes()
    await submitLogin(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(/something went wrong/i)
  })
})

describe('the happy path', () => {
  it('signs in and lands on the dashboard', async () => {
    const user = userEvent.setup()
    stubAuth(() =>
      json({
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        tokenType: 'Bearer',
        expiresInSeconds: 300,
      }),
    )

    renderAuthRoutes()
    await submitLogin(user)

    await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument())
    expect(tokens.getRefreshToken()).toBe('refresh-1')
  })

  it('validates client-side before troubling the server', async () => {
    const user = userEvent.setup()
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)

    renderAuthRoutes('/register')
    await user.type(screen.getByLabelText('Email'), 'not-an-email')
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    await waitFor(() =>
      expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(/valid email/i),
    )
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(/at least 12 characters/i)
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
