import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TransfersPage } from './TransfersPage'
import { __resetRefreshStateForTests } from '@/lib/api/client'
import { tokens } from '@/lib/api/tokens'
import { renderWithProviders } from '@/test/renderWithProviders'
import type { Account, Paged, Transfer } from '@/lib/api/types'

const toasts = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('sonner', () => ({ toast: toasts }))

const ACCOUNT: Account = {
  id: '11111111-1111-1111-1111-111111111111',
  currency: 'USD',
  balanceMinorUnits: 100_000,
  status: 'ACTIVE',
  createdAt: '2026-06-01T00:00:00Z',
}
const RECIPIENT = '22222222-2222-2222-2222-222222222222'

function existingTransfer(): Transfer {
  return {
    id: 't-existing',
    sourceAccountId: ACCOUNT.id,
    destinationAccountId: RECIPIENT,
    direction: 'DEBIT',
    counterpartyLabel: 'bob@payvero.dev',
    counterpartyAccountId: RECIPIENT,
    amountMinorUnits: 1_000,
    currency: 'USD',
    status: 'SETTLED',
    failureReason: null,
    createdAt: '2026-08-01T10:00:00Z',
    settledAt: '2026-08-01T10:01:00Z',
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function paged(content: Transfer[]): Paged<Transfer> {
  return { content, page: { size: 10, number: 0, totalElements: content.length, totalPages: 1 } }
}

type Harness = {
  /** Every Idempotency-Key the client sent, in order. */
  sentKeys: string[]
  postCount: () => number
  transferGetCount: () => number
  /** Resolves the in-flight POST when the test decides to. */
  settlePost: (outcome: { ok: true; body: Transfer } | { ok: false; status: number; body: unknown }) => void
  /**
   * Freezes list refetches.
   *
   * Without this a test cannot tell a rollback from a refetch that happened to
   * restore the same state: onSettled invalidates, the server returns the
   * pre-transfer list, and the screen looks correct whether or not onError ever
   * ran. Holding the refetch leaves only the optimistic handling under test.
   */
  freezeListRefetch: () => void
  thawListRefetch: () => void
}

function stubApi({
  transfers = [existingTransfer()],
  balanceMinorUnits = 100_000,
  holdPost = false,
}: {
  transfers?: Transfer[]
  balanceMinorUnits?: number
  holdPost?: boolean
} = {}): Harness {
  const sentKeys: string[] = []
  let posts = 0
  let transferGets = 0
  let release: ((value: Response) => void) | null = null
  let serverTransfers = [...transfers]
  let serverBalance = balanceMinorUnits
  let frozen = false
  let heldReads: (() => void)[] = []

  const harness: Harness = {
    sentKeys,
    postCount: () => posts,
    transferGetCount: () => transferGets,
    freezeListRefetch: () => {
      frozen = true
    },
    thawListRefetch: () => {
      frozen = false
      heldReads.forEach((resume) => resume())
      heldReads = []
    },
    settlePost: (outcome) => {
      if (outcome.ok) {
        serverTransfers = [outcome.body, ...serverTransfers]
        serverBalance -= outcome.body.amountMinorUnits
        release?.(json(outcome.body, 201))
      } else {
        release?.(json(outcome.body, outcome.status))
      }
      release = null
    },
  }

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const path = String(input)

      if (path === '/api/transfers' && init.method === 'POST') {
        posts += 1
        sentKeys.push(new Headers(init.headers).get('Idempotency-Key') ?? '')
        if (holdPost) {
          return new Promise<Response>((resolve) => {
            release = resolve
          })
        }
        const body = JSON.parse(String(init.body)) as { amountMinorUnits: number }
        const created = confirmedTransfer(body.amountMinorUnits)
        serverTransfers = [created, ...serverTransfers]
        serverBalance -= created.amountMinorUnits
        return json(created, 201)
      }

      if (path.startsWith('/api/transfers')) {
        transferGets += 1
        if (frozen) {
          await new Promise<void>((resume) => heldReads.push(resume))
        }
        return json(paged(serverTransfers))
      }
      if (path === '/api/accounts') return json([ACCOUNT])
      if (path.includes('/balance')) {
        if (frozen) {
          await new Promise<void>((resume) => heldReads.push(resume))
        }
        return json({
          accountId: ACCOUNT.id,
          currency: 'USD',
          derivedBalanceMinorUnits: serverBalance,
          cachedBalanceMinorUnits: serverBalance,
          consistent: true,
        })
      }
      return json({}, 404)
    }),
  )

  return harness
}

