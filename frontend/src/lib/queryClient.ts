import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './api/client'

/**
 * Retrying a 4xx is pointless and occasionally harmful: a 429 answered by
 * three immediate retries is the caller making the rate limit worse, and a 422
 * will never become a 201. Only genuine server or transport failures retry.
 */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failureCount, error) => {
          if (error instanceof ApiError && error.status < 500) return false
          return failureCount < 2
        },
        staleTime: 30_000,
        refetchOnWindowFocus: false,
      },
      mutations: {
        // A mutation that moves money is never retried automatically. The
        // Idempotency-Key makes a *deliberate* retry safe; an automatic one
        // would be the client deciding to send money twice.
        retry: false,
      },
    },
  })
}
