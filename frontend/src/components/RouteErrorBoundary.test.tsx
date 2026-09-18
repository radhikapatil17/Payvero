import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { RouteErrorBoundary } from './RouteErrorBoundary'
import { AppLayout } from './AppLayout'
import { __resetRefreshStateForTests } from '@/lib/api/client'
import { tokens } from '@/lib/api/tokens'
import { renderWithProviders } from '@/test/renderWithProviders'

/**
 * A component that throws during render, not one that calls a mocked failure.
 * An error boundary only ever sees real throws, so a test that simulates one
 * is testing something the boundary will never encounter.
 */
function Exploding({ message = 'cannot read balance of undefined' }: { message?: string }): never {
  throw new Error(message)
}

/**
 * Fails until the test says the cause is gone, standing in for a bad response
 * that a refetch replaces.
 *
 * Deliberately not a "throws on first render" flag: React may render a
 * component more than once for a single logical render — it retries
 * synchronously after a concurrent throw — so a self-clearing flag can be
 * spent before the boundary ever sees the error.
 */
const transient = { failing: true }

function FailsUntilFixed() {
  if (transient.failing) {
    throw new Error('transient shape error')
  }
  return <p>Recovered content</p>
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  vi.unstubAllGlobals()
  __resetRefreshStateForTests()
  tokens.clear()
  transient.failing = true
  // React logs the caught error, and the boundary logs it too. Both are correct
  // behaviour; silencing keeps the run readable without suppressing assertions.
  vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.stubGlobal('fetch', vi.fn(async () => json({}, 404)))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('catching a render-time throw', () => {
  it('shows a recoverable message instead of a blank page', async () => {
    renderWithProviders(
      <RouteErrorBoundary>
        <Exploding />
      </RouteErrorBoundary>,
    )

    const panel = await screen.findByTestId('route-error')
    expect(panel).toBeInTheDocument()
    expect(panel).toHaveTextContent(/something went wrong on this page/i)

    // Not a blank screen: there is a way out, and it is announced.
    expect(panel).toHaveAttribute('role', 'alert')
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /back to dashboard/i })).toBeInTheDocument()
  })

  it('never leaks the raw exception message to the user', async () => {
    renderWithProviders(
      <RouteErrorBoundary>
        <Exploding message="ORA-00942: table or view does not exist" />
      </RouteErrorBoundary>,
    )

    const panel = await screen.findByTestId('route-error')
    expect(panel).not.toHaveTextContent('ORA-00942')
    expect(panel).not.toHaveTextContent(/table or view/i)
    // What it says instead is the thing a person actually needs to know.
    expect(panel).toHaveTextContent(/your money is unaffected/i)
  })

  it('recovers when the cause is transient rather than only redrawing the crash', async () => {
    renderWithProviders(
      <RouteErrorBoundary>
        <FailsUntilFixed />
      </RouteErrorBoundary>,
    )

    await screen.findByTestId('route-error')
    expect(screen.queryByText('Recovered content')).not.toBeInTheDocument()

    // The underlying cause goes away, as a refetch would make it.
    transient.failing = false

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /try again/i }))

    expect(await screen.findByText('Recovered content')).toBeInTheDocument()
    expect(screen.queryByTestId('route-error')).not.toBeInTheDocument()
  })

  /**
   * The honest other half: if the cause has not gone away, retrying shows the
   * error again rather than a blank screen or a spinner that never resolves.
   */
  it('shows the error again, still recoverable, when the cause persists', async () => {
    renderWithProviders(
      <RouteErrorBoundary>
        <FailsUntilFixed />
      </RouteErrorBoundary>,
    )

    await screen.findByTestId('route-error')

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /try again/i }))

    expect(await screen.findByTestId('route-error')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })
})

describe('scope of the failure', () => {
  /**
   * The reason the boundary sits inside the layout rather than only at the
   * root. A screen that throws should cost the user that screen, not the
   * navigation they need in order to leave it.
   */
  it('keeps the navigation usable when a page inside the layout throws', async () => {
    renderWithProviders(
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<Exploding />} />
          <Route path="/transfers" element={<p>Transfers screen</p>} />
        </Route>
      </Routes>,
    )

    expect(await screen.findByTestId('route-error')).toBeInTheDocument()

    // The shell survived: the brand and every nav link are still on screen.
    expect(screen.getByText('Payvero')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Transfers' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument()
  })

  /**
   * Without a per-route key the boundary stays in its error state forever: the
   * user navigates to a working screen and still sees the crash from the one
   * they left.
   */
  it('clears the error on navigating to a different route', async () => {
    renderWithProviders(
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<Exploding />} />
          <Route path="/transfers" element={<p>Transfers screen</p>} />
        </Route>
      </Routes>,
    )

    await screen.findByTestId('route-error')

    const user = userEvent.setup()
    await user.click(screen.getByRole('link', { name: 'Transfers' }))

    expect(await screen.findByText('Transfers screen')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByTestId('route-error')).not.toBeInTheDocument())
  })

  it('does not swallow a working page', async () => {
    renderWithProviders(
      <RouteErrorBoundary>
        <p>Perfectly fine</p>
      </RouteErrorBoundary>,
    )

    expect(screen.getByText('Perfectly fine')).toBeInTheDocument()
    expect(screen.queryByTestId('route-error')).not.toBeInTheDocument()
  })
})

describe('the retry', () => {
  /**
   * Asserted against the real cache rather than a mocked hook. If a bad
   * response is what caused the throw, a retry that re-renders the same cached
   * bytes just redraws the crash — dropping the data is what makes the button
   * worth offering.
   */
  it('drops cached data so a bad response is refetched rather than replayed', async () => {
    const { queryClient } = renderWithProviders(
      <RouteErrorBoundary>
        <Exploding />
      </RouteErrorBoundary>,
    )
    queryClient.setQueryData(['transfers', 0, 8], { content: 'the response that broke rendering' })

    await screen.findByTestId('route-error')
    expect(queryClient.getQueryData(['transfers', 0, 8])).toBeDefined()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /try again/i }))

    await waitFor(() =>
      expect(queryClient.getQueryData(['transfers', 0, 8])).toBeUndefined(),
    )
  })
})
