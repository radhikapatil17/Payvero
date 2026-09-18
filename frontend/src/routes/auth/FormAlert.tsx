import { AlertCircle } from 'lucide-react'

/**
 * Where an error the server sent lands when it names no field.
 *
 * Insufficient funds, a spent rate limit, a reused idempotency key: all refusals
 * the server explained, none of which belong against a particular input.
 * Swallowing them would leave a form that appears to do nothing when submitted.
 */
export function FormAlert({ message }: { message?: string }) {
  if (!message) return null

  return (
    <div
      role="alert"
      className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive"
    >
      <AlertCircle aria-hidden className="mt-0.5 size-4 shrink-0" />
      <span>{message}</span>
    </div>
  )
}
