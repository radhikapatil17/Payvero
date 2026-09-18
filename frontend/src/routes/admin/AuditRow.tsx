import { useState } from 'react'
import { ChevronRight } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { TableCell, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import type { AuditLogEntry } from '@/lib/api/types'

const WHEN = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'medium',
})

/** Event types read as sentences rather than as constants from the wire. */
const DID: Record<string, string> = {
  TRANSFER_CREATED: 'moved money',
  FRAUD_FLAG_CLEARED: 'cleared a fraud flag',
  FRAUD_FLAG_CONFIRMED: 'confirmed a fraud flag',
}

export function AuditRow({ entry }: { entry: AuditLogEntry }) {
  const [open, setOpen] = useState(false)
  const isDecision = entry.aggregateType === 'FRAUD_FLAG'

  return (
    <>
      <TableRow data-testid="audit-row">
        <TableCell>
          {/*
            Null actor is shown as "system" rather than blank, because a blank
            cell reads as missing data. The server returns null when the event
            genuinely names no person, and saying so is more honest than
            attributing it to somebody.
          */}
          {entry.actor ?? <span className="text-muted-foreground">system</span>}
        </TableCell>
        <TableCell>
          <button
            type="button"
            onClick={() => setOpen((wasOpen) => !wasOpen)}
            aria-expanded={open}
            className="flex items-center gap-1.5 text-left hover:underline"
          >
            <ChevronRight
              aria-hidden
              className={cn('size-3.5 shrink-0 transition-transform', open && 'rotate-90')}
            />
            <span>{DID[entry.eventType] ?? entry.eventType}</span>
          </button>
        </TableCell>
        <TableCell>
          <Badge variant="outline" className={cn('text-xs', isDecision && 'text-foreground')}>
            {entry.aggregateType.toLowerCase().replace('_', ' ')}
          </Badge>{' '}
          <span className="font-mono text-xs text-muted-foreground">
            {entry.aggregateId.slice(0, 8)}
          </span>
        </TableCell>
        <TableCell className="text-right text-sm whitespace-nowrap text-muted-foreground">
          <time dateTime={entry.recordedAt}>{WHEN.format(new Date(entry.recordedAt))}</time>
        </TableCell>
      </TableRow>

      {open ? (
        <TableRow data-testid="audit-payload">
          <TableCell colSpan={4} className="bg-muted/30">
            <div className="space-y-2 py-2">
              <p className="text-xs text-muted-foreground">
                The event exactly as it was recorded
                {entry.kafkaTopic ? (
                  <>
                    , delivered on <span className="font-mono">{entry.kafkaTopic}</span> partition{' '}
                    {entry.kafkaPartition} offset {entry.kafkaOffset}
                  </>
                ) : null}
                .
              </p>
              <pre className="overflow-x-auto rounded-md bg-background p-3 text-xs">
                {JSON.stringify(entry.payload, null, 2)}
              </pre>
            </div>
          </TableCell>
        </TableRow>
      ) : null}
    </>
  )
}
