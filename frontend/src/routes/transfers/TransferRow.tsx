import { ArrowDownLeft, ArrowUpRight, Loader2 } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { isOptimistic } from '@/lib/api/useCreateTransfer'
import { formatSignedMoney } from '@/lib/money'
import { cn } from '@/lib/utils'
import type { Transfer, TransferStatus } from '@/lib/api/types'

const WHEN = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})

const STATUS_STYLES: Record<TransferStatus, string> = {
  SETTLED: 'text-muted-foreground',
  PENDING: 'border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400',
  FLAGGED: 'border-destructive/40 bg-destructive/10 text-destructive',
  FAILED: 'text-muted-foreground',
}

export function TransferRow({ transfer }: { transfer: Transfer }) {
  const incoming = transfer.direction === 'CREDIT'
  const Icon = incoming ? ArrowDownLeft : ArrowUpRight
  const unconfirmed = isOptimistic(transfer)

  return (
    <li
      data-testid="transfer-row"
      data-transfer-id={transfer.id}
      data-status={transfer.status}
      // Dimmed while the server has not confirmed it, so an optimistic row
      // never quite passes for a settled fact.
      className={cn('flex items-center gap-3 py-3', unconfirmed && 'opacity-60')}
    >
      <span
        aria-hidden
        className={cn(
          'flex size-8 shrink-0 items-center justify-center rounded-full',
          incoming ? 'bg-emerald-500/10 text-emerald-600' : 'bg-muted text-muted-foreground',
        )}
      >
        {unconfirmed ? <Loader2 className="size-4 animate-spin" /> : <Icon className="size-4" />}
      </span>

      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{transfer.counterpartyLabel}</p>
        <p className="text-xs text-muted-foreground">
          <time dateTime={transfer.createdAt}>{WHEN.format(new Date(transfer.createdAt))}</time>
          {transfer.failureReason ? ` · ${transfer.failureReason}` : null}
        </p>
      </div>

      {transfer.status !== 'SETTLED' ? (
        <Badge variant="outline" className={cn('text-xs', STATUS_STYLES[transfer.status])}>
          {unconfirmed ? 'sending' : transfer.status.toLowerCase()}
        </Badge>
      ) : null}

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
