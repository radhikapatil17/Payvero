import { describe, expect, it } from 'vitest'
import { buildPeriods, formatPeriod, isInPeriod } from './buildPeriods'
import type { Statement } from '@/lib/api/types'

function statement(period: string): Statement {
  return {
    id: `s-${period}`,
    accountId: 'a-1',
    period,
    openingBalanceMinorUnits: 0,
    closingBalanceMinorUnits: 10_000,
    netMovementMinorUnits: 10_000,
    entryCount: 2,
    lineItems: [],
    generatedAt: '2026-08-01T00:00:00Z',
  }
}

const NOW = new Date('2026-08-19T12:00:00Z')

describe('buildPeriods', () => {
  it('marks the running month open rather than leaving it out', () => {
    const periods = buildPeriods('2026-06-01T00:00:00Z', NOW, [
      statement('2026-06'),
      statement('2026-07'),
    ])

    expect(periods.map((period) => [period.label, period.state])).toEqual([
      ['2026-08', 'open'],
      ['2026-07', 'issued'],
      ['2026-06', 'issued'],
    ])
    // No statement for the open month, and that is the correct state, not a gap.
    expect(periods[0].statement).toBeUndefined()
  })

  it('distinguishes a closed month with no statement from the open one', () => {
    const periods = buildPeriods('2026-05-01T00:00:00Z', NOW, [statement('2026-06')])

    expect(periods.map((period) => [period.label, period.state])).toEqual([
      ['2026-08', 'open'],
      ['2026-07', 'available'],
      ['2026-06', 'issued'],
      ['2026-05', 'available'],
    ])
  })

  it('shows a single open period for an account opened this month', () => {
    const periods = buildPeriods('2026-08-03T09:00:00Z', NOW, [])

    expect(periods).toHaveLength(1)
    expect(periods[0]).toMatchObject({ label: '2026-08', state: 'open' })
  })

  it('spans a year boundary without losing a month', () => {
    const periods = buildPeriods('2025-11-01T00:00:00Z', new Date('2026-01-15T00:00:00Z'), [])

    expect(periods.map((period) => period.label)).toEqual(['2026-01', '2025-12', '2025-11'])
    expect(periods[0].state).toBe('open')
  })

  it('surfaces a statement dated before the account rather than hiding it', () => {
    const periods = buildPeriods('2026-07-01T00:00:00Z', NOW, [statement('2026-03')])

    // Keeping it visible means the discrepancy is noticed instead of swallowed.
    expect(periods.map((period) => period.label)).toContain('2026-03')
  })
})

describe('formatPeriod', () => {
  it('reads as a month a person would say out loud', () => {
    expect(formatPeriod('2026-08')).toBe('August 2026')
    expect(formatPeriod('2025-12')).toBe('December 2025')
  })
})

describe('isInPeriod', () => {
  it('matches on the UTC month, like the server', () => {
    expect(isInPeriod('2026-08-19T23:30:00Z', '2026-08')).toBe(true)
    expect(isInPeriod('2026-07-31T23:59:59Z', '2026-08')).toBe(false)
  })
})
