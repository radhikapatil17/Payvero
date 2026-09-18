import { Suspense, lazy } from 'react'
import { Link } from 'react-router-dom'
import { Landmark, Receipt, ArrowUpRight, ShieldCheck, Zap } from 'lucide-react'
import { ActivityList } from './ActivityList'
import { EmptyState } from '@/components/EmptyState'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useAccounts, useBalance, useTransfers } from '@/lib/api/queries'
import { formatMoney } from '@/lib/money'

const BalanceChart = lazy(() =>
  import('./BalanceChart').then((module) => ({ default: module.BalanceChart })),
)

export function DashboardPage() {
  const accounts = useAccounts()
  const account = accounts.data?.[0]
  const balance = useBalance(account?.id)
  const transfers = useTransfers(0, 8)

  if (accounts.isPending) {
    return <DashboardSkeleton />
  }

  if (!account) {
    return (
      <section className="space-y-6">
        <PageHeading />
        <EmptyState
          icon={<Landmark className="size-6 text-emerald-400" />}
          title="No account yet"
          description="Initialize your Payvero digital accounting account to start processing transactions."
          action={
            <Link
              to="/transfers"
              className="inline-flex h-9 items-center justify-center rounded-lg bg-emerald-500 px-4 text-sm font-semibold text-slate-950 transition-colors hover:bg-emerald-400 shadow-md shadow-emerald-500/20"
            >
              Open an account
            </Link>
          }
        />
      </section>
    )
  }

  const balanceMinorUnits = balance.data?.derivedBalanceMinorUnits ?? account.balanceMinorUnits
  const transferCount = transfers.data?.page.totalElements ?? 0

  return (
    <section className="space-y-6">
      <PageHeading />

      <div className="grid gap-4 md:grid-cols-3">
        {/* Available balance Card */}
        <Card className="border-slate-800 bg-slate-900/80 shadow-xl backdrop-blur-md">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-semibold uppercase tracking-wider text-slate-400">
              Available balance
            </CardTitle>
            <div className="rounded-lg bg-emerald-500/10 p-2 text-emerald-400 border border-emerald-500/20">
              <Landmark className="size-4" />
            </div>
          </CardHeader>
          <CardContent>
            {balance.isPending ? (
              <Skeleton data-testid="balance-skeleton" className="h-9 w-40 bg-slate-800" />
            ) : (
              <div>
                <p className="text-3xl font-extrabold tabular-nums tracking-tight text-white">
                  {formatMoney(balanceMinorUnits, account.currency)}
                </p>
                <div className="mt-2 flex items-center justify-between text-xs text-slate-400">
                  <span>Currency: <strong className="text-slate-200">{account.currency}</strong></span>
                  <Link
                    to="/transfers"
                    className="inline-flex items-center gap-1 font-semibold text-emerald-400 hover:text-emerald-300"
                  >
                    Send Money <ArrowUpRight className="size-3" />
                  </Link>
                </div>
              </div>
            )}
            {balance.data && !balance.data.consistent ? (
              <p role="alert" className="mt-2 text-xs font-medium text-rose-400 bg-rose-500/10 p-2 rounded-md border border-rose-500/20">
                This balance disagrees with the journal and is being investigated.
              </p>
            ) : null}
          </CardContent>
        </Card>

        {/* Total Transfers Metric */}
        <Card className="border-slate-800 bg-slate-900/80 shadow-xl backdrop-blur-md">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-semibold uppercase tracking-wider text-slate-400">
              Processed Transfers
            </CardTitle>
            <div className="rounded-lg bg-indigo-500/10 p-2 text-indigo-400 border border-indigo-500/20">
              <Receipt className="size-4" />
            </div>
          </CardHeader>
          <CardContent>
            {transfers.isPending ? (
              <Skeleton className="h-9 w-24 bg-slate-800" />
            ) : (
              <div>
                <p className="text-3xl font-extrabold tabular-nums tracking-tight text-white">
                  {transferCount}
                </p>
                <p className="mt-2 text-xs text-slate-400">
                  Double-entry balanced records
                </p>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Idempotency & Engine Status */}
        <Card className="border-slate-800 bg-slate-900/80 shadow-xl backdrop-blur-md">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-semibold uppercase tracking-wider text-slate-400">
              Engine Status
            </CardTitle>
            <div className="rounded-lg bg-teal-500/10 p-2 text-teal-400 border border-teal-500/20">
              <Zap className="size-4" />
            </div>
          </CardHeader>
          <CardContent className="space-y-2">
            <div className="flex items-center justify-between text-xs">
              <span className="text-slate-400">Idempotency Guard:</span>
              <span className="inline-flex items-center gap-1 font-semibold text-emerald-400">
                <ShieldCheck className="size-3.5" /> Redis Active
              </span>
            </div>
            <div className="flex items-center justify-between text-xs">
              <span className="text-slate-400">Outbox Event Bus:</span>
              <span className="font-semibold text-indigo-400">Kafka Outbox</span>
            </div>
            <div className="flex items-center justify-between text-xs">
              <span className="text-slate-400">Ledger Integrity:</span>
              <span className="font-semibold text-teal-400">Balanced DB Triggers</span>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Balance Trend Chart */}
      <Card className="border-slate-800 bg-slate-900/80 shadow-xl backdrop-blur-md">
        <CardHeader>
          <CardTitle className="text-sm font-semibold tracking-wide text-slate-300">
            Account Balance History & Trend
          </CardTitle>
        </CardHeader>
        <CardContent>
          {transfers.isPending ? (
            <Skeleton data-testid="chart-skeleton" className="h-56 w-full bg-slate-800" />
          ) : (
            <Suspense fallback={<Skeleton data-testid="chart-skeleton" className="h-56 w-full bg-slate-800" />}>
              <BalanceChart
                transfers={transfers.data?.content ?? []}
                currentBalanceMinorUnits={balanceMinorUnits}
                currency={account.currency}
              />
            </Suspense>
          )}
        </CardContent>
      </Card>

      {/* Recent Ledger Activity */}
      <Card className="border-slate-800 bg-slate-900/80 shadow-xl backdrop-blur-md">
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="text-sm font-semibold tracking-wide text-slate-300">
            Recent Ledger Activity
          </CardTitle>
          <Link to="/transfers" className="text-xs font-semibold text-emerald-400 hover:text-emerald-300 transition-colors">
            View full history &rarr;
          </Link>
        </CardHeader>
        <CardContent>
          {transfers.isPending ? (
            <ActivitySkeleton />
          ) : transfers.data && transfers.data.content.length > 0 ? (
            <ActivityList transfers={transfers.data.content} />
          ) : (
            <EmptyState
              icon={<Receipt className="size-6 text-slate-500" />}
              title="No activity yet"
              description="Money you send or receive will appear here."
            />
          )}
        </CardContent>
      </Card>
    </section>
  )
}

