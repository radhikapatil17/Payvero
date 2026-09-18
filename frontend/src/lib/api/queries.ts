import { useQuery } from '@tanstack/react-query'
import { apiRequest } from './client'
import type {
  Account,
  AuditLogEntry,
  Balance,
  FraudFlag,
  Paged,
  Statement,
  Transfer,
} from './types'

export type FraudFilter = 'OPEN' | 'CLEARED' | 'CONFIRMED' | 'ALL'

/** One place for keys, so an invalidation and a query cannot disagree. */
export const queryKeys = {
  accounts: ['accounts'] as const,
  balance: (accountId: string) => ['balance', accountId] as const,
  transfers: (page: number, size: number) => ['transfers', page, size] as const,
  statements: (accountId: string) => ['statements', accountId] as const,
  fraudFlags: (status: FraudFilter) => ['fraud-flags', status] as const,
  auditLog: (aggregateType: string) => ['audit-log', aggregateType] as const,
}

export function useAccounts() {
  return useQuery({
    queryKey: queryKeys.accounts,
    queryFn: () => apiRequest<Account[]>('/api/accounts'),
  })
}

export function useBalance(accountId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.balance(accountId ?? 'none'),
    queryFn: () => apiRequest<Balance>(`/api/accounts/${accountId}/balance`),
    enabled: Boolean(accountId),
  })
}

export function useTransfers(page = 0, size = 20) {
  return useQuery({
    queryKey: queryKeys.transfers(page, size),
    queryFn: () => apiRequest<Paged<Transfer>>(`/api/transfers?page=${page}&size=${size}`),
    /**
     * A newly accepted transfer settles a moment later, so the list polls only
     * while something is actually pending and stops as soon as nothing is.
     * A permanent interval would poll all night to catch a one second change.
     */
    refetchInterval: (query) =>
      query.state.data?.content.some((transfer) => transfer.status === 'PENDING') ? 2_000 : false,
  })
}

export function useStatements(accountId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.statements(accountId ?? 'none'),
    queryFn: () => apiRequest<Statement[]>(`/api/accounts/${accountId}/statements`),
    enabled: Boolean(accountId),
  })
}

export function useFraudFlags(status: FraudFilter) {
  return useQuery({
    queryKey: queryKeys.fraudFlags(status),
    queryFn: () =>
      apiRequest<Paged<FraudFlag>>(
        `/api/admin/fraud-flags?size=50${status === 'ALL' ? '' : `&status=${status}`}`,
      ),
    /**
     * A queue two operators share goes stale by someone else's action, not by
     * anything this tab did. Refetching on focus is what makes returning to the
     * tab show the queue as it is rather than as it was when you left.
     */
    refetchOnWindowFocus: true,
  })
}

export function useAuditLog(aggregateType: string) {
  return useQuery({
    queryKey: queryKeys.auditLog(aggregateType),
    queryFn: () =>
      apiRequest<Paged<AuditLogEntry>>(
        `/api/admin/audit-log?size=50${aggregateType === 'ALL' ? '' : `&aggregateType=${aggregateType}`}`,
      ),
  })
}
