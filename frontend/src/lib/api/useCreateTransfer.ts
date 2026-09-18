import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { apiRequest } from './client'
import { queryKeys } from './queries'
import type { Balance, Paged, Transfer } from './types'

export type CreateTransferInput = {
  sourceAccountId: string
  destinationAccountId: string
  amountMinorUnits: number
  /**
   * Generated once per intent, not once per attempt. A retry after a failure
   * carries the same key, which is what makes pressing the button again safe
   * rather than a second transfer.
   */
  idempotencyKey: string
}

type MutationContext = {
  optimisticId: string
  previousTransfers: [readonly unknown[], Paged<Transfer> | undefined][]
  previousBalance: Balance | undefined
}

export const OPTIMISTIC_PREFIX = 'optimistic:'

/** Marks a row the server has not confirmed, so the UI can style it as in-flight. */
export function isOptimistic(transfer: Transfer) {
  return transfer.id.startsWith(OPTIMISTIC_PREFIX)
}

function buildOptimisticTransfer(input: CreateTransferInput, currency: string): Transfer {
  return {
    id: `${OPTIMISTIC_PREFIX}${input.idempotencyKey}`,
    sourceAccountId: input.sourceAccountId,
    destinationAccountId: input.destinationAccountId,
    // The caller is the source, so this is money leaving. The counterparty's
    // name is not knowable client-side, so the account is shown until the
    // server answers with who it belongs to.
    direction: 'DEBIT',
    counterpartyLabel: shortId(input.destinationAccountId),
    counterpartyAccountId: input.destinationAccountId,
    amountMinorUnits: input.amountMinorUnits,
    currency,
    status: 'PENDING',
    failureReason: null,
    createdAt: new Date().toISOString(),
    settledAt: null,
  }
}

function shortId(id: string) {
  return id.length > 12 ? `${id.slice(0, 8)}…` : id
}

export function useCreateTransfer(currency = 'USD') {
  const queryClient = useQueryClient()

  return useMutation<Transfer, unknown, CreateTransferInput, MutationContext>({
    mutationFn: (input) =>
      apiRequest<Transfer>('/api/transfers', {
        method: 'POST',
        // Sent on every create, without exception. Without it a retried
        // request — by the user, or by a proxy — is a second transfer.
        headers: { 'Idempotency-Key': input.idempotencyKey },
        body: JSON.stringify({
          sourceAccountId: input.sourceAccountId,
          destinationAccountId: input.destinationAccountId,
          amountMinorUnits: input.amountMinorUnits,
        }),
      }),

    onMutate: async (input) => {
      // Cancelled first: an in-flight refetch that resolves after this would
      // otherwise overwrite the optimistic state with pre-transfer data.
      await queryClient.cancelQueries({ queryKey: ['transfers'] })
      await queryClient.cancelQueries({ queryKey: queryKeys.balance(input.sourceAccountId) })

      const previousTransfers = snapshotTransfers(queryClient)
      const previousBalance = queryClient.getQueryData<Balance>(
        queryKeys.balance(input.sourceAccountId),
      )

      const optimistic = buildOptimisticTransfer(input, currency)

      queryClient.setQueriesData<Paged<Transfer>>({ queryKey: ['transfers'] }, (page) =>
        page
          ? {
              ...page,
              content: [optimistic, ...page.content],
              page: { ...page.page, totalElements: page.page.totalElements + 1 },
            }
          : page,
      )

      queryClient.setQueryData<Balance>(queryKeys.balance(input.sourceAccountId), (balance) =>
        balance
          ? {
              ...balance,
              derivedBalanceMinorUnits:
                balance.derivedBalanceMinorUnits - input.amountMinorUnits,
              cachedBalanceMinorUnits: balance.cachedBalanceMinorUnits - input.amountMinorUnits,
            }
          : balance,
      )

      return { optimisticId: optimistic.id, previousTransfers, previousBalance }
    },

    onSuccess: (serverTransfer, _input, context) => {
      /**
       * Replaced in place, by id, before anything is invalidated.
       *
       * Appending the server's row and waiting for a refetch to tidy up would
       * show the same transfer twice for as long as the refetch takes. Swapping
       * the optimistic row for the real one means the row the user has been
       * watching simply becomes confirmed.
       */
      if (!context) return
      queryClient.setQueriesData<Paged<Transfer>>({ queryKey: ['transfers'] }, (page) =>
        page
          ? {
              ...page,
              content: page.content.map((transfer) =>
                transfer.id === context.optimisticId ? serverTransfer : transfer,
              ),
            }
          : page,
      )
    },

    onError: (_error, _input, context) => {
      // Both snapshots restored: the row vanishes and the balance goes back to
      // what it was, so a refused transfer leaves no trace of having happened.
      if (!context) return
      for (const [key, data] of context.previousTransfers) {
        queryClient.setQueryData(key, data)
      }
      if (context.previousBalance) {
        queryClient.setQueryData(
          queryKeys.balance(context.previousBalance.accountId),
          context.previousBalance,
        )
      }
    },

    onSettled: (_data, _error, input) => {
      void queryClient.invalidateQueries({ queryKey: ['transfers'] })
      void queryClient.invalidateQueries({ queryKey: queryKeys.balance(input.sourceAccountId) })
      void queryClient.invalidateQueries({ queryKey: queryKeys.accounts })
    },
  })
}

function snapshotTransfers(
  queryClient: QueryClient,
): [readonly unknown[], Paged<Transfer> | undefined][] {
  return queryClient
    .getQueriesData<Paged<Transfer>>({ queryKey: ['transfers'] })
    .map(([key, data]) => [key, data] as [readonly unknown[], Paged<Transfer> | undefined])
}
