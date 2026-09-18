import { tokens } from './tokens'

/**
 * Cross-tab coordination for a single-use refresh token.
 *
 * The API rotates refresh tokens and treats replaying a spent one as theft,
 * revoking the whole session lineage. That is the right design, and it means
 * two tabs must never spend the same token: if they do, the loser gets the
 * user signed out everywhere. So the constraint is met here rather than
 * softened on the server.
 *
 * Two mechanisms, because they solve different halves:
 *   - a Web Lock serialises refreshes across tabs, so only one is ever in
 *     flight for the whole browser;
 *   - a BroadcastChannel hands the result to the other tabs, so a tab that
 *     waited adopts the new token instead of spending the old one.
 */

type AuthMessage =
  | { type: 'refreshed'; accessToken: string; refreshToken: string }
  | { type: 'signed-out' }

const CHANNEL_NAME = 'payvero-auth'
const LOCK_NAME = 'payvero-refresh'

const channel: BroadcastChannel | null =
  typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL_NAME)

type SignOutListener = () => void
const signOutListeners = new Set<SignOutListener>()

if (channel) {
  channel.onmessage = (event: MessageEvent<AuthMessage>) => {
    const message = event.data
    if (message.type === 'refreshed') {
      // Adopt rather than refresh: another tab already rotated, and spending
      // our now-stale token would look exactly like a replay.
      tokens.set(message.accessToken, message.refreshToken)
    } else {
      tokens.clear()
      signOutListeners.forEach((listener) => listener())
    }
  }
}

export const authChannel = {
  announceRefreshed(accessToken: string, refreshToken: string) {
    channel?.postMessage({ type: 'refreshed', accessToken, refreshToken } satisfies AuthMessage)
  },

  announceSignedOut() {
    channel?.postMessage({ type: 'signed-out' } satisfies AuthMessage)
  },

  onSignedOut(listener: SignOutListener): () => void {
    signOutListeners.add(listener)
    return () => {
      signOutListeners.delete(listener)
    }
  },
}

/**
 * Runs `work` with at most one holder across all tabs. Falls back to running
 * directly where the Web Locks API is unavailable — including jsdom, so tests
 * exercise the in-tab single-flight path without needing a lock shim.
 */
export async function withRefreshLock<T>(work: () => Promise<T>): Promise<T> {
  const locks = globalThis.navigator?.locks
  if (!locks?.request) {
    return work()
  }
  return locks.request(LOCK_NAME, work) as Promise<T>
}
