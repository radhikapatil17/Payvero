import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { PayveroBrandLogo } from '@/components/PayveroBrandLogo'

export function AuthShell({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string
  subtitle: string
  children: ReactNode
  footer: ReactNode
}) {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center bg-slate-950 px-4 py-12 text-slate-100 selection:bg-emerald-500 selection:text-slate-950">
      <div className="w-full max-w-md space-y-8 rounded-2xl border border-slate-800/80 bg-slate-900/60 p-8 shadow-2xl backdrop-blur-xl">
        <div className="space-y-6">
          <Link to="/" className="inline-block transition-transform hover:scale-105">
            <PayveroBrandLogo size="lg" />
          </Link>

          <div className="space-y-1.5 border-t border-slate-800/60 pt-5">
            <h1 className="text-2xl font-bold tracking-tight text-white">{title}</h1>
            <p className="text-sm text-slate-400">{subtitle}</p>
          </div>
        </div>

        {children}

        <div className="border-t border-slate-800/60 pt-4 text-center text-sm text-slate-400">
          {footer}
        </div>
      </div>
    </div>
  )
}
