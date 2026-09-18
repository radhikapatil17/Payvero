import { useState } from 'react'
import { Lock, ScrollText } from 'lucide-react'
import { AuditRow } from './AuditRow'
import { EmptyState } from '@/components/EmptyState'
import { FormAlert } from '@/routes/auth/FormAlert'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useAuditLog } from '@/lib/api/queries'

const FILTERS = [
  { value: 'ALL', label: 'Everything' },
  { value: 'TRANSFER', label: 'Transfers' },
  { value: 'FRAUD_FLAG', label: 'Fraud decisions' },
]

export function AuditLogPage() {
  const [aggregateType, setAggregateType] = useState('ALL')
  const entries = useAuditLog(aggregateType)

  return (
    <section className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Audit log</h1>
        <p className="text-sm text-muted-foreground">
          Every event this system has consumed, in the order it learned of them.
        </p>
      </div>

      {/*
        The append-only property is the point of the whole table, so it is
        stated where the table is read rather than left as a fact about the
        schema that only someone reading a migration would ever discover.
      */}
      <div className="flex items-start gap-3 rounded-lg border bg-muted/40 px-4 py-3">
        <Lock aria-hidden className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <p className="text-sm text-muted-foreground">
          These rows are append-only. The database rejects <code>UPDATE</code> and{' '}
          <code>DELETE</code> on this table outright, so nothing here — including the fraud
          decisions — can be edited or removed afterwards, by this application or by anyone
          holding a database connection. A correction is a new event, never a rewrite.
        </p>
      </div>

      <Tabs value={aggregateType} onValueChange={setAggregateType}>
        <TabsList>
          {FILTERS.map((option) => (
            <TabsTrigger key={option.value} value={option.value}>
              {option.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {entries.isPending ? (
        <Skeleton data-testid="audit-skeleton" className="h-96 w-full" />
      ) : entries.isError ? (
        <FormAlert message="Could not load the audit log." />
      ) : entries.data && entries.data.content.length > 0 ? (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Who</TableHead>
                  <TableHead>Did what</TableHead>
                  <TableHead>Subject</TableHead>
                  <TableHead className="text-right">Recorded</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.data.content.map((entry) => (
                  <AuditRow key={entry.id} entry={entry} />
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="pt-6">
            <EmptyState
              icon={<ScrollText className="size-6" />}
              title="Nothing recorded yet"
              description="Events appear here once the consumer has processed them."
            />
          </CardContent>
        </Card>
      )}
    </section>
  )
}
