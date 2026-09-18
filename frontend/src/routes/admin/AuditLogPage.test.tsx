import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuditLogPage } from './AuditLogPage'
import { __resetRefreshStateForTests } from '@/lib/api/client'
import { tokens } from '@/lib/api/tokens'
import { renderWithProviders } from '@/test/renderWithProviders'
import type { AuditLogEntry, Paged } from '@/lib/api/types'

function entry(overrides: Partial<AuditLogEntry> = {}): AuditLogEntry {
  return {
    id: 'a-1',
    eventId: 'e-1',
    eventType: 'TRANSFER_CREATED',
    aggregateType: 'TRANSFER',
    aggregateId: '3f2a1b09-0000-0000-0000-000000000000',
    actor: 'alice@payvero.dev',
    payload: { transferId: '3f2a1b09', amountMinorUnits: 25_000, currency: 'USD' },
    kafkaTopic: 'transfer.events',
    kafkaPartition: 0,
    kafkaOffset: 41,
    recordedAt: '2026-08-19T09:00:00Z',
    ...overrides,
  }
}

const DECISION = entry({
  id: 'a-2',
  eventId: 'e-2',
  eventType: 'FRAUD_FLAG_CONFIRMED',
  aggregateType: 'FRAUD_FLAG',
  aggregateId: '7c4e2d55-0000-0000-0000-000000000000',
  actor: 'admin@payvero.dev',
  payload: { flagId: '7c4e2d55', decision: 'CONFIRMED', rule: 'VELOCITY_COUNT' },
  recordedAt: '2026-08-19T10:00:00Z',
})

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function paged(content: AuditLogEntry[]): Paged<AuditLogEntry> {
  return { content, page: { size: 50, number: 0, totalElements: content.length, totalPages: 1 } }
}

function stubApi(entries: AuditLogEntry[] = [entry(), DECISION]) {
  const requested: (string | null)[] = []

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.startsWith('/api/admin/audit-log')) {
        const type = new URL(path, 'http://x').searchParams.get('aggregateType')
        requested.push(type)
        return json(paged(type ? entries.filter((e) => e.aggregateType === type) : entries))
      }
      return json({}, 404)
    }),
  )

  return { requested }
}

async function renderLog(entries?: AuditLogEntry[]) {
  const harness = stubApi(entries)
  renderWithProviders(<AuditLogPage />)
  await waitFor(() => expect(screen.queryByTestId('audit-skeleton')).not.toBeInTheDocument())
  return harness
}

function rowFor(text: RegExp) {
  const rows = screen.getAllByTestId('audit-row')
  const found = rows.find((row) => text.test(row.textContent ?? ''))
  if (!found) throw new Error(`no audit row matching ${text}`)
  return within(found)
}

beforeEach(() => {
  vi.unstubAllGlobals()
  __resetRefreshStateForTests()
  tokens.clear()
})

describe('who did what and when', () => {
  it('names the actor, the action, and the time for each event', async () => {
    await renderLog()

    const transfer = rowFor(/moved money/)
    expect(transfer.getByText('alice@payvero.dev')).toBeInTheDocument()
    expect(transfer.getByText('moved money')).toBeInTheDocument()
    expect(transfer.getByText(/Aug 19, 2026/)).toBeInTheDocument()
  })

  /**
   * The fraud decisions are the events most worth attributing, so their
   * presence in the trail is asserted directly rather than assumed from the
   * transfer case passing.
   */
  it('includes the fraud decisions, attributed to the admin who made them', async () => {
    await renderLog()

    const decision = rowFor(/confirmed a fraud flag/)
    expect(decision.getByText('admin@payvero.dev')).toBeInTheDocument()
    expect(decision.getByText('confirmed a fraud flag')).toBeInTheDocument()
  })

  /**
   * Null means the event named nobody. A blank cell reads as data that failed
   * to load, which is a different and misleading claim.
   */
  it('says "system" rather than leaving the actor blank when no person acted', async () => {
    await renderLog([entry({ actor: null })])

    expect(rowFor(/moved money/).getByText('system')).toBeInTheDocument()
  })

  it('shows the recorded event itself, and where it was delivered from', async () => {
    await renderLog()
    const user = userEvent.setup()

    expect(screen.queryByTestId('audit-payload')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /moved money/i }))

    const payload = within(screen.getByTestId('audit-payload'))
    expect(payload.getByText(/"amountMinorUnits": 25000/)).toBeInTheDocument()
    expect(payload.getByText(/transfer\.events/)).toBeInTheDocument()
    expect(payload.getByText(/offset 41/)).toBeInTheDocument()
  })
})

describe('the append-only property', () => {
  /**
   * The user-visible half of the project's thesis. The guarantee is enforced by
   * a database trigger, which nobody reading this screen will ever see, so the
   * screen has to say it.
   */
  it('states that the rows cannot be edited or removed, and that the database enforces it', async () => {
    await renderLog()

    const banner = screen.getByText(/append-only/i).closest('div')
    expect(banner).not.toBeNull()
    expect(banner).toHaveTextContent(/UPDATE/)
    expect(banner).toHaveTextContent(/DELETE/)
    expect(banner).toHaveTextContent(/database rejects/i)
    expect(banner).toHaveTextContent(/correction is a new event/i)
  })

  it('offers no control that would edit or delete a row', async () => {
    await renderLog()

    const actions = screen.getAllByRole('button').map((button) => button.textContent ?? '')
    expect(actions.join(' ')).not.toMatch(/edit|delete|remove|revert|undo/i)
  })
})

describe('filtering and loading', () => {
  it('asks the server for one aggregate type rather than filtering on the client', async () => {
    const harness = await renderLog()
    const user = userEvent.setup()

    await user.click(screen.getByRole('tab', { name: 'Fraud decisions' }))

    await waitFor(() => expect(harness.requested).toContain('FRAUD_FLAG'))
    await waitFor(() => expect(screen.getAllByTestId('audit-row')).toHaveLength(1))
    expect(screen.getByText('confirmed a fraud flag')).toBeInTheDocument()
  })

  it('shows a skeleton before the trail arrives', async () => {
    stubApi()
    renderWithProviders(<AuditLogPage />)

    expect(screen.getByTestId('audit-skeleton')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByTestId('audit-skeleton')).not.toBeInTheDocument())
  })

  it('explains an empty trail instead of showing a bare table', async () => {
    await renderLog([])

    expect(screen.getByText(/nothing recorded yet/i)).toBeInTheDocument()
    expect(screen.queryAllByTestId('audit-row')).toHaveLength(0)
  })
})
