/**
 * Where the two tokens live, and why they live in different places.
 *
 * The access token is held in memory only. It is attached to every request, so
 * keeping it out of any storage a script can read is the single cheapest
 * reduction in blast radius from an XSS bug.
 *
 * The refresh token has to survive a page reload, so it goes to localStorage.
 * That is still readable by injected script, and this split does not make it
 * safe — it narrows the window rather than closing it. The real fix is an
 * httpOnly cookie, which the API does not currently issue. Stated plainly here
 * rather than left to be inferred from the code.
 */

const REFRESH_TOKEN_KEY = 'payvero.refreshToken'

let accessToken: string | null = null

export const tokens = {
  getAccessToken: () => accessToken,

  getRefreshToken: () => {
    try {
      return localStorage.getItem(REFRESH_TOKEN_KEY)
    } catch {
      // Private browsing modes can throw on access rather than returning null.
      return null
    }
  },

  set(nextAccessToken: string, nextRefreshToken: string) {
    accessToken = nextAccessToken
    try {
      localStorage.setItem(REFRESH_TOKEN_KEY, nextRefreshToken)
    } catch {
      // A session that cannot persist its refresh token still works until the
      // access token expires, which is better than refusing to sign in.
    }
  },

  clear() {
    accessToken = null
    try {
      localStorage.removeItem(REFRESH_TOKEN_KEY)
    } catch {
      // nothing useful to do
    }
  },
}
