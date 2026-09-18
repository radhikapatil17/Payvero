import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from './authContext'
import { FullPageLoader } from '@/components/FullPageLoader'

/**
 * Same shape as RequireAuth, one rule further. The role comes from the server
 * resolved user rather than a decoded claim, and this only decides what to
 * render — every admin endpoint enforces the role again on its own.
 */
export function RequireAdmin() {
  const { status, user } = useAuth()

  if (status === 'resolving') {
    return <FullPageLoader label="Restoring your session" />
  }

  if (status === 'anonymous') {
    return <Navigate to="/login" replace />
  }

  if (user?.role !== 'ADMIN') {
    return <Navigate to="/" replace />
  }

  return <Outlet />
}
