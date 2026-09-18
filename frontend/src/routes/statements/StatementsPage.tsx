import { useState } from 'react'
import { FileText } from 'lucide-react'
import { buildPeriods, formatPeriod, type Period } from './buildPeriods'
import { OpenPeriodPanel } from './OpenPeriodPanel'
import { StatementPanel } from './StatementPanel'
import { EmptyState } from '@/components/EmptyState'
import { FormAlert } from '@/routes/auth/FormAlert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiError } from '@/lib/api/client'
import { useAccounts, useStatements } from '@/lib/api/queries'
import { useGenerateStatement } from '@/lib/api/useGenerateStatement'
import { cn } from '@/lib/utils'

export function StatementsPage() {
  const accounts = useAccounts()
  const account = accounts.data?.[0]
  const statements = useStatements(account?.id)
  const [selected, setSelected] = useState<string | null>(null)

  if (accounts.isPending || (account && statements.isPending)) {
    return <Skeleton data-testid="statements-skeleton" className="h-96 w-full" />
  }

  if (!account) {
    return (
      <EmptyState
        icon={<FileText className="size-6" />}
        title="No account yet"
        description="Statements are issued per account, so there is nothing to show yet."
      />
    )
  }

  const periods = buildPeriods(account.createdAt, new Date(), statements.data ?? [])
  const active = periods.find((period) => period.label === selected) ?? periods[0]

  return (
    <section className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Statements</h1>
        <p className="text-sm text-muted-foreground">
          One immutable statement per closed month, derived from journal entries.
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-[16rem_1fr]">
        <Card className="h-fit">
          <CardHeader>
            <CardTitle className="text-sm font-medium text-muted-foreground">Periods</CardTitle>
          </CardHeader>
          <CardContent className="p-2">
            <ul>
              {periods.map((period) => (
                <li key={period.label}>
                  <button
                    type="button"
                    onClick={() => setSelected(period.label)}
                    aria-current={period.label === active?.label ? 'true' : undefined}
                    className={cn(
                      'flex w-full items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-sm transition-colors',
                      period.label === active?.label
                        ? 'bg-muted font-medium'
                        : 'hover:bg-muted/60',
                    )}
                  >
                    <span>{formatPeriod(period.label)}</span>
                    <PeriodBadge state={period.state} />
                  </button>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="pt-6">
            {active ? (
              /*
                Keyed by period so switching selection remounts. Without it a
                failed generate on one month would leave its explanation sitting
                above a different month, attached to nothing the user just did.
              */
              <PeriodDetail
                key={active.label}
                period={active}
                accountId={account.id}
                currency={account.currency}
              />
            ) : null}
          </CardContent>
        </Card>
      </div>
    </section>
  )
}

function PeriodBadge({ state }: { state: Period['state'] }) {
  if (state === 'open') {
    return (
      <Badge
        variant="outline"
        className="border-amber-500/40 bg-amber-500/10 text-[10px] text-amber-600 dark:text-amber-400"
      >
        open
      </Badge>
    )
  }
  if (state === 'available') {
    return (
      <Badge variant="outline" className="text-[10px] text-muted-foreground">
        not issued
      </Badge>
    )
  }
  return null
}

function PeriodDetail({
  period,
  accountId,
  currency,
}: {
  period: Period
  accountId: string
  currency: string
}) {
  const generate = useGenerateStatement(accountId)

  if (period.state === 'open') {
    return <OpenPeriodPanel period={period.label} currency={currency} />
  }

  if (period.statement) {
    return <StatementPanel statement={period.statement} currency={currency} />
  }

  return (
    <div className="space-y-4">
      {/*
        A 409 here means the month had not actually closed when the request
        landed — reachable by sitting on this page across a month boundary. It
        is an explanation, not a failure: the button will simply work later.
      */}
      <FormAlert message={explain(generate.error)} />

      <EmptyState
        icon={<FileText className="size-6" />}
        title={`${formatPeriod(period.label)} has closed`}
        description="No statement has been issued for this period yet. Generating one derives the figures from journal entries and fixes them permanently."
        action={
          <Button onClick={() => generate.mutate(period.label)} disabled={generate.isPending}>
            {generate.isPending ? 'Generating…' : 'Generate statement'}
          </Button>
        }
      />
    </div>
  )
}

function explain(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) {
    return error ? 'Could not generate the statement. Please try again.' : undefined
  }
  if (error.code === 'PERIOD_NOT_CLOSED') {
    return 'This period has not finished yet, so a statement cannot be issued for it. It will become available once the month ends.'
  }
  return error.message
}
