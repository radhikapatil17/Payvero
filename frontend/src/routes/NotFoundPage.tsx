import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'

export function NotFoundPage() {
  const navigate = useNavigate()

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 text-center">
      <div className="space-y-1">
        <h1 className="text-lg font-medium">Page not found</h1>
        <p className="text-sm text-muted-foreground">That route does not exist.</p>
      </div>
      <Button onClick={() => void navigate('/')}>Back to dashboard</Button>
    </div>
  )
}
