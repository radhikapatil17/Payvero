import type { Statement } from '@/lib/api/types'

/**
 * `open`      — the month currently running. No statement exists, and that is
 *               correct rather than missing: a statement is immutable, so
 *               issuing one for a month that can still receive entries would
 *               record figures nothing could later correct.
 * `issued`    — closed, and the immutable artifact exists.
 * `available` — closed, but no statement has been issued for it yet.
 */
export type PeriodState = 'open' | 'issued' | 'available'

export type Period = {
  /** YYYY-MM */
  label: string
  state: PeriodState
  statement?: Statement
}

/**
 * Every month the account has existed, newest first.
 *
 * Derived from the account's own lifetime rather than from the statements that
 * happen to exist, because the interesting states are the ones with no
 * statement: the month still running, and a closed month nobody has generated
 * yet. Listing only what exists would make both of those invisible.
 */
export function buildPeriods(
  accountCreatedAt: string,
  now: Date,
  statements: readonly Statement[],
): Period[] {
  const byLabel = new Map(statements.map((statement) => [statement.period, statement]))

  const currentLabel = labelOf(now.getUTCFullYear(), now.getUTCMonth() + 1)
  const created = new Date(accountCreatedAt)

  let year = created.getUTCFullYear()
  let month = created.getUTCMonth() + 1

  const periods: Period[] = []
  // Guarded rather than while(true): a malformed date should not spin.
  for (let step = 0; step < 600; step++) {
    const label = labelOf(year, month)
    periods.push({
      label,
      state: label === currentLabel ? 'open' : byLabel.has(label) ? 'issued' : 'available',
      statement: byLabel.get(label),
    })
    if (label === currentLabel) break
    month += 1
    if (month > 12) {
      month = 1
      year += 1
    }
  }

  // A statement for a month before the account's own creation should still be
  // shown rather than silently dropped: it means one of the two is wrong, and
  // hiding it would hide the discrepancy.
  for (const statement of statements) {
    if (!periods.some((period) => period.label === statement.period)) {
      periods.push({ label: statement.period, state: 'issued', statement })
    }
  }

  return periods.sort((a, b) => b.label.localeCompare(a.label))
}

function labelOf(year: number, month: number) {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`
}

const MONTH_LABEL = new Intl.DateTimeFormat('en-US', {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
})

export function formatPeriod(label: string): string {
  const [year, month] = label.split('-').map(Number)
  return MONTH_LABEL.format(new Date(Date.UTC(year, month - 1, 1)))
}

/** True when the transfer falls inside the period, in UTC like the server. */
export function isInPeriod(isoTimestamp: string, label: string): boolean {
  return isoTimestamp.slice(0, 7) === label
}