function confirmedTransfer(amountMinorUnits: number, status: Transfer['status'] = 'PENDING'): Transfer {
  return {
    id: 't-server-1',
    sourceAccountId: ACCOUNT.id,
    destinationAccountId: RECIPIENT,
    direction: 'DEBIT',
    counterpartyLabel: 'bob@payvero.dev',
    counterpartyAccountId: RECIPIENT,
    amountMinorUnits,
    currency: 'USD',
    status,
    failureReason: null,
    createdAt: '2026-08-19T10:00:00Z',
    settledAt: null,
  }
}

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, amount = '250.00') {
  await user.type(screen.getByLabelText('Recipient account id'), RECIPIENT)
  await user.type(screen.getByLabelText('Amount (USD)'), amount)
  await user.click(screen.getByRole('button', { name: /send transfer|try again/i }))
}

function rows() {
  return screen.queryAllByTestId('transfer-row')
}

beforeEach(() => {
  vi.unstubAllGlobals()
  __resetRefreshStateForTests()
  tokens.clear()
  toasts.success.mockClear()
  toasts.error.mockClear()
})

describe('optimistic rollback', () => {
  /**
   * The test the whole feature exists to be trusted by. A 422 must undo
   * everything the optimistic update did: the row goes, the balance goes back,
   * and the server's own explanation reaches the user.
   */
  it('removes the pending row, restores the balance, and toasts the server message', async () => {
    const user = userEvent.setup()
    const api = stubApi({ balanceMinorUnits: 100_000, holdPost: true })

    renderWithProviders(<TransfersPage />)
    expect(await screen.findByTestId('available-balance')).toHaveTextContent('$1,000.00')
    await waitFor(() => expect(rows()).toHaveLength(1))

    // From here the server will not answer another read, so anything that gets
    // repaired on screen was repaired by the rollback and not by a refetch.
    api.freezeListRefetch()

    await fillAndSubmit(user)

    // In flight: the row is there and the money has visibly left.
    await waitFor(() => expect(rows()).toHaveLength(2))
    expect(screen.getByTestId('available-balance')).toHaveTextContent('$750.00')

    api.settlePost({
      ok: false,
      status: 422,
      body: {
        status: 422,
        error: 'INSUFFICIENT_FUNDS',
        message: 'Insufficient available balance for this movement',
        fieldErrors: {},
      },
    })

    // Rolled back while the server is still silent.
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0]).toHaveAttribute('data-transfer-id', 't-existing')
    await waitFor(() =>
      expect(screen.getByTestId('available-balance')).toHaveTextContent('$1,000.00'),
    )

    expect(toasts.error).toHaveBeenCalledWith('Insufficient available balance for this movement')

    api.thawListRefetch()
  })

  it('rolls back a 500 the same way, with a message the user can act on', async () => {
    const user = userEvent.setup()
    const api = stubApi({ holdPost: true })

    renderWithProviders(<TransfersPage />)
    await waitFor(() => expect(rows()).toHaveLength(1))
    api.freezeListRefetch()

    await fillAndSubmit(user)
    await waitFor(() => expect(rows()).toHaveLength(2))

    api.settlePost({
      ok: false,
      status: 500,
      body: { status: 500, error: 'INTERNAL', message: 'Server error', fieldErrors: {} },
    })

    await waitFor(() => expect(rows()).toHaveLength(1))
    await waitFor(() =>
      expect(screen.getByTestId('available-balance')).toHaveTextContent('$1,000.00'),
    )
    expect(toasts.error).toHaveBeenCalled()

    api.thawListRefetch()
  })
})

