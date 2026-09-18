import { screen, waitFor, waitForElementToBeRemoved } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'
import { __resetRefreshStateForTests } from '@/lib/api/client'
import { tokens } from '@/lib/api/tokens'
import { renderWithProviders } from '@/test/renderWithProviders'
import type { Account, Paged, Transfer } from '@/lib/api/types'

/**
 * Recharts measures its container, and jsdom reports every element as zero by
 * zero, so ResponsiveContainer would render nothing at all. Substituting a
 * fixed-size box lets the chart actually draw; everything under test — the
 * axes, the line, the point count — is unaffected by how the box got its size.
 */
vi.mock('recharts', async () => {
  const actual = await vi.importActual<typeof import('recharts')>('recharts')
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
      <actual.ResponsiveContainer width={640} height={224}>
        {children as never}
      </actual.ResponsiveContainer>
    ),
  }
})

const ACCOUNT: Account = {
  id: 'a-1',
  currency: 'USD',
  balanceMinorUnits: 75_000,
  status: 'ACTIVE',
  createdAt: '2026-06-01T00:00:00Z',
}

function transfer(overrides: Partial<Transfer> = {}): Transfer {
  return {
    id: 't-1',
    sourceAccountId: 'a-1',
    destinationAccountId: 'a-2',
    direction: 'DEBIT',
    counterpartyLabel: 'bob@payvero.dev',
    counterpartyAccountId: 'a-2',
    amountMinorUnits: 25_000,
    currency: 'USD',
    status: 'SETTLED',
    failureReason: null,
    createdAt: '2026-08-05T10:00:00Z',
    settledAt: '2026-08-05T10:01:00Z',
    ...overrides,
  }
}

function paged(content: Transfer[]): Paged<Transfer> {
  return {
    content,
    page: { size: 8, number: 0, totalElements: content.length, totalPages: 1 },
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** `never` on a route keeps that query pending, so the skeleton stays up. */
function stubApi({
  accounts,
  transfers,
  balanceMinorUnits = 75_000,
  consistent = true,
}: {
  accounts: Account[] | 'never'
  transfers: Transfer[] | 'never'
  balanceMinorUnits?: number
  consistent?: boolean
}) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/accounts') {
        if (accounts === 'never') return new Promise<Response>(() => {})
        return json(accounts)
      }
      if (path.startsWith('/api/transfers')) {
        if (transfers === 'never') return new Promise<Response>(() => {})
        return json(paged(transfers))
      }
      if (path.includes('/balance')) {
        return json({
          accountId: 'a-1',
          currency: 'USD',
          derivedBalanceMinorUnits: balanceMinorUnits,
          cachedBalanceMinorUnits: consistent ? balanceMinorUnits : balanceMinorUnits + 5_000,
          consistent,
        })
      }
      return json({}, 404)
    }),
  )
}

beforeEach(() => {
  vi.unstubAllGlobals()
  __resetRefreshStateForTests()
  tokens.clear()
})

describe('loading skeletons', () => {
  it('shows the page skeleton while accounts are still loading', async () => {
    stubApi({ accounts: 'never', transfers: [] })

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByTestId('dashboard-skeleton')).toBeInTheDocument()
    // Skeleton instead of, not alongside, a misleading zero balance.
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument()
  })

  it('shows activity and chart skeletons while transfers are still loading', async () => {
    stubApi({ accounts: [ACCOUNT], transfers: 'never' })

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByTestId('activity-skeleton')).toBeInTheDocument()
    expect(screen.getByTestId('chart-skeleton')).toBeInTheDocument()
    // The balance resolves independently, so it is not held hostage by the list.
    expect(await screen.findByText('$750.00')).toBeInTheDocument()
  })

  it('replaces the skeleton with content once the data arrives', async () => {
    stubApi({ accounts: [ACCOUNT], transfers: [transfer()] })

    renderWithProviders(<DashboardPage />)

    await waitForElementToBeRemoved(() => screen.queryByTestId('dashboard-skeleton'))
    expect(await screen.findByText('bob@payvero.dev')).toBeInTheDocument()
    expect(screen.queryByTestId('activity-skeleton')).not.toBeInTheDocument()
  })
})

