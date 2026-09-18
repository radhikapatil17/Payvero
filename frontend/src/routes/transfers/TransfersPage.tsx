import { useState } from 'react'
import { Receipt } from 'lucide-react'
import { TransferForm } from './TransferForm'
import { TransferRow } from './TransferRow'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useAccounts, useBalance, useTransfers } from '@/lib/api/queries'
import { formatMoney } from '@/lib/money'

const PAGE_SIZE = 10

export function TransfersPage() {
  const [page, setPage] = useState(0)
  const accounts = useAccounts()
  const account = accounts.data?.[0]
  const balance = useBalance(account?.id)
  const transfers = useTransfers(page, PAGE_SIZE)

  if (accounts.isPending) {
    return <Skeleton data-testid="transfers-skeleton" className="h-96 w-full" />
  }

  if (!account) {
    return (
      <EmptyState
        icon={<Receipt className="size-6" />}
        title="No account yet"
        description="You need an account before you can send money."
      />
    )
  }

  const balanceMinorUnits = balance.data?.derivedBalanceMinorUnits ?? account.balanceMinorUnits
  const pageInfo = transfers.data?.page

  return (
    <section className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Transfers</h1>
          <p className="text-sm text-muted-foreground">Send money and follow it settle.</p>
        </div>
        <div className="text-right">
          <p className="text-xs text-muted-foreground">Available</p>
          <p data-testid="available-balance" className="text-xl font-semibold tabular-nums">
            {formatMoney(balanceMinorUnits, account.currency)}
          </p>
        </div>
      </div>

      <TransferForm sourceAccountId={account.id} currency={account.currency} />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium text-muted-foreground">All transfers</CardTitle>
        </CardHeader>
        <CardContent>
          {transfers.isPending ? (
            <TransfersSkeleton />
          ) : transfers.data && transfers.data.content.length > 0 ? (
            <>
              <ul className="divide-y">
                {transfers.data.content.map((transfer) => (
                  <TransferRow key={transfer.id} transfer={transfer} />
                ))}
              </ul>

              {pageInfo && pageInfo.totalPages > 1 ? (
                <div className="flex items-center justify-between pt-4 text-sm">
                  <span className="text-muted-foreground">
                    Page {pageInfo.number + 1} of {pageInfo.totalPages}
                  </span>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={pageInfo.number === 0}
                      onClick={() => setPage((current) => Math.max(0, current - 1))}
                    >
                      Previous
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={pageInfo.number >= pageInfo.totalPages - 1}
                      onClick={() => setPage((current) => current + 1)}
                    >
                      Next
                    </Button>
                  </div>
                </div>
              ) : null}
            </>
          ) : (
            <EmptyState
              icon={<Receipt className="size-6" />}
              title="No transfers yet"
              description="Once you send or receive money it will be listed here."
            />
          )}
        </CardContent>
      </Card>
    </section>
  )
}

function TransfersSkeleton() {
  return (
    <ul data-testid="transfer-list-skeleton" className="divide-y">
      {[0, 1, 2, 3, 4].map((row) => (
        <li key={row} className="flex items-center gap-3 py-3">
          <Skeleton className="size-8 shrink-0 rounded-full" />
          <div className="flex-1 space-y-1.5">
            <Skeleton className="h-3.5 w-48" />
            <Skeleton className="h-3 w-28" />
          </div>
          <Skeleton className="h-4 w-20" />
        </li>
      ))}
    </ul>
  )
}
