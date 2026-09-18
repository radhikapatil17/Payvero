import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { AuthProvider } from '@/auth/AuthProvider'
import { createQueryClient } from '@/lib/queryClient'

export function renderWithProviders(ui: ReactElement, { route = '/' } = {}) {
  const queryClient = createQueryClient()

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <AuthProvider>{children}</AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }

  // The client is returned so a test can assert on the cache itself rather
  // than mocking the hook that reads it.
  return { ...render(ui, { wrapper: Wrapper }), queryClient }
}
