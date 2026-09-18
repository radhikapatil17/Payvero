import { useId } from 'react'
import type { ComponentProps, ReactNode } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'

type FieldProps = ComponentProps<typeof Input> & {
  label: string
  /** Message from either Zod or the server; the field does not care which. */
  error?: string
  hint?: ReactNode
}

/**
 * A labelled input that wires its own error up accessibly.
 *
 * The point is the association, not the styling. `aria-describedby` links the
 * message to the input, so a screen reader announces which field is wrong
 * rather than reading a disembodied sentence, and `aria-invalid` marks the
 * input itself. Rendering the message merely somewhere near the input would
 * look identical and communicate nothing.
 */
export function Field({ label, error, hint, className, id, ...inputProps }: FieldProps) {
  const generatedId = useId()
  const fieldId = id ?? generatedId
  const errorId = `${fieldId}-error`
  const hintId = `${fieldId}-hint`

  const describedBy = [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ')

  return (
    <div className="space-y-1.5">
      <Label htmlFor={fieldId}>{label}</Label>
      <Input
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className={cn(error && 'border-destructive focus-visible:ring-destructive/30', className)}
        {...inputProps}
      />
      {hint ? (
        <p id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </p>
      ) : null}
      {error ? (
        // Announced when it appears, because a validation failure after submit
        // is new information the user did not ask to see.
        <p id={errorId} role="alert" className="text-xs font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  )
}
