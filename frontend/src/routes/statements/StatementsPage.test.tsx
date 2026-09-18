import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StatementsPage } from './StatementsPage'
import { __resetRefreshStateForTests } from '@/lib/api/client'
import { tokens } from '@/lib/api/tokens'
import { renderWithProviders } from '@/test/renderWithProviders'
import type { Account, Paged, Statement, Transfer } from '@/lib/api/types'

/**
 * The clock is frozen because "the current period" is the whole subject here.
 * Left on the wall clock these tests would pass in August and quietly change
 * meaning in September.
 */
const NOW = new Date('2026-08-19T12:00:00Z')

const ACCOUNT: Account = {
  id: '11111111-1111-1111-1111-111111111111',
  currency: 'USD',
  balanceMinorUnits: 250_000,
  status: 'ACTIVE',
  createdAt: '2026-05-04T00:00:00Z',
}

function statement(period: string, overrides: Partial<Statement> = {}): Statement {
  return {
    id: `s-${period}`,
    accountId: ACCOUNT.id,
    period,
    openingBalanceMinorUnits: 100_000,
    closingBalanceMinorUnits: 142_500,
    netMovementMinorUnits: 42_500,
    entryCount: 2,
    generatedAt: '2026-08-01T00:05:00Z',
    lineItems: [
      {
        entryId: `e-${period}-1`,
        transferId: `t-${period}-1`,
        direction: 'CREDIT',
        amountMinorUnits: 50_000,
        balanceAfterMinorUnits: 150_000,
        currency: 'USD',
        occurredAt: `${period}-09T10:00:00Z`,
      },
      {
        entryId: `e-${period}-2`,
        transferId: `t-${period}-2`,
        direction: 'DEBIT',
        amountMinorUnits: 7_500,
        balanceAfterMinorUnits: 142_500,
        currency: 'USD',
        occurredAt: `${period}-21T10:00:00Z`,
      },
    ],
    ...overrides,
  }
}

function transfer(id: string, createdAt: string, overrides: Partial<Transfer> = {}): Transfer {
  return {
    id,
    sourceAccountId: ACCOUNT.id,
    destinationAccountId: '22222222-2222-2222-2222-222222222222',
    direction: 'DEBIT',
    counterpartyLabel: 'bob@payvero.dev',
    counterpartyAccountId: '22222222-2222-2222-2222-222222222222',
    amountMinorUnits: 12_300,
    currency: 'USD',
    status: 'SETTLED',
    failureReason: null,
    createdAt,
    settledAt: createdAt,
    ...overrides,
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function paged(content: Transfer[]): Paged<Transfer> {
  return { content, page: { size: 50, number: 0, totalElements: content.length, totalPages: 1 } }
}

type Harness = {
  generateCount: () => number
  /** Every period the client asked the server to generate, in order. */
  generatedPeriods: string[]
}

function stubApi({
  statements = [] as Statement[],
  transfers = [] as Transfer[],
  accounts = [ACCOUNT] as Account[],
  generateFails,
}: {
  statements?: Statement[]
  transfers?: Transfer[]
  accounts?: Account[]
  generateFails?: { status: number; body: unknown }
} = {}): Harness {
  const generatedPeriods: string[] = []
  const served = [...statements]

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const path = String(input)
      const statementsRoot = `/api/accounts/${ACCOUNT.id}/statements`

      if (path.startsWith(statementsRoot) && init.method === 'POST') {
        const period = path.slice(statementsRoot.length + 1)
        generatedPeriods.push(period)
        if (generateFails) return json(generateFails.body, generateFails.status)
        const created = statement(period)
        served.unshift(created)
        return json(created, 201)
      }

      if (path === statementsRoot) return json(served)
      if (path === '/api/accounts') return json(accounts)
      if (path.startsWith('/api/transfers')) return json(paged(transfers))
      return json({}, 404)
    }),
  )

  return { generateCount: () => generatedPeriods.length, generatedPeriods }
}

