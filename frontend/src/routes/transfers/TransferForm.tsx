import { useRef, useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Send, KeyRound, ShieldCheck } from 'lucide-react'
import { Field } from '@/components/Field'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ApiError } from '@/lib/api/client'
import { useCreateTransfer } from '@/lib/api/useCreateTransfer'
import { applyServerErrors } from '@/lib/forms/serverErrors'
import { parseAmountToMinorUnits } from '@/lib/parseAmount'
import { FormAlert } from '@/routes/auth/FormAlert'
import { transferSchema, type TransferValues } from './transferSchema'

const FIELDS = ['destinationAccountId', 'amount'] as const

export function TransferForm({
  sourceAccountId,
  currency,
}: {
  sourceAccountId: string
  currency: string
}) {
  const createTransfer = useCreateTransfer(currency)

  const idempotencyKey = useRef<string | null>(null)
  const keyedValues = useRef<string | null>(null)
  const [activeKey, setActiveKey] = useState<string | null>(null)
  const [canRetry, setCanRetry] = useState(false)

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<TransferValues>({
    resolver: zodResolver(transferSchema),
    defaultValues: { destinationAccountId: '', amount: '' },
  })

  function keyFor(values: TransferValues) {
    const fingerprint = `${values.destinationAccountId}|${values.amount}`
    if (idempotencyKey.current === null || keyedValues.current !== fingerprint) {
      const newKey = crypto.randomUUID()
      idempotencyKey.current = newKey
      keyedValues.current = fingerprint
      setActiveKey(newKey)
    }
    return idempotencyKey.current
  }

  const onSubmit = handleSubmit(async (values) => {
    const amountMinorUnits = parseAmountToMinorUnits(values.amount)
    if (amountMinorUnits === null) return

    const key = keyFor(values)

    try {
      await createTransfer.mutateAsync({
        sourceAccountId,
        destinationAccountId: values.destinationAccountId,
        amountMinorUnits,
        idempotencyKey: key,
      })

      idempotencyKey.current = null
      keyedValues.current = null
      setActiveKey(null)
      setCanRetry(false)
      reset()
      toast.success('Payment submitted & settled')
    } catch (error) {
      setCanRetry(true)
      applyServerErrors(error, setError, FIELDS)
      toast.error(error instanceof ApiError ? error.message : 'Could not execute payment')
    }
  })

  return (
    <Card className="border-slate-800 bg-slate-900/80 shadow-xl backdrop-blur-md">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base font-bold text-slate-100">
          <Send className="size-4 text-emerald-400" />
          Send Money / Initiate Payment
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} noValidate className="space-y-4">
          <FormAlert message={errors.root?.message} />

          <Field
            label="Recipient account id"
            placeholder="00000000-0000-0000-0000-000000000000"
            autoComplete="off"
            error={errors.destinationAccountId?.message}
            {...register('destinationAccountId')}
          />
          <Field
            label={`Amount (${currency})`}
            inputMode="decimal"
            placeholder="125.50"
            error={errors.amount?.message}
            {...register('amount')}
          />

          {/* Idempotency Key Live Indicator */}
          <div className="rounded-lg border border-slate-800 bg-slate-950/80 p-3 space-y-1">
            <div className="flex items-center justify-between text-xs text-slate-400">
              <span className="flex items-center gap-1.5 font-medium text-slate-300">
                <KeyRound className="size-3.5 text-indigo-400" />
                Idempotency Protection
              </span>
              <span className="flex items-center gap-1 text-[11px] font-mono text-emerald-400">
                <ShieldCheck className="size-3" /> Double-spend Prevention
              </span>
            </div>
            {activeKey && (
              <p className="font-mono text-[11px] text-slate-400 truncate">
                Active Key: <span className="text-slate-200">{activeKey}</span>
              </p>
            )}
          </div>

          <div className="flex items-center gap-3">
            <Button
              type="submit"
              disabled={isSubmitting}
              className="bg-emerald-500 hover:bg-emerald-600 text-slate-950 font-semibold shadow-md shadow-emerald-500/20 transition-all"
            >
              {isSubmitting ? 'Sending...' : canRetry ? 'Try again' : 'Send transfer'}
            </Button>
            {canRetry && (
              <p className="text-xs text-amber-400/90 font-medium">
                Retrying reuses the active idempotency key safely.
              </p>
            )}
          </div>
        </form>
      </CardContent>
    </Card>
  )
}
