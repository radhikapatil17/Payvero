import type { Transfer } from '@/lib/api/types'

export type BalancePoint = {
  /** Epoch millis, so the axis can be a real time scale rather than categories. */
  timestamp: number
  balanceMinorUnits: number
}

export type BalanceSeries = {
  points: BalancePoint[]
  /** Explicit domain, because a single point has no natural extent. */
  domain: [number, number]
}

const DAY_MS = 24 * 60 * 60 * 1000

/**
 * Reconstructs balance over time by walking backwards from the balance we know.
 *
 * The API gives the current balance and the movements that produced it, not a
 * time series, so the series is derived: the balance after the newest transfer
 * is today's balance, and the balance before any transfer is the balance after
 * it minus that transfer's signed amount. Walking backwards means the series is
 * anchored to a figure the server vouches for rather than accumulated forwards
 * from an assumed zero, which would drift the moment a page of history is
 * missing.
 *
 * An opening point is emitted before the first movement so the first change
 * reads as a step rather than as the chart's origin.
 */
export function buildBalanceSeries(
  transfers: readonly Transfer[],
  currentBalanceMinorUnits: number,
): BalanceSeries {
  if (transfers.length === 0) {
    // Nothing has happened, but the account still has a balance worth drawing
    // as a flat line rather than an empty box.
    const now = Date.now()
    return {
      points: [
        { timestamp: now - DAY_MS, balanceMinorUnits: currentBalanceMinorUnits },
        { timestamp: now, balanceMinorUnits: currentBalanceMinorUnits },
      ],
      domain: [now - DAY_MS, now],
    }
  }

  const ascending = [...transfers].sort(
    (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
  )

  const balanceAfter = new Array<number>(ascending.length)
  let running = currentBalanceMinorUnits
  for (let i = ascending.length - 1; i >= 0; i--) {
    balanceAfter[i] = running
    running -= signedAmount(ascending[i])
  }
  const openingBalance = running

  const first = Date.parse(ascending[0].createdAt)
  const points: BalancePoint[] = [
    { timestamp: first - 1, balanceMinorUnits: openingBalance },
    ...ascending.map((transfer, index) => ({
      timestamp: Date.parse(transfer.createdAt),
      balanceMinorUnits: balanceAfter[index],
    })),
  ]

  return { points, domain: domainFor(points) }
}

function signedAmount(transfer: Transfer): number {
  return transfer.direction === 'CREDIT' ? transfer.amountMinorUnits : -transfer.amountMinorUnits
}

/**
 * A domain wide enough to draw in. Points that all share an instant — a single
 * movement, or several in the same second — would otherwise collapse to a zero
 * width axis and render nothing at all.
 */
function domainFor(points: readonly BalancePoint[]): [number, number] {
  const first = points[0].timestamp
  const last = points[points.length - 1].timestamp

  if (last - first < DAY_MS) {
    const midpoint = (first + last) / 2
    return [midpoint - DAY_MS / 2, midpoint + DAY_MS / 2]
  }
  return [first, last]
}
