import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiRequest } from './client'
import { queryKeys, type FraudFilter } from './queries'
import type { FraudFlag } from './types'

export type Decision = 'clear' | 'confirm'

/**
 * Reviewing a flag, given that another admin may be reviewing the same one.
 *
 * The server refuses the second decision outright rather than letting it
 * overwrite the first — a flag leaves OPEN exactly once, and the loser gets a
 * 409. That is the correct behaviour and the reason there is no optimistic
 * update here: showing a decision that the server is about to reject would
 * mean the loser briefly sees their own verdict recorded when it never was.
 */
export function useReviewFlag(status: FraudFilter) {
  const queryClient = useQueryClient()

  return useMutation<FraudFlag, unknown, { flagId: string; decision: Decision }>({
    mutationFn: ({ flagId, decision }) =>
      apiRequest<FraudFlag>(`/api/admin/fraud-flags/${flagId}/${decision}`, { method: 'POST' }),
    onSettled: () => {
      /**
       * Invalidated on failure as well as success, and deliberately so. Losing
       * the race means this tab's queue is out of date, so the refetch is what
       * replaces the stale OPEN row with the decision that actually won.
       */
      void queryClient.invalidateQueries({ queryKey: queryKeys.fraudFlags(status) })
      void queryClient.invalidateQueries({ queryKey: queryKeys.auditLog('ALL') })
      void queryClient.invalidateQueries({ queryKey: queryKeys.auditLog('FRAUD_FLAG') })
    },
  })
}

/** Plain language for the refusals this action can actually get back. */
export function explainReviewError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Could not record the decision. Please try again.'
  }
  if (error.code === 'FRAUD_FLAG_ALREADY_REVIEWED') {
    return 'Another admin already reviewed this flag. Your decision was not recorded — the queue below now shows theirs.'
  }
  if (error.code === 'FRAUD_FLAG_NOT_FOUND') {
    return 'This flag no longer exists.'
  }
  return error.message
}