describe('no double-row flash', () => {
  /**
   * The optimistic row must be replaced by id, not joined by the server's copy
   * and tidied up later. Sampling the row count on every render is what catches
   * a duplicate that exists for only a frame.
   */
  it('never shows the same transfer twice, at any point', async () => {
    const user = userEvent.setup()
    const api = stubApi({ holdPost: true })

    renderWithProviders(<TransfersPage />)
    await waitFor(() => expect(rows()).toHaveLength(1))

    // Frozen so the reconciling refetch cannot tidy up a duplicate before it is
    // observed. Only the onSuccess replacement can keep the count correct here.
    api.freezeListRefetch()

    await fillAndSubmit(user)
    await waitFor(() => expect(rows()).toHaveLength(2))

    const optimisticId = rows()[0].getAttribute('data-transfer-id')
    expect(optimisticId).toMatch(/^optimistic:/)

    api.settlePost({ ok: true, body: confirmedTransfer(25_000) })

    // The optimistic row became the server's row rather than being joined by it.
    await waitFor(() => expect(rows()[0]).toHaveAttribute('data-transfer-id', 't-server-1'))
    expect(rows()).toHaveLength(2)
    expect(
      rows().filter((row) => row.getAttribute('data-transfer-id')?.startsWith('optimistic:')),
    ).toHaveLength(0)

    // And it carries the server's counterparty, not the id the optimistic row
    // had to guess with.
    expect(within(rows()[0]).getByText('bob@payvero.dev')).toBeInTheDocument()

    api.thawListRefetch()
    await waitFor(() => expect(rows()).toHaveLength(2))
  })

  it('shows the optimistic row as unconfirmed until the server answers', async () => {
    const user = userEvent.setup()
    const api = stubApi({ holdPost: true })

    renderWithProviders(<TransfersPage />)
    await waitFor(() => expect(rows()).toHaveLength(1))
    await fillAndSubmit(user)

    await waitFor(() => expect(screen.getByText('sending')).toBeInTheDocument())

    api.settlePost({ ok: true, body: confirmedTransfer(25_000) })

    await waitFor(() => expect(screen.queryByText('sending')).not.toBeInTheDocument())
    expect(screen.getByText('pending')).toBeInTheDocument()
  })
})

describe('idempotency', () => {
  it('sends an Idempotency-Key on every create', async () => {
    const user = userEvent.setup()
    const api = stubApi()

    renderWithProviders(<TransfersPage />)
    await waitFor(() => expect(rows()).toHaveLength(1))
    await fillAndSubmit(user)

    await waitFor(() => expect(api.postCount()).toBe(1))
    expect(api.sentKeys[0]).toMatch(/^[0-9a-f-]{36}$/)
  })

  /**
   * The point of the retry affordance. Pressing "try again" must replay the
   * same request under the same key, so the server answers with the original
   * outcome rather than moving money a second time.
   */
  it('reuses the same key when the user retries after a failure', async () => {
    const user = userEvent.setup()
    let failNext = true
    const sentKeys: string[] = []
    let posts = 0

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
        const path = String(input)
        if (path === '/api/transfers' && init.method === 'POST') {
          posts += 1
          sentKeys.push(new Headers(init.headers).get('Idempotency-Key') ?? '')
          if (failNext) {
            failNext = false
            return json({ status: 500, error: 'INTERNAL', message: 'Server error', fieldErrors: {} }, 500)
          }
          return json(confirmedTransfer(25_000), 201)
        }
        if (path.startsWith('/api/transfers')) return json(paged([existingTransfer()]))
        if (path === '/api/accounts') return json([ACCOUNT])
        if (path.includes('/balance')) {
          return json({
            accountId: ACCOUNT.id,
            currency: 'USD',
            derivedBalanceMinorUnits: 100_000,
            cachedBalanceMinorUnits: 100_000,
            consistent: true,
          })
        }
        return json({}, 404)
      }),
    )

    renderWithProviders(<TransfersPage />)
    await waitFor(() => expect(rows()).toHaveLength(1))

    await fillAndSubmit(user)
    await waitFor(() => expect(posts).toBe(1))
    await screen.findByRole('button', { name: 'Try again' })

    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(posts).toBe(2))

    // Same intent, same key: the server can recognise the retry.
    expect(sentKeys[1]).toBe(sentKeys[0])
    expect(sentKeys[0]).not.toBe('')
  })

  it('mints a new key once the inputs change, since that is a different request', async () => {
    const user = userEvent.setup()
    const api = stubApi()

    renderWithProviders(<TransfersPage />)
    await waitFor(() => expect(rows()).toHaveLength(1))

    await fillAndSubmit(user, '250.00')
    await waitFor(() => expect(api.postCount()).toBe(1))

    await user.type(screen.getByLabelText('Recipient account id'), RECIPIENT)
    await user.type(screen.getByLabelText('Amount (USD)'), '99.00')
    await user.click(screen.getByRole('button', { name: /send transfer|try again/i }))

    await waitFor(() => expect(api.postCount()).toBe(2))
    // Reusing the first key here would be refused by the server as a conflict.
    expect(api.sentKeys[1]).not.toBe(api.sentKeys[0])
  })
})

