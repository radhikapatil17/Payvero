import { useState } from 'react'
import { ShieldCheck } from 'lucide-react'
import { FlagCard } from './FlagCard'
import { EmptyState } from '@/components/EmptyState'
import { FormAlert } from '@/routes/auth/FormAlert'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useFraudFlags, type FraudFilter } from '@/lib/api/queries'
import { explainReviewError, useReviewFlag, type Decision } from '@/lib/api/useReviewFlag'

const FILTERS: { value: FraudFilter; label: string }[] = [
  { value: 'OPEN', label: 'Open' },
  { value: 'CONFIRMED', label: 'Confirmed' },
  { value: 'CLEARED', label: 'Cleared' },
  { value: 'ALL', label: 'All' },
]

const EMPTY: Record<FraudFilter, { title: string; description: string }> = {
  OPEN: {
    title: 'Nothing waiting for review',
    description: 'Every flag the velocity rules raised has been decided.',
  },
  CONFIRMED: {
    title: 'No confirmed flags',
    description: 'Nothing here has been judged fraudulent yet.',
  },
  CLEARED: {
    title: 'No cleared flags',
    description: 'Nothing here has been judged legitimate yet.',
  },
  ALL: {
    title: 'No flags have been raised',
    description: 'The velocity rules have not tripped on any transfer.',
  },
}

export function FraudQueuePage() {
  const [filter, setFilter] = useState<FraudFilter>('OPEN')
  const flags = useFraudFlags(filter)
  const review = useReviewFlag(filter)

  const decide = (flagId: string, decision: Decision) => review.mutate({ flagId, decision })

  return (
    <section className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Fraud queue</h1>
        <p className="text-sm text-muted-foreground">
          A flag is advisory. Reviewing one records what a human concluded and moves no money —
          reversing a confirmed fraud is a new opposing transfer.
        </p>
      </div>

      <Tabs value={filter} onValueChange={(value) => setFilter(value as FraudFilter)}>
        <TabsList>
          {FILTERS.map((option) => (
            <TabsTrigger key={option.value} value={option.value}>
              {option.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {/*
        The conflict lives here rather than on a card, because the flag that
        caused it may not be in the list any more once the refetch lands.
      */}
      {review.isError ? <FormAlert message={explainReviewError(review.error)} /> : null}

      {flags.isPending ? (
        <div data-testid="fraud-skeleton" className="space-y-3">
          <Skeleton className="h-32 w-full" />
          <Skeleton className="h-32 w-full" />
        </div>
      ) : flags.isError ? (
        <FormAlert message="Could not load the review queue." />
      ) : flags.data && flags.data.content.length > 0 ? (
        <div className="space-y-3">
          {flags.data.content.map((flag) => (
            <FlagCard
              key={flag.id}
              flag={flag}
              onDecide={decide}
              pending={
                review.isPending && review.variables?.flagId === flag.id
                  ? review.variables.decision
                  : null
              }
              /* Every card locks during a decision: a second click while one is
                 in flight is the same race, just started from one browser. */
              disabled={review.isPending}
            />
          ))}
        </div>
      ) : (
        <Card>
          <CardContent className="pt-6">
            <EmptyState
              icon={<ShieldCheck className="size-6" />}
              title={EMPTY[filter].title}
              description={EMPTY[filter].description}
            />
          </CardContent>
        </Card>
      )}
    </section>
  )
}
