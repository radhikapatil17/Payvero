import { NavLink, Outlet } from 'react-router-dom'
import { LogOut, ShieldAlert, FileText, ArrowRightLeft, LayoutDashboard, ShieldCheck } from 'lucide-react'
import { useAuth } from '@/auth/authContext'
import { RouteErrorBoundary } from '@/components/RouteErrorBoundary'
import { PayveroBrandLogo } from '@/components/PayveroBrandLogo'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

type NavItem = { to: string; label: string; icon: React.ComponentType<{ className?: string }>; end?: boolean }

const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/transfers', label: 'Transfers', icon: ArrowRightLeft },
  { to: '/statements', label: 'Statements', icon: FileText },
]

const ADMIN_NAV: NavItem[] = [
  { to: '/admin/fraud', label: 'Fraud Queue', icon: ShieldAlert },
  { to: '/admin/audit', label: 'Audit Log', icon: ShieldCheck },
]

export function AppLayout() {
  const { user, signOut } = useAuth()
  const links = user?.role === 'ADMIN' ? [...NAV, ...ADMIN_NAV] : NAV

  return (
    <div className="min-h-dvh bg-slate-950 text-slate-100 font-sans antialiased">
      {/* Top Navbar */}
      <header className="sticky top-0 z-50 border-b border-slate-800/80 bg-slate-950/80 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6">
          <div className="flex items-center gap-8">
            <PayveroBrandLogo size="md" />

            <nav className="hidden md:flex items-center gap-1 text-sm font-medium">
              {links.map((link) => {
                const Icon = link.icon
                return (
                  <NavLink
                    key={link.to}
                    to={link.to}
                    end={link.end}
                    className={({ isActive }) =>
                      cn(
                        'flex items-center gap-2 rounded-lg px-3.5 py-2 transition-all duration-150',
                        isActive
                          ? 'bg-emerald-500/10 text-emerald-400 font-semibold shadow-sm border border-emerald-500/20'
                          : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900',
                      )
                    }
                  >
                    <Icon className="size-4" />
                    {link.label}
                  </NavLink>
                )
              })}
            </nav>
          </div>

          <div className="flex items-center gap-4">
            <div className="hidden sm:flex flex-col items-end text-xs">
              <span className="font-semibold text-slate-200">{user?.email}</span>
              <span className="text-[10px] text-emerald-400/90 font-mono tracking-wider uppercase">
                {user?.role === 'ADMIN' ? 'System Administrator' : 'Verified Account'}
              </span>
            </div>

            <Button
              variant="outline"
              size="sm"
              onClick={() => void signOut()}
              className="border-slate-800 bg-slate-900/60 text-slate-300 hover:bg-slate-800 hover:text-white transition-colors"
            >
              <LogOut className="size-4 mr-1.5 text-slate-400" />
              Sign out
            </Button>
          </div>
        </div>

        {/* Mobile Navigation bar */}
        <div aria-hidden="true" className="flex md:hidden overflow-x-auto border-t border-slate-800/60 px-2 py-1.5 bg-slate-950/90">
          {links.map((link) => {
            const Icon = link.icon
            return (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.end}
                className={({ isActive }) =>
                  cn(
                    'flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                    isActive
                      ? 'bg-emerald-500/15 text-emerald-400 font-semibold'
                      : 'text-slate-400 hover:text-slate-200',
                  )
                }
              >
                <Icon className="size-3.5" />
                {link.label}
              </NavLink>
            )
          })}
        </div>
      </header>

      {/* Main Content Area */}
      <main className="mx-auto max-w-7xl px-4 sm:px-6 py-8">
        <RouteErrorBoundary>
          <Outlet />
        </RouteErrorBoundary>
      </main>
    </div>
  )
}
