import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { AuthContext, type AuthContextValue, type AuthStatus } from './authContext'
import { apiRequest, refreshAccessToken } from '@/lib/api/client'
import { authChannel } from '@/lib/api/authChannel'
import { tokens } from '@/lib/api/tokens'
import type { AuthResponse, CurrentUser } from '@/lib/api/types'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('resolving')
  const [user, setUser] = useState<CurrentUser | null>(null)

  const adopt = useCallback(async () => {
    // Identity comes from the server rather than from decoding the token.
    // A claim read by the client is not an authorization decision, and
    // keeping that boundary in one place stops it blurring later.
    const currentUser = await apiRequest<CurrentUser>('/api/auth/me')
    setUser(currentUser)
    setStatus('authenticated')
  }, [])

  const forgetSession = useCallback(() => {
    tokens.clear()
    setUser(null)
    setStatus('anonymous')
  }, [])

  useEffect(() => {
    let cancelled = false

    async function resolveSession() {
      if (!tokens.getRefreshToken()) {
        if (!cancelled) setStatus('anonymous')
        return
      }
      try {
        const renewed = await refreshAccessToken()
        if (cancelled) return
        if (!renewed) {
          forgetSession()
          return
        }
        await adopt()
      } catch {
        if (!cancelled) forgetSession()
      }
    }

    void resolveSession()
    return () => {
      cancelled = true
    }
  }, [adopt, forgetSession])

  // Another tab signing out signs this one out too, rather than leaving a
  // window that looks signed in until its next request fails.
  useEffect(() => authChannel.onSignedOut(() => forgetSession()), [forgetSession])

  const authenticate = useCallback(
    async (path: string, email: string, password: string) => {
      const response = await apiRequest<AuthResponse>(path, {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      })
      tokens.set(response.accessToken, response.refreshToken)
      await adopt()
    },
    [adopt],
  )

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      signIn: (email, password) => authenticate('/api/auth/login', email, password),
      register: (email, password) => authenticate('/api/auth/register', email, password),
      signOut: async () => {
        const refreshToken = tokens.getRefreshToken()
        try {
          if (refreshToken) {
            await apiRequest<void>('/api/auth/logout', {
              method: 'POST',
              body: JSON.stringify({ refreshToken }),
            })
          }
        } finally {
          // Local state is cleared regardless: a network failure must not
          // leave someone looking signed in after they asked to leave.
          authChannel.announceSignedOut()
          forgetSession()
        }
      },
    }),
    [status, user, authenticate, forgetSession],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
