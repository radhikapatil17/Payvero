import { createContext, useContext } from 'react'
import type { CurrentUser } from '@/lib/api/types'

/**
 * `resolving` is a real state, not a loading flag bolted onto `anonymous`.
 *
 * On a fresh page load the access token is gone — it only ever lived in
 * memory — so whether the visitor is signed in is genuinely unknown until a
 * refresh either succeeds or fails. Guards must be able to render nothing at
 * all during that window, which is only possible if the window has a name.
 */
export type AuthStatus = 'resolving' | 'authenticated' | 'anonymous'

export type AuthContextValue = {
  status: AuthStatus
  user: CurrentUser | null
  signIn: (email: string, password: string) => Promise<void>
  register: (email: string, password: string) => Promise<void>
  signOut: () => Promise<void>
}

/**
 * The context and its hook live apart from the provider so that file exports
 * only a component. A module mixing components with other exports defeats Fast
 * Refresh, which silently falls back to a full reload and loses the state you
 * were mid-way through reproducing.
 */
export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return context
}
