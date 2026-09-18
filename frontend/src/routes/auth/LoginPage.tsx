import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/authContext'
import { Field } from '@/components/Field'
import { FullPageLoader } from '@/components/FullPageLoader'
import { Button } from '@/components/ui/button'
import { applyServerErrors } from '@/lib/forms/serverErrors'
import { AuthShell } from './AuthShell'
import { FormAlert } from './FormAlert'
import { loginSchema, type LoginValues } from './authSchemas'

const FIELDS = ['email', 'password'] as const

export function LoginPage() {
  const { status, signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const {
    register,
    handleSubmit,
    setValue,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  })

  if (status === 'resolving') {
    return <FullPageLoader label="Restoring your Payvero session..." />
  }
  if (status === 'authenticated') {
    const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname
    return <Navigate to={from ?? '/'} replace />
  }

  const onSubmit = handleSubmit(async (values) => {
    try {
      await signIn(values.email, values.password)
      const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname
      void navigate(from ?? '/', { replace: true })
    } catch (error) {
      applyServerErrors(error, setError, FIELDS)
    }
  })

  const fillDemoAccount = (email: string) => {
    setValue('email', email)
    setValue('password', 'demo-password-123')
  }

  return (
    <AuthShell
      title="Sign in to Payvero"
      subtitle="Access your digital wallet, transaction ledger, and accounts."
      footer={
        <>
          New to Payvero?{' '}
          <Link to="/register" className="font-semibold text-emerald-400 hover:underline underline-offset-4">
            Create an account
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <FormAlert message={errors.root?.message} />

        <Field
          label="Email address"
          type="email"
          autoComplete="email"
          autoFocus
          error={errors.email?.message}
          {...register('email')}
        />
        <Field
          label="Password"
          type="password"
          autoComplete="current-password"
          error={errors.password?.message}
          {...register('password')}
        />

        <Button
          type="submit"
          className="w-full bg-emerald-500 hover:bg-emerald-600 text-slate-950 font-semibold shadow-lg shadow-emerald-500/25 transition-all"
          disabled={isSubmitting}
        >
          {isSubmitting ? 'Authenticating...' : 'Sign in'}
        </Button>
      </form>

      {/* Preset Demo Accounts */}
      <div className="space-y-2 border-t border-slate-800/60 pt-4">
        <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider text-center">
          Quick Demo Credentials
        </p>
        <div className="grid grid-cols-3 gap-2 text-xs">
          <button
            type="button"
            onClick={() => fillDemoAccount('alice@payvero.dev')}
            className="rounded-lg border border-slate-800 bg-slate-900/80 py-1.5 px-2 text-slate-300 hover:border-emerald-500/50 hover:text-emerald-400 transition-colors"
          >
            Alice (User)
          </button>
          <button
            type="button"
            onClick={() => fillDemoAccount('bob@payvero.dev')}
            className="rounded-lg border border-slate-800 bg-slate-900/80 py-1.5 px-2 text-slate-300 hover:border-emerald-500/50 hover:text-emerald-400 transition-colors"
          >
            Bob (User)
          </button>
          <button
            type="button"
            onClick={() => fillDemoAccount('admin@payvero.dev')}
            className="rounded-lg border border-slate-800 bg-slate-900/80 py-1.5 px-2 text-slate-300 hover:border-indigo-500/50 hover:text-indigo-400 transition-colors"
          >
            Admin
          </button>
        </div>
      </div>
    </AuthShell>
  )
}