async function renderPage() {
  renderWithProviders(<StatementsPage />)
  await waitFor(() => expect(screen.queryByTestId('statements-skeleton')).not.toBeInTheDocument())
}

function selectPeriod(user: ReturnType<typeof userEvent.setup>, label: string) {
  return user.click(screen.getByRole('button', { name: new RegExp(label, 'i') }))
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(NOW)
  vi.unstubAllGlobals()
  __resetRefreshStateForTests()
  tokens.clear()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('the open period', () => {
  /**
   * The case the whole screen is shaped around. August has no statement and
   * never will until it ends, so the screen has to say that rather than show
   * nothing, show a spinner, or show an error.
   */
  it('says the month is still open and shows the live activity in place of a statement', async () => {
    stubApi({
      statements: [statement('2026-07')],
      transfers: [
        transfer('t-aug', '2026-08-12T10:00:00Z', { counterpartyLabel: 'august@payvero.dev' }),
        transfer('t-jul', '2026-07-12T10:00:00Z', { counterpartyLabel: 'july@payvero.dev' }),
      ],
    })
    await renderPage()

    const panel = within(screen.getByTestId('open-period-panel'))
    expect(panel.getByText(/August 2026 is still open/i)).toBeInTheDocument()
    expect(panel.getByText(/issued once the month ends/i)).toBeInTheDocument()
    expect(panel.getByText('Not yet issued')).toBeInTheDocument()

    // The live view, scoped to this month and no other.
    expect(await panel.findByText('august@payvero.dev')).toBeInTheDocument()
    expect(panel.queryByText('july@payvero.dev')).not.toBeInTheDocument()
  })

  /**
   * The distinction that has to be legible: an open month must not offer the
   * action that only makes sense for a closed one, and must not be dressed up
   * as an issued statement.
   */
  it('offers no generate action and shows no statement figures', async () => {
    stubApi({ transfers: [transfer('t-aug', '2026-08-12T10:00:00Z')] })
    await renderPage()

    expect(screen.getByTestId('open-period-panel')).toBeInTheDocument()
    expect(screen.queryByTestId('statement-panel')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /generate statement/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/opening/i)).not.toBeInTheDocument()
  })

  /**
   * An open month with nothing in it yet is still not an error. This is the
   * shape that most invites a blank screen, so it is asserted explicitly.
   */
  it('reads as open, not broken, when nothing has happened this month', async () => {
    stubApi({ transfers: [transfer('t-jul', '2026-07-12T10:00:00Z')] })
    await renderPage()

    const panel = within(screen.getByTestId('open-period-panel'))
    expect(panel.getByText(/August 2026 is still open/i)).toBeInTheDocument()
    expect(await panel.findByText(/Nothing this month yet/i)).toBeInTheDocument()
    expect(panel.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('marks the running month open in the period list and leaves closed months unmarked', async () => {
    stubApi({ statements: [statement('2026-07')] })
    await renderPage()

    const august = screen.getByRole('button', { name: /August 2026/i })
    expect(within(august).getByText('open')).toBeInTheDocument()

    const july = screen.getByRole('button', { name: /July 2026/i })
    expect(within(july).queryByText('open')).not.toBeInTheDocument()
    expect(within(july).queryByText('not issued')).not.toBeInTheDocument()
  })
})

describe('an issued statement', () => {
  it('presents the closed period as a fixed record, with its figures and lines', async () => {
    stubApi({ statements: [statement('2026-07')] })
    await renderPage()
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

    await selectPeriod(user, 'July 2026')

    const panel = within(screen.getByTestId('statement-panel'))
    expect(panel.getByText(/July 2026 statement/i)).toBeInTheDocument()
    expect(panel.getByText(/fixed once issued/i)).toBeInTheDocument()
    expect(panel.getAllByTestId('statement-line')).toHaveLength(2)

    // Scoped to the summary, because a closing balance and the balance after
    // the last line are the same number for a good reason. Asserting loosely
    // would let either one satisfy the test for the other.
    const figures = within(panel.getByTestId('statement-figures'))
    expect(figures.getByText('$1,000.00')).toBeInTheDocument() // opening
    expect(figures.getByText('$1,425.00')).toBeInTheDocument() // closing
    expect(figures.getByText('+$425.00')).toBeInTheDocument() // net movement

    // And it is not the live view.
    expect(screen.queryByTestId('open-period-panel')).not.toBeInTheDocument()
  })

  it('explains a period that closed with no movement rather than showing an empty table', async () => {
    stubApi({
      statements: [
        statement('2026-07', {
          lineItems: [],
          entryCount: 0,
          netMovementMinorUnits: 0,
          closingBalanceMinorUnits: 100_000,
        }),
      ],
    })
    await renderPage()
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

    await selectPeriod(user, 'July 2026')

    expect(screen.getByText(/No movement in this period/i)).toBeInTheDocument()
    expect(screen.queryAllByTestId('statement-line')).toHaveLength(0)
  })
})

describe('a closed period with no statement', () => {
  it('offers to generate one and shows it once issued', async () => {
    const harness = stubApi()
    await renderPage()
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

    await selectPeriod(user, 'June 2026')
    expect(screen.getByText(/June 2026 has closed/i)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /generate statement/i }))

    expect(await screen.findByTestId('statement-panel')).toBeInTheDocument()
    expect(harness.generatedPeriods).toEqual(['2026-06'])
  })

  /**
   * Reachable by sitting on this page across a month boundary: the client
   * still believes the month is closed, the server knows better. The user must
   * get the reason, not the raw refusal.
   */
  it('turns a 409 PERIOD_NOT_CLOSED into an explanation rather than a raw error', async () => {
    stubApi({
      generateFails: {
        status: 409,
        body: {
          timestamp: '2026-08-19T12:00:00Z',
          status: 409,
          error: 'PERIOD_NOT_CLOSED',
          message: 'Period 2026-06 has not closed',
          fieldErrors: {},
        },
      },
    })
    await renderPage()
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

    await selectPeriod(user, 'June 2026')
    await user.click(screen.getByRole('button', { name: /generate statement/i }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/has not finished yet/i)
    expect(alert).toHaveTextContent(/once the month ends/i)
    // The machine code and the server's terse wording stay out of the UI.
    expect(alert).not.toHaveTextContent('PERIOD_NOT_CLOSED')
    expect(alert).not.toHaveTextContent('Period 2026-06 has not closed')
  })

  /**
   * Both periods here are un-issued on purpose. Switching to a month that has
   * a statement would take a different branch and hide the alert whether or
   * not the failure was actually cleared, so the test would pass on a page
   * that never cleared anything.
   */
  it('does not leave one period’s failure showing over another un-issued period', async () => {
    stubApi({
      generateFails: {
        status: 409,
        body: { status: 409, error: 'PERIOD_NOT_CLOSED', message: 'nope', fieldErrors: {} },
      },
    })
    await renderPage()
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

    await selectPeriod(user, 'June 2026')
    await user.click(screen.getByRole('button', { name: /generate statement/i }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()

    await selectPeriod(user, 'May 2026')

    expect(screen.getByText(/May 2026 has closed/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('loading and empty', () => {
  it('shows a skeleton before the account and statements arrive', async () => {
    stubApi()
    renderWithProviders(<StatementsPage />)

    expect(screen.getByTestId('statements-skeleton')).toBeInTheDocument()

    await waitFor(() => expect(screen.queryByTestId('statements-skeleton')).not.toBeInTheDocument())
  })

  it('explains that statements are per-account when there is no account', async () => {
    stubApi({ accounts: [] })
    await renderPage()

    expect(screen.getByText(/No account yet/i)).toBeInTheDocument()
    expect(screen.queryByTestId('open-period-panel')).not.toBeInTheDocument()
  })
})