describe('empty states', () => {
  /**
   * Tested against genuinely empty fixtures. The seeded demo never reaches
   * either of these screens, so they would otherwise ship unseen.
   */
  it('offers to open an account when the user has none', async () => {
    stubApi({ accounts: [], transfers: [] })

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText('No account yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open an account' })).toBeInTheDocument()
    // No balance card at all rather than a $0.00 that implies an account exists.
    expect(screen.queryByText('Available balance')).not.toBeInTheDocument()
  })

  it('explains an account with no activity instead of showing a blank list', async () => {
    stubApi({ accounts: [ACCOUNT], transfers: [], balanceMinorUnits: 0 })

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText('No activity yet')).toBeInTheDocument()
    expect(screen.getByText('Money you send or receive will appear here.')).toBeInTheDocument()
    // A funded-but-quiet account still shows its balance and a flat chart.
    // Awaited separately because the balance query settles independently of
    // the transfers one.
    expect(await screen.findByText('$0.00')).toBeInTheDocument()
    // Awaited, not queried synchronously: the chart is a lazy chunk now, so a
    // synchronous assertion would only pass while some earlier await happened
    // to cover the import.
    expect(await screen.findByTestId('balance-chart')).toBeInTheDocument()
  })

  it('warns when the balance disagrees with the journal', async () => {
    stubApi({ accounts: [ACCOUNT], transfers: [transfer()], consistent: false })

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByRole('alert')).toHaveTextContent(/disagrees with the journal/i)
  })
})

describe('the chart with sparse data', () => {
  async function renderChartWith(transfers: Transfer[]) {
    stubApi({ accounts: [ACCOUNT], transfers })
    renderWithProviders(<DashboardPage />)
    return screen.findByTestId('balance-chart')
  }

  it('draws a flat line for an account with no movement at all', async () => {
    const chart = await renderChartWith([])

    // Two points, not zero: a funded account that has done nothing is a line,
    // not an empty panel.
    expect(chart).toHaveAttribute('data-points', '2')
    await waitFor(() => expect(chart.querySelector('.recharts-line')).toBeInTheDocument())
  })

  it('renders a visible dot for a single movement', async () => {
    const chart = await renderChartWith([
      transfer({ id: 't-only', createdAt: '2026-08-10T12:00:00Z' }),
    ])

    expect(chart).toHaveAttribute('data-points', '2')
    // The line has almost no length here, so the dots are what can be seen.
    await waitFor(() =>
      expect(chart.querySelectorAll('.recharts-line-dot').length).toBeGreaterThan(0),
    )
  })

  it('renders two movements on the same day without collapsing them', async () => {
    const chart = await renderChartWith([
      transfer({ id: 't-a', createdAt: '2026-08-10T09:00:00Z', amountMinorUnits: 5_000 }),
      transfer({ id: 't-b', createdAt: '2026-08-10T17:00:00Z', amountMinorUnits: 1_000 }),
    ])

    expect(chart).toHaveAttribute('data-points', '3')
    await waitFor(() =>
      expect(chart.querySelectorAll('.recharts-line-dot').length).toBeGreaterThanOrEqual(3),
    )
  })

  /**
   * The one a category axis fails. Two movements a month apart must be drawn a
   * month apart, so the x positions of the dots have to be far from evenly
   * spaced — which is only true if the axis is a real time scale.
   */
  it('shows a long quiet gap as an actual gap', async () => {
    const chart = await renderChartWith([
      transfer({ id: 't-jun', createdAt: '2026-06-01T10:00:00Z', direction: 'CREDIT' }),
      transfer({ id: 't-jul-a', createdAt: '2026-07-28T10:00:00Z' }),
      transfer({ id: 't-jul-b', createdAt: '2026-07-29T10:00:00Z' }),
    ])

    await waitFor(() =>
      expect(chart.querySelectorAll('.recharts-line-dot').length).toBeGreaterThanOrEqual(4),
    )

    const xs = [...chart.querySelectorAll('.recharts-line-dot')]
      .map((dot) => Number(dot.getAttribute('cx')))
      .sort((a, b) => a - b)

    // The June-to-July gap must dominate the two adjacent July days.
    const longestGap = Math.max(...xs.slice(1).map((x, index) => x - xs[index]))
    const shortestGap = Math.min(...xs.slice(1).map((x, index) => x - xs[index]))
    expect(longestGap).toBeGreaterThan(shortestGap * 5)
  })
})
