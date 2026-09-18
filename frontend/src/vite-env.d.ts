/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Backend origin. Empty means same-origin through the dev proxy. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
