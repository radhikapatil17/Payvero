import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { AuthProvider } from '@/auth/AuthProvider'
import { RouteErrorBoundary } from '@/components/RouteErrorBoundary'
import { Toaster } from '@/components/ui/sonner'
import { createQueryClient } from '@/lib/queryClient'
import './index.css'

const queryClient = createQueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          {/*
            The outer net, for throws above the layout — the auth shell, the
            router itself, a sign-in screen. Anything inside the layout is
            caught closer to where it happened, with the nav left standing.
          */}
          <RouteErrorBoundary fullPage>
            <App />
          </RouteErrorBoundary>
          <Toaster position="bottom-right" richColors />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
