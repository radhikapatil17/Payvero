import { Route, Routes } from 'react-router-dom'
import { RequireAdmin } from '@/auth/RequireAdmin'
import { RequireAuth } from '@/auth/RequireAuth'
import { AppLayout } from '@/components/AppLayout'
import { NotFoundPage } from '@/routes/NotFoundPage'
import { AuditLogPage } from '@/routes/admin/AuditLogPage'
import { FraudQueuePage } from '@/routes/admin/FraudQueuePage'
import { DashboardPage } from '@/routes/dashboard/DashboardPage'
import { StatementsPage } from '@/routes/statements/StatementsPage'
import { TransfersPage } from '@/routes/transfers/TransfersPage'
import { LoginPage } from '@/routes/auth/LoginPage'
import { RegisterPage } from '@/routes/auth/RegisterPage'

/**
 * Routing shape only. The pages behind each guard arrive in the next steps;
 * what matters here is that every authenticated route sits inside RequireAuth
 * so no screen can be added later that forgets to be guarded.
 */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/transfers" element={<TransfersPage />} />
          <Route path="/statements" element={<StatementsPage />} />

          <Route element={<RequireAdmin />}>
            <Route path="/admin/fraud" element={<FraudQueuePage />} />
            <Route path="/admin/audit" element={<AuditLogPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
