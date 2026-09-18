import { Loader2 } from 'lucide-react'

/**
 * Shown while the session resolves. Deliberately plain: it stands in for a
 * screen we do not yet know the visitor is allowed to see.
 */
export function FullPageLoader({ label = 'Loading' }: { label?: string }) {
  return (
    <div
      role="status"
      aria-live="polite"
      className="flex min-h-dvh flex-col items-center justify-center gap-3 text-muted-foreground"
    >
      <Loader2 aria-hidden className="size-5 animate-spin" />
      <p className="text-sm">{label}</p>
    </div>
  )
}