describe('settlement becomes visible without a permanent poll', () => {
  it('polls while a transfer is pending and stops once nothing is', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

    let serverStatus: Transfer['status'] = 'PENDING'
    let gets = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
        const path = String(input)
        if (path === '/api/transfers' && init.method === 'POST') {
          return json(confirmedTransfer(25_000, 'PENDING'), 201)
        }
        if (path.startsWith('/api/transfers')) {
          gets += 1
          return json(paged([confirmedTransfer(25_000, serverStatus)]))
        }
        if (path === '/api/accounts') return json([ACCOUNT])
        if (path.includes('/balance')) {
          return json({
            accountId: ACCOUNT.id,
            currency: 'USD',
            derivedBalanceMinorUnits: 75_000,
            cachedBalanceMinorUnits: 75_000,
            consistent: true,
          })
        }
        return json({}, 404)
      }),
    )

    renderWithProviders(<TransfersPage />)
    await waitFor(() => expect(screen.getByText('pending')).toBeInTheDocument())

    const whilePending = gets
    await vi.advanceTimersByTimeAsync(5_000)
    expect(gets).toBeGreaterThan(whilePending)

    // The server settles it; the UI picks that up on the next poll.
    serverStatus = 'SETTLED'
    await vi.advanceTimersByTimeAsync(3_000)
    await waitFor(() => expect(screen.queryByText('pending')).not.toBeInTheDocument())

    // Nothing pending any more, so the polling stops rather than running on.
    const afterSettled = gets
    await vi.advanceTimersByTimeAsync(10_000)
    expect(gets).toBe(afterSettled)

    vi.useRealTimers()
    void user
  })
})

describe('list states', () => {
  it('shows a skeleton while the list loads', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const path = String(input)
        if (path === '/api/accounts') return json([ACCOUNT])
        if (path.startsWith('/api/transfers')) return new Promise<Response>(() => {})
        if (path.includes('/balance')) {
          return json({
            accountId: ACCOUNT.id,
            currency: 'USD',
            derivedBalanceMinorUnits: 100_000,
            cachedBalanceMinorUnits: 100_000,
            consistent: true,
          })
        }
        return json({}, 404)
      }),
    )

    renderWithProviders(<TransfersPage />)
    expect(await screen.findByTestId('transfer-list-skeleton')).toBeInTheDocument()
  })

  it('explains an empty list rather than showing nothing', async () => {
    stubApi({ transfers: [] })

    renderWithProviders(<TransfersPage />)

    expect(await screen.findByText('No transfers yet')).toBeInTheDocument()
    expect(rows()).toHaveLength(0)
  })

  /**
   * The state a brand new sign-up lands in, and the one the seeded demo can
   * never reach — which is exactly why it is asserted rather than assumed.
   */
  it('explains that an account is needed before offering a send form', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input) === '/api/accounts') return json([])
        return json({}, 404)
      }),
    )

    renderWithProviders(<TransfersPage />)

    expect(await screen.findByText('No account yet')).toBeInTheDocument()
    expect(screen.getByText('You need an account before you can send money.')).toBeInTheDocument()
    // No form to fill in, because there is nothing it could send from.
    expect(screen.queryByLabelText('Recipient account id')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /send transfer/i })).not.toBeInTheDocument()
  })
})
