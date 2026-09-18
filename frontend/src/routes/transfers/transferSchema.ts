import { z } from 'zod'
import { parseAmountToMinorUnits } from '@/lib/parseAmount'

export const transferSchema = z.object({
  /**
   * `guid`, not `uuid`. Zod's `uuid` enforces the RFC version and variant
   * nibbles, which would reject perfectly real account ids: the seeded treasury
   * is 00000000-0000-0000-0000-000000000001, and the server accepts any
   * well-formed UUID. A client that refuses ids the server would have honoured
   * is wrong in the more annoying direction — it blocks a valid transfer with a
   * message the user cannot act on.
   */
  destinationAccountId: z
    .string()
    .min(1, 'Enter the recipient account id')
    .pipe(z.guid('That does not look like an account id')),
  amount: z
    .string()
    .min(1, 'Enter an amount')
    .refine((value) => parseAmountToMinorUnits(value) !== null, {
      message: 'Enter an amount like 125.50',
    })
    .refine((value) => (parseAmountToMinorUnits(value) ?? 0) > 0, {
      message: 'Amount must be more than zero',
    }),
})

export type TransferValues = z.infer<typeof transferSchema>
