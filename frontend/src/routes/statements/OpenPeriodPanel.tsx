import { CalendarClock } from 'lucide-react'
import { formatPeriod, isInPeriod } from './buildPeriods'
import { ActivityList } from '@/routes/dashboard/ActivityList'
import { EmptyState } from '@/components/EmptyState'
import { Skeleton } from '@/components/ui/skeleton'
import { useTransfers } from '@/lib/api/queries'
import { formatMoney } from '@/lib/money'

/**
 * What a month that has not finished shows instead of a statement.
 *
 * The absence of a statement here is a fact about the period, not a failure to
 * load one, so it is stated plainly and the live activity is offered in its
 * place. The two are deliberately not made to look alike: a statement is an
 * immutable record of a closed month, and this is a running total that will
 * keep changing until the month ends.
 */
export function OpenPeriodPanel({ period, currency }: { period: string; currency: string }) {
  const transfers = useTransfers(0, 50)

  const inPeriod = (transfers.data?.content ?? []).filter((transfer) =>
    isInPeriod(transfer.createdAt, period),
  )
  const net = inPeriod.reduce(
    (total, transfer) =>
      total + (transfer.direction === 'CREDIT' ? transfer.amountMinorUnits : -transfer.amountMinorUnits),
    0,
  )

  return (
    <div data-testid="open-period-panel" className="space-y-4">
      <div className="flex items-start gap-3 rounded-lg border border-dashed px-4 py-3">
        <CalendarClock aria-hidden className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <div className="space-y-1">
          <p className="text-sm font-medium">{formatPeriod(period)} is still open</p>
          <p className="text-sm text-muted-foreground">
            A statement is issued once the month ends, and cannot change afterwards. Until then,
            here is the activity so far.
          </p>
        </div>
      </div>

      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
        <Figure label="Movements so far" value={String(inPeriod.length)} />
        <Figure
          label="Net so far"
          value={`${net >= 0 ? '+' : '−'}${formatMoney(Math.abs(net), currency)}`}
        />
        <Figure label="Statement" value="Not yet issued" muted />
      </dl>

      {transfers.isPending ? (
        <Skeleton data-testid="open-period-skeleton" className="h-40 w-full" />
      ) : inPeriod.length > 0 ? (
        <ActivityList transfers={inPeriod} />
      ) : (
        <EmptyState
          title="Nothing this month yet"
          description="Activity in this period will appear here as it happens."
        />
      )}
    </div>
  )
}

function Figure({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd
        className={
          muted
            ? 'text-sm text-muted-foreground'
            : 'text-lg font-semibold tabular-nums tracking-tight'
        }
      >
        {value}
      </dd>
    </div>
  )
}
