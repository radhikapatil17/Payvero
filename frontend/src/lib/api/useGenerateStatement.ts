import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiRequest } from './client'
import { queryKeys } from './queries'
import type { Statement } from './types'

/**
 * No optimistic update here, deliberately. A statement's figures are derived
 * server-side from journal entries, so the client has nothing truthful to show
 * until the server answers — inventing an opening balance would be exactly the
 * kind of guess this document exists to rule out.
 */
export function useGenerateStatement(accountId: string) {
  const queryClient = useQueryClient()

  return useMutation<Statement, unknown, string>({
    mutationFn: (period) =>
      apiRequest<Statement>(`/api/accounts/${accountId}/statements/${period}`, { method: 'POST' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.statements(accountId) })
    },
  })
}
