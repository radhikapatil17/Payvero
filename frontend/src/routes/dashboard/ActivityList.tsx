import { ArrowDownLeft, ArrowUpRight } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { formatSignedMoney } from '@/lib/money'
import { cn } from '@/lib/utils'
import type { Transfer, TransferStatus } from '@/lib/api/types'

const WHEN = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})

/**
 * Colour carries state and nothing else, so a glance finds the unusual rows.
 * Settled is deliberately unstyled: the common case should be quiet.
 */
const STATUS_STYLES: Record<TransferStatus, string> = {
  SETTLED: 'text-muted-foreground',
  PENDING: 'border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400',
  FLAGGED: 'border-destructive/40 bg-destructive/10 text-destructive',
  FAILED: 'text-muted-foreground line-through',
}

export function ActivityRow({ transfer }: { transfer: Transfer }) {
  const incoming = transfer.direction === 'CREDIT'
  const Icon = incoming ? ArrowDownLeft : ArrowUpRight

  return (
    <li className="flex items-center gap-3 py-3">
      <span
        aria-hidden
        className={cn(
          'flex size-8 shrink-0 items-center justify-center rounded-full',
          incoming ? 'bg-emerald-500/10 text-emerald-600' : 'bg-muted text-muted-foreground',
        )}
      >
        <Icon className="size-4" />
      </span>

      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{transfer.counterpartyLabel}</p>
        <p className="text-xs text-muted-foreground">
          <time dateTime={transfer.createdAt}>{WHEN.format(new Date(transfer.createdAt))}</time>
        </p>
      </div>

      {transfer.status !== 'SETTLED' ? (
        <Badge variant="outline" className={cn('text-xs', STATUS_STYLES[transfer.status])}>
          {transfer.status.toLowerCase()}
        </Badge>
      ) : null}

      {/*
        Tabular figures so amounts align down the column, and the sign is in the
        text as well as the colour so direction survives a greyscale print or a
        colour-blind reader.
      */}
      <span
        className={cn(
          'shrink-0 font-medium tabular-nums',
          incoming ? 'text-emerald-600 dark:text-emerald-400' : 'text-foreground',
        )}
      >
        {formatSignedMoney(transfer.amountMinorUnits, transfer.direction, transfer.currency)}
      </span>
    </li>
  )
}

export function ActivityList({ transfers }: { transfers: readonly Transfer[] }) {
  return (
    <ul className="divide-y">
      {transfers.map((transfer) => (
        <ActivityRow key={transfer.id} transfer={transfer} />
      ))}
    </ul>
  )
}
