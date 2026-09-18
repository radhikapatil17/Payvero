import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/authContext'
import { Field } from '@/components/Field'
import { FullPageLoader } from '@/components/FullPageLoader'
import { Button } from '@/components/ui/button'
import { applyServerErrors } from '@/lib/forms/serverErrors'
import { AuthShell } from './AuthShell'
import { FormAlert } from './FormAlert'
import { registerSchema, type RegisterValues } from './authSchemas'

const FIELDS = ['email', 'password'] as const

export function RegisterPage() {
  const { status, register: createAccount } = useAuth()
  const navigate = useNavigate()

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { email: '', password: '' },
  })

  if (status === 'resolving') {
    return <FullPageLoader label="Restoring your session" />
  }
  if (status === 'authenticated') {
    return <Navigate to="/" replace />
  }

  const onSubmit = handleSubmit(async (values) => {
    try {
      await createAccount(values.email, values.password)
      void navigate('/', { replace: true })
    } catch (error) {
      applyServerErrors(error, setError, FIELDS)
    }
  })

  return (
    <AuthShell
      title="Create an account"
      subtitle="Opens a accounting account you can fund and transfer from."
      footer={
        <>
          Already have one?{' '}
          <Link to="/login" className="font-medium text-foreground underline underline-offset-4">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <FormAlert message={errors.root?.message} />

        <Field
          label="Email"
          type="email"
          autoComplete="email"
          autoFocus
          error={errors.email?.message}
          {...register('email')}
        />
        <Field
          label="Password"
          type="password"
          autoComplete="new-password"
          hint="At least 12 characters."
          error={errors.password?.message}
          {...register('password')}
        />

        <Button type="submit" className="w-full" disabled={isSubmitting}>
          {isSubmitting ? 'Creating account…' : 'Create account'}
        </Button>
      </form>
    </AuthShell>
  )
}
