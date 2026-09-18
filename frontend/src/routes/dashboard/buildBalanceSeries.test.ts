import { describe, expect, it } from 'vitest'
import { buildBalanceSeries } from './buildBalanceSeries'
import type { Transfer } from '@/lib/api/types'

const DAY_MS = 24 * 60 * 60 * 1000

function transfer(
  createdAt: string,
  direction: 'DEBIT' | 'CREDIT',
  amountMinorUnits: number,
): Transfer {
  return {
    id: `t-${createdAt}-${amountMinorUnits}`,
    sourceAccountId: 'a-1',
    destinationAccountId: 'a-2',
    direction,
    counterpartyLabel: 'bob@payvero.dev',
    counterpartyAccountId: 'a-2',
    amountMinorUnits,
    currency: 'USD',
    status: 'SETTLED',
    failureReason: null,
    createdAt,
    settledAt: createdAt,
  }
}

describe('buildBalanceSeries', () => {
  it('anchors the newest point to the balance the server reports', () => {
    const { points } = buildBalanceSeries(
      [
        transfer('2026-08-01T10:00:00Z', 'CREDIT', 100_000),
        transfer('2026-08-05T10:00:00Z', 'DEBIT', 25_000),
      ],
      75_000,
    )

    expect(points.at(-1)?.balanceMinorUnits).toBe(75_000)
    // Walked backwards: before the debit it was 100,000, and before the credit
    // the account was empty.
    expect(points.map((point) => point.balanceMinorUnits)).toEqual([0, 100_000, 75_000])
  })

  it('opens at the balance before the first movement, so the first change is a step', () => {
    const { points } = buildBalanceSeries(
      [transfer('2026-08-01T10:00:00Z', 'CREDIT', 40_000)],
      140_000,
    )

    // The account already held 100,000 before this page of history begins.
    expect(points[0].balanceMinorUnits).toBe(100_000)
    expect(points[0].timestamp).toBeLessThan(points[1].timestamp)
  })

  describe('sparse data', () => {
    it('draws a flat line when there is no activity at all', () => {
      const { points, domain } = buildBalanceSeries([], 25_000)

      // Not an empty array: a funded account with no movement is a real state
      // and should read as a flat line, not a blank panel.
      expect(points).toHaveLength(2)
      expect(points.every((point) => point.balanceMinorUnits === 25_000)).toBe(true)
      expect(domain[1]).toBeGreaterThan(domain[0])
    })

    it('gives a single movement a domain wide enough to be visible', () => {
      const { points, domain } = buildBalanceSeries(
        [transfer('2026-08-10T12:00:00Z', 'CREDIT', 5_000)],
        5_000,
      )

      expect(points).toHaveLength(2)
      // Both points sit within a second of each other, so a naive domain would
      // be zero-width and the chart would render nothing.
      expect(domain[1] - domain[0]).toBeGreaterThanOrEqual(DAY_MS)
      expect(domain[0]).toBeLessThanOrEqual(points[0].timestamp)
      expect(domain[1]).toBeGreaterThanOrEqual(points.at(-1)!.timestamp)
    })

    it('keeps two movements on the same day inside a drawable domain', () => {
      const { domain } = buildBalanceSeries(
        [
          transfer('2026-08-10T09:00:00Z', 'CREDIT', 5_000),
          transfer('2026-08-10T17:00:00Z', 'DEBIT', 1_000),
        ],
        4_000,
      )

      expect(domain[1] - domain[0]).toBeGreaterThanOrEqual(DAY_MS)
    })

    /**
     * The case a category axis gets wrong: two movements a month apart must sit
     * a month apart, not adjacent. Asserting on the timestamps is what proves
     * the series carries real time rather than an index.
     */
    it('preserves a long gap between movements instead of collapsing it', () => {
      const { points, domain } = buildBalanceSeries(
        [
          transfer('2026-06-01T10:00:00Z', 'CREDIT', 50_000),
          transfer('2026-07-01T10:00:00Z', 'DEBIT', 10_000),
        ],
        40_000,
      )

      const gap = points.at(-1)!.timestamp - points.at(-2)!.timestamp
      expect(gap).toBeGreaterThan(25 * DAY_MS)
      expect(domain[1] - domain[0]).toBeGreaterThan(25 * DAY_MS)
    })
  })

  it('orders points by time even when the API returns newest first', () => {
    const { points } = buildBalanceSeries(
      [
        transfer('2026-08-05T10:00:00Z', 'DEBIT', 25_000),
        transfer('2026-08-01T10:00:00Z', 'CREDIT', 100_000),
      ],
      75_000,
    )

    const timestamps = points.map((point) => point.timestamp)
    expect([...timestamps].sort((a, b) => a - b)).toEqual(timestamps)
    expect(points.at(-1)?.balanceMinorUnits).toBe(75_000)
  })

  it('handles a balance that goes to zero without treating it as missing data', () => {
    const { points } = buildBalanceSeries(
      [transfer('2026-08-02T10:00:00Z', 'DEBIT', 30_000)],
      0,
    )

    expect(points.map((point) => point.balanceMinorUnits)).toEqual([30_000, 0])
  })
})
