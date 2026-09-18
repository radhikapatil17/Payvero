import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { FraudQueuePage } from './FraudQueuePage'
import { __resetRefreshStateForTests } from '@/lib/api/client'
import { tokens } from '@/lib/api/tokens'
import { renderWithProviders } from '@/test/renderWithProviders'
import type { FraudFlag, Paged } from '@/lib/api/types'

const ADMIN = 'admin@payvero.dev'

function flag(overrides: Partial<FraudFlag> = {}): FraudFlag {
  return {
    id: 'f-1',
    transferId: '9a7d3c11-0000-0000-0000-000000000000',
    rule: 'VELOCITY_COUNT',
    status: 'OPEN',
    details: {
      rule: 'VELOCITY_COUNT',
      windowSeconds: 60,
      observedTransferCount: 12,
      observedAmountMinorUnits: 450_000,
      maxTransfersPerWindow: 10,
      maxAmountPerWindow: 1_000_000,
    },
    reviewedBy: null,
    reviewedAt: null,
    createdAt: '2026-08-19T09:00:00Z',
    ...overrides,
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function paged(content: FraudFlag[]): Paged<FraudFlag> {
  return { content, page: { size: 50, number: 0, totalElements: content.length, totalPages: 1 } }
}

type Harness = {
  postCount: () => number
  queueReads: () => number
  /** Resolves a held POST when the test decides to. */
  settle: (outcome: { ok: true; body: FraudFlag } | { ok: false; status: number; body: unknown }) => void
}

/**
 * `serverFlags` is a function so a test can change what the server returns
 * between the first load and the refetch — which is exactly what happens when
 * another admin decides a flag while this queue is on screen.
 */
function stubApi({
  serverFlags,
  reviewFails,
  holdPost = false,
}: {
  serverFlags: () => FraudFlag[]
  reviewFails?: { status: number; body: unknown }
  holdPost?: boolean
}): Harness {
  let posts = 0
  let queueReads = 0
  let release: ((value: Response) => void) | null = null

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const path = String(input)

      if (path.includes('/api/admin/fraud-flags/') && init.method === 'POST') {
        posts += 1
        if (holdPost) {
          return new Promise<Response>((resolve) => {
            release = resolve
          })
        }
        if (reviewFails) return json(reviewFails.body, reviewFails.status)
        return json(flag({ status: 'CLEARED', reviewedBy: 'u-admin', reviewedAt: '2026-08-19T10:00:00Z' }))
      }

      if (path.startsWith('/api/admin/fraud-flags')) {
        queueReads += 1
        const status = new URL(path, 'http://x').searchParams.get('status')
        const all = serverFlags()
        return json(paged(status ? all.filter((f) => f.status === status) : all))
      }
      return json({}, 404)
    }),
  )

  return {
    postCount: () => posts,
    queueReads: () => queueReads,
    settle: (outcome) => {
      release?.(outcome.ok ? json(outcome.body) : json(outcome.body, outcome.status))
      release = null
    },
  }
}

const ALREADY_REVIEWED = {
  status: 409,
  body: {
    timestamp: '2026-08-19T10:00:00Z',
    status: 409,
    error: 'FRAUD_FLAG_ALREADY_REVIEWED',
    message: 'Flag has already been reviewed',
    fieldErrors: {},
  },
}

async function renderQueue() {
  renderWithProviders(<FraudQueuePage />)
  await waitFor(() => expect(screen.queryByTestId('fraud-skeleton')).not.toBeInTheDocument())
}

beforeEach(() => {
  vi.unstubAllGlobals()
  __resetRefreshStateForTests()
  tokens.clear()
})

