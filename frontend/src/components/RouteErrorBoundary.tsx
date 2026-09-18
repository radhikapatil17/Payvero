import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { RotateCcw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

type BoundaryProps = {
  children: ReactNode
  fullPage: boolean
  onReset: () => void
}
type BoundaryState = { error: Error | null }

/**
 * Class component because error boundaries still have no hook equivalent.
 * Kept private so no caller can mount one without the reset wiring below.
 */
class Boundary extends Component<BoundaryProps, BoundaryState> {
  state: BoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): BoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Somewhere to attach real error reporting later; logging beats swallowing.
    console.error('Unhandled render error', error, info.componentStack)
  }

  private retry = () => {
    this.props.onReset()
    this.setState({ error: null })
  }

  render() {
    if (!this.state.error) {
      return this.props.children
    }

    return (
      <div
        role="alert"
        data-testid="route-error"
        className={cn(
          'flex flex-col items-center justify-center gap-4 rounded-lg border border-dashed p-8 text-center',
          this.props.fullPage && 'min-h-dvh border-none',
        )}
      >
        <div className="space-y-1">
          <h2 className="text-lg font-medium">Something went wrong on this page</h2>
          {/*
            Stated because it is the first thing anyone looking at a broken
            banking screen wants to know, and because it is true: rendering is
            downstream of every write, and the journal is append-only besides.
          */}
          <p className="text-sm text-muted-foreground">
            Your money is unaffected — nothing on this screen changes the journal.
          </p>
        </div>

        <div className="flex flex-wrap justify-center gap-2">
          <Button onClick={this.retry}>
            <RotateCcw aria-hidden className="size-4" />
            Try again
          </Button>
          {/*
            A real navigation rather than a link, so the app remounts from
            scratch. If the failure is in shared state a client-side route
            change would carry it along.
          */}
          <Button variant="outline" onClick={() => window.location.assign('/')}>
            Back to dashboard
          </Button>
        </div>
      </div>
    )
  }
}

/**
 * A render error in one screen should not blank the whole app.
 *
 * Two things make the retry more than a button that redraws the same crash.
 * The cached data is dropped first, so a bad response that caused the throw is
 * refetched rather than replayed; and the boundary is keyed by route, so
 * navigating away clears the error instead of leaving it stuck across a screen
 * that was never broken.
 */
export function RouteErrorBoundary({
  children,
  fullPage = false,
}: {
  children: ReactNode
  fullPage?: boolean
}) {
  const queryClient = useQueryClient()
  const location = useLocation()

  return (
    <Boundary
      key={location.pathname}
      fullPage={fullPage}
      onReset={() => queryClient.resetQueries()}
    >
      {children}
    </Boundary>
  )
}
