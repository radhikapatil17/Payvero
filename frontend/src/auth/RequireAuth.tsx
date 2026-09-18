import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './authContext'
import { FullPageLoader } from '@/components/FullPageLoader'

/**
 * Decides before the guarded subtree exists.
 *
 * The three states are handled with three different returns, so while the
 * session is resolving this renders a loader and nothing else — the child
 * route element is never constructed, never mounted, and never in the DOM.
 * A guard that redirected from an effect would have to render its children
 * first and remove them after paint, which is the flash this avoids.
 */
export function RequireAuth() {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'resolving') {
    return <FullPageLoader label="Restoring your session" />
  }

  if (status === 'anonymous') {
    // Remembered so a deep link survives the round trip through sign-in.
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  return <Outlet />
}