function PageHeading() {
  return (
    <div className="space-y-1">
      <h1 className="text-3xl font-black tracking-tight text-white">Financial Dashboard</h1>
      <p className="text-sm text-slate-400">Real-time overview of your Payvero balance, transaction velocity, and ledger events.</p>
    </div>
  )
}

function ActivitySkeleton() {
  return (
    <ul data-testid="activity-skeleton" className="divide-y divide-slate-800">
      {[0, 1, 2, 3].map((row) => (
        <li key={row} className="flex items-center gap-3 py-3">
          <Skeleton className="size-8 shrink-0 rounded-full bg-slate-800" />
          <div className="flex-1 space-y-1.5">
            <Skeleton className="h-3.5 w-40 bg-slate-800" />
            <Skeleton className="h-3 w-24 bg-slate-800" />
          </div>
          <Skeleton className="h-4 w-16 bg-slate-800" />
        </li>
      ))}
    </ul>
  )
}

function DashboardSkeleton() {
  return (
    <section data-testid="dashboard-skeleton" className="space-y-6">
      <PageHeading />
      <div className="grid gap-4 md:grid-cols-3">
        <Skeleton className="h-32 bg-slate-900" />
        <Skeleton className="h-32 bg-slate-900" />
        <Skeleton className="h-32 bg-slate-900" />
      </div>
      <Skeleton className="h-64 bg-slate-900" />
    </section>
  )
}
