import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/utils'
import type { Decision } from '@/lib/api/useReviewFlag'
import type { FraudFlag } from '@/lib/api/types'

const WHEN = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

const RULE_LABEL: Record<FraudFlag['rule'], string> = {
  VELOCITY_COUNT: 'Too many transfers in a short window',
  VELOCITY_AMOUNT: 'Too much value moved in a short window',
}

const STATUS_STYLE: Record<FraudFlag['status'], string> = {
  OPEN: 'border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400',
  CONFIRMED: 'border-destructive/40 bg-destructive/10 text-destructive',
  CLEARED: 'text-muted-foreground',
}

export function FlagCard({
  flag,
  onDecide,
  /** The decision in flight on *this* flag, if any. */
  pending,
  disabled,
}: {
  flag: FraudFlag
  onDecide: (flagId: string, decision: Decision) => void
  pending: Decision | null
  disabled: boolean
}) {
  const open = flag.status === 'OPEN'
  const { details } = flag

  return (
    <Card data-testid="flag-card">
      <CardContent className="space-y-4 pt-6">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="space-y-1">
            <p className="font-medium">{RULE_LABEL[flag.rule]}</p>
            <p className="text-xs text-muted-foreground">
              Raised <time dateTime={flag.createdAt}>{WHEN.format(new Date(flag.createdAt))}</time>{' '}
              on transfer <span className="font-mono">{flag.transferId.slice(0, 8)}</span>
            </p>
          </div>
          <Badge variant="outline" className={cn('text-xs', STATUS_STYLE[flag.status])}>
            {flag.status.toLowerCase()}
          </Badge>
        </div>

        {/*
          What the rule saw, against what it allows. A reviewer should be able
          to judge without rerunning the rule or reading its source.
        */}
        <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-md bg-muted/40 px-4 py-3 text-sm sm:grid-cols-4">
          <Observed
            label="Transfers"
            observed={String(details.observedTransferCount)}
            limit={String(details.maxTransfersPerWindow)}
            tripped={flag.rule === 'VELOCITY_COUNT'}
          />
          <Observed
            label="Value"
            observed={formatMoney(details.observedAmountMinorUnits)}
            limit={formatMoney(details.maxAmountPerWindow)}
            tripped={flag.rule === 'VELOCITY_AMOUNT'}
          />
          <div className="col-span-2">
            <dt className="text-xs text-muted-foreground">Window</dt>
            <dd>{details.windowSeconds} seconds</dd>
          </div>
        </dl>

        {/*
          Only the button that was actually clicked reports progress. Both
          showing "Recording…" would leave a reviewer unable to tell which
          verdict is being written — the one question that matters here.
        */}
        {open ? (
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              disabled={disabled}
              onClick={() => onDecide(flag.id, 'clear')}
            >
              {pending === 'clear' ? 'Recording…' : 'Clear — legitimate'}
            </Button>
            <Button
              variant="destructive"
              disabled={disabled}
              onClick={() => onDecide(flag.id, 'confirm')}
            >
              {pending === 'confirm' ? 'Recording…' : 'Confirm — fraudulent'}
            </Button>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">
            Decided{' '}
            {flag.reviewedAt ? (
              <time dateTime={flag.reviewedAt}>{WHEN.format(new Date(flag.reviewedAt))}</time>
            ) : null}
            . This decision is recorded in the audit log and cannot be taken back.
          </p>
        )}
      </CardContent>
    </Card>
  )
}

function Observed({
  label,
  observed,
  limit,
  tripped,
}: {
  label: string
  observed: string
  limit: string
  tripped: boolean
}) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={cn('tabular-nums', tripped && 'font-semibold text-destructive')}>
        {observed} <span className="text-xs font-normal text-muted-foreground">of {limit}</span>
      </dd>
    </div>
  )
}
