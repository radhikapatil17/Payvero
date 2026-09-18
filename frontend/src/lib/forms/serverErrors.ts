import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { ApiError } from '@/lib/api/client'

/**
 * Puts a server error somewhere the user will actually see it.
 *
 * Three cases, and the third is the one that matters:
 *
 *  - `fieldErrors` naming a field the form has: attach it to that input.
 *  - No `fieldErrors` at all — INSUFFICIENT_FUNDS, RATE_LIMIT_EXCEEDED,
 *    IDEMPOTENCY_KEY_REUSED, and every other 409/422/429 — go to the form-level
 *    error, so a refusal the server took the trouble to explain is never
 *    silently dropped.
 *  - `fieldErrors` naming a field this form does *not* have, which happens when
 *    an API adds validation the client has not caught up with. Those also fall
 *    through to the form level rather than vanishing into a `setError` call for
 *    an input that does not exist and therefore renders nowhere.
 */
export function applyServerErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  knownFields: readonly Path<T>[],
): void {
  if (!(error instanceof ApiError)) {
    setError('root', { type: 'server', message: 'Something went wrong. Please try again.' })
    return
  }

  const known = new Set<string>(knownFields)
  const unattached: string[] = []
  let attachedAny = false

  for (const [field, message] of Object.entries(error.fieldErrors)) {
    if (known.has(field)) {
      setError(field as Path<T>, { type: 'server', message })
      attachedAny = true
    } else {
      unattached.push(`${field}: ${message}`)
    }
  }

  if (unattached.length > 0) {
    setError('root', { type: 'server', message: unattached.join('. ') })
    return
  }

  if (!attachedAny) {
    setError('root', { type: 'server', message: error.message })
  }
}