describe('two admins reviewing the same flag', () => {
  /**
   * The check this whole page has to survive. The server refuses the second
   * decision; the loser must be told that plainly and must end up looking at
   * the decision that actually won, not at their own.
   */
  it('explains the loss and replaces the row with the decision that won', async () => {
    // Another admin confirms it between this tab's first load and its click.
    let serverState: FraudFlag[] = [flag()]
    stubApi({ serverFlags: () => serverState, reviewFails: ALREADY_REVIEWED })
    await renderQueue()

    expect(screen.getByTestId('flag-card')).toBeInTheDocument()

    serverState = [
      flag({ status: 'CONFIRMED', reviewedBy: 'u-other', reviewedAt: '2026-08-19T09:59:00Z' }),
    ]

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /clear — legitimate/i }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/another admin already reviewed this flag/i)
    expect(alert).toHaveTextContent(/was not recorded/i)
    // Neither the machine code nor the server's terse wording reaches the user.
    expect(alert).not.toHaveTextContent('FRAUD_FLAG_ALREADY_REVIEWED')
    expect(alert).not.toHaveTextContent('Flag has already been reviewed')

    // The default filter is OPEN, and the winning decision was CONFIRMED, so
    // the row leaves this view entirely rather than sitting there still OPEN.
    await waitFor(() => expect(screen.queryByTestId('flag-card')).not.toBeInTheDocument())
  })

  /**
   * A conflict is not a rejected form: the queue is stale, so it has to be
   * re-read. Without this the loser keeps looking at a row the server has
   * already decided and can click it again forever.
   */
  it('refetches the queue after losing, not only after winning', async () => {
    const harness = stubApi({ serverFlags: () => [flag()], reviewFails: ALREADY_REVIEWED })
    await renderQueue()

    const readsBefore = harness.queueReads()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /confirm — fraudulent/i }))

    await screen.findByRole('alert')
    await waitFor(() => expect(harness.queueReads()).toBeGreaterThan(readsBefore))
  })

  /**
   * The same race started from one browser. Both buttons lock while a decision
   * is in flight, so a double click cannot spend two decisions on one flag.
   */
  it('locks the actions while a decision is in flight so one flag gets one POST', async () => {
    const harness = stubApi({ serverFlags: () => [flag()], holdPost: true })
    await renderQueue()

    const user = userEvent.setup()
    const clear = screen.getByRole('button', { name: /clear — legitimate/i })
    await user.click(clear)

    await waitFor(() => expect(clear).toBeDisabled())

    // Only the clicked decision reports progress, so a reviewer can see which
    // verdict is being written; the other is locked but still labelled.
    expect(screen.getByRole('button', { name: /recording/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /confirm — fraudulent/i })).toBeDisabled()

    // A determined second click lands on a disabled control and does nothing.
    await user.click(clear)
    expect(harness.postCount()).toBe(1)

    harness.settle({ ok: true, body: flag({ status: 'CLEARED' }) })
    await waitFor(() => expect(harness.postCount()).toBe(1))
  })
})

describe('the queue', () => {
  it('shows what the rule observed against what it allows', async () => {
    stubApi({ serverFlags: () => [flag()] })
    await renderQueue()

    const card = within(screen.getByTestId('flag-card'))
    expect(card.getByText(/too many transfers in a short window/i)).toBeInTheDocument()
    expect(card.getByText('12')).toBeInTheDocument()
    expect(card.getByText(/of 10/)).toBeInTheDocument()
    expect(card.getByText('60 seconds')).toBeInTheDocument()
  })

  it('records a successful decision and drops the flag out of the open queue', async () => {
    let serverState: FraudFlag[] = [flag()]
    stubApi({ serverFlags: () => serverState })
    await renderQueue()

    const user = userEvent.setup()
    serverState = [flag({ status: 'CLEARED', reviewedBy: ADMIN, reviewedAt: '2026-08-19T10:00:00Z' })]
    await user.click(screen.getByRole('button', { name: /clear — legitimate/i }))

    await waitFor(() => expect(screen.queryByTestId('flag-card')).not.toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('offers no decision buttons on a flag that is already decided', async () => {
    stubApi({
      serverFlags: () => [
        flag({ status: 'CONFIRMED', reviewedBy: ADMIN, reviewedAt: '2026-08-19T09:30:00Z' }),
      ],
    })
    renderWithProviders(<FraudQueuePage />)
    await waitFor(() => expect(screen.queryByTestId('fraud-skeleton')).not.toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('tab', { name: 'All' }))

    const card = within(await screen.findByTestId('flag-card'))
    expect(card.queryByRole('button', { name: /clear|confirm/i })).not.toBeInTheDocument()
    expect(card.getByText(/cannot be taken back/i)).toBeInTheDocument()
  })

  it('says an empty open queue is a good state, not a missing one', async () => {
    stubApi({ serverFlags: () => [] })
    await renderQueue()

    expect(screen.getByText(/nothing waiting for review/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  /**
   * Each filter says something different about what "empty" means here, and a
   * shared "no results" would be wrong for all four. Asserted against genuinely
   * empty fixtures, because the seeded demo has a flag in every state and would
   * hide all of them.
   */
  it.each([
    ['Confirmed', /no confirmed flags/i, /judged fraudulent/i],
    ['Cleared', /no cleared flags/i, /judged legitimate/i],
    ['All', /no flags have been raised/i, /have not tripped/i],
  ])('explains what empty means on the %s tab', async (tab, title, description) => {
    stubApi({ serverFlags: () => [] })
    await renderQueue()

    const user = userEvent.setup()
    await user.click(screen.getByRole('tab', { name: tab }))

    expect(await screen.findByText(title)).toBeInTheDocument()
    expect(screen.getByText(description)).toBeInTheDocument()
    expect(screen.queryByTestId('flag-card')).not.toBeInTheDocument()
  })

  it('shows a skeleton before the queue arrives', async () => {
    stubApi({ serverFlags: () => [flag()] })
    renderWithProviders(<FraudQueuePage />)

    expect(screen.getByTestId('fraud-skeleton')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByTestId('fraud-skeleton')).not.toBeInTheDocument())
  })
})
