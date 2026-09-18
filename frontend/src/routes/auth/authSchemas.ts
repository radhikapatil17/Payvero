import { z } from 'zod'

/**
 * Sign-in validates shape, not policy: it checks against what is stored, and
 * enforcing a current password rule here would lock out an account created
 * before that rule existed. The server's LoginRequest is deliberately looser
 * than its RegisterRequest for the same reason.
 */
export const loginSchema = z.object({
  email: z.string().min(1, 'Enter your email'),
  password: z.string().min(1, 'Enter your password'),
})

/** Mirrors the server's RegisterRequest so the common case fails client-side. */
export const registerSchema = z.object({
  email: z.email('Enter a valid email address').max(255),
  password: z
    .string()
    .min(12, 'Use at least 12 characters')
    .max(200, 'That is longer than 200 characters'),
})

export type LoginValues = z.infer<typeof loginSchema>
export type RegisterValues = z.infer<typeof registerSchema>
