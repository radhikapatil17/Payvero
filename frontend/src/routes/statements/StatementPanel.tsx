import { Lock } from 'lucide-react'
import { formatPeriod } from './buildPeriods'
import { EmptyState } from '@/components/EmptyState'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMoney, formatSignedMoney } from '@/lib/money'
import type { Statement } from '@/lib/api/types'

const ISSUED = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'short',
})
const LINE_DATE = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })

/**
 * A closed period's statement: the figures the server derived from ledger
 * entries, presented as the fixed record they are. The immutability is stated
 * rather than implied, because "issued" is what makes these numbers worth more
 * than a live balance.
 */
export function StatementPanel({
  statement,
  currency,
}: {
  statement: Statement
  currency: string
}) {
  return (
    <div data-testid="statement-panel" className="space-y-4">
      <div className="flex items-start gap-3 rounded-lg border bg-muted/40 px-4 py-3">
        <Lock aria-hidden className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <div className="space-y-1">
          <p className="text-sm font-medium">{formatPeriod(statement.period)} statement</p>
          <p className="text-sm text-muted-foreground">
            Issued {ISSUED.format(new Date(statement.generatedAt))}. Derived from journal entries and
            fixed once issued, so these figures will not change.
          </p>
        </div>
      </div>

      <dl data-testid="statement-figures" className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Figure label="Opening" value={formatMoney(statement.openingBalanceMinorUnits, currency)} />
        <Figure label="Closing" value={formatMoney(statement.closingBalanceMinorUnits, currency)} />
        <Figure
          label="Net movement"
          value={`${statement.netMovementMinorUnits >= 0 ? '+' : '−'}${formatMoney(
            Math.abs(statement.netMovementMinorUnits),
            currency,
          )}`}
        />
        <Figure label="Entries" value={String(statement.entryCount)} />
      </dl>

      {statement.lineItems.length === 0 ? (
        <EmptyState
          title="No movement in this period"
          description="The balance carried forward unchanged, which is why opening and closing match."
        />
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Date</TableHead>
              <TableHead>Direction</TableHead>
              <TableHead className="text-right">Amount</TableHead>
              <TableHead className="text-right">Balance after</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {statement.lineItems.map((line) => (
              <TableRow key={line.entryId} data-testid="statement-line">
                <TableCell>{LINE_DATE.format(new Date(line.occurredAt))}</TableCell>
                <TableCell className="text-muted-foreground">
                  {line.direction === 'CREDIT' ? 'in' : 'out'}
                </TableCell>
                <TableCell className="text-right tabular-nums">
                  {formatSignedMoney(line.amountMinorUnits, line.direction, line.currency)}
                </TableCell>
                <TableCell className="text-right tabular-nums">
                  {formatMoney(line.balanceAfterMinorUnits, line.currency)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-lg font-semibold tabular-nums tracking-tight">{value}</dd>
    </div>
  )
}
