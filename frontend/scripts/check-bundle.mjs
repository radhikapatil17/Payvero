#!/usr/bin/env node
/**
 * Guards the code split that keeps Recharts out of the entry chunk.
 *
 * Bundle shape is not something a unit test can see: replacing the lazy import
 * with a static one leaves every test passing and every screen working, and
 * only the download gets worse. That regression is invisible in review and
 * invisible in CI unless something looks at the built output, which is what
 * this does.
 *
 * Run after `npm run build`.
 */
import { gzipSync } from 'node:zlib'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

// Not `import.meta.dirname`, which only exists from Node 20.11; CI pins 20.
const ASSETS = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist', 'assets')

/**
 * A ceiling, not a target. Set above the current figure so ordinary feature
 * work does not trip it, and low enough that folding a charting library back
 * into the entry chunk does.
 */
const MAX_ENTRY_GZIP_KB = 200
const HEAVY = { name: 'recharts', markers: /recharts|ResponsiveContainer|CartesianGrid/ }

let files
try {
  files = readdirSync(ASSETS).filter((name) => name.endsWith('.js'))
} catch {
  fail(`No build output at ${ASSETS}. Run \`npm run build\` first.`)
}

if (files.length === 0) {
  fail(`No JavaScript emitted into ${ASSETS}.`)
}

const chunks = files.map((name) => {
  const source = readFileSync(join(ASSETS, name))
  return {
    name,
    gzipKb: gzipSync(source).length / 1024,
    containsHeavy: HEAVY.markers.test(source.toString('utf8')),
  }
})

// The entry is the chunk Vite names `index-*`; everything else is split out.
const entry = chunks.find((chunk) => chunk.name.startsWith('index-'))
if (!entry) {
  fail(`Could not find an entry chunk among: ${files.join(', ')}`)
}

const problems = []

if (entry.containsHeavy) {
  problems.push(
    `${HEAVY.name} is in the entry chunk (${entry.name}). It should be behind a lazy import ` +
      `so signing in does not download a chart nobody has opened.`,
  )
}

const carriers = chunks.filter((chunk) => chunk.containsHeavy)
if (carriers.length === 0) {
  problems.push(`${HEAVY.name} is in no chunk at all — the marker check is probably stale.`)
} else if (carriers.length > 1) {
  problems.push(
    `${HEAVY.name} is duplicated across ${carriers.length} chunks ` +
      `(${carriers.map((c) => c.name).join(', ')}), which costs more than not splitting it.`,
  )
}

if (entry.gzipKb > MAX_ENTRY_GZIP_KB) {
  problems.push(
    `Entry chunk is ${entry.gzipKb.toFixed(1)} kB gzipped, over the ${MAX_ENTRY_GZIP_KB} kB ceiling.`,
  )
}

for (const chunk of chunks.sort((a, b) => b.gzipKb - a.gzipKb)) {
  const tag = chunk === entry ? 'entry' : 'split'
  console.log(
    `  ${tag}  ${chunk.name.padEnd(34)} ${chunk.gzipKb.toFixed(1).padStart(7)} kB gzip` +
      `${chunk.containsHeavy ? `  <- ${HEAVY.name}` : ''}`,
  )
}

if (problems.length > 0) {
  console.error('\nBundle check failed:')
  for (const problem of problems) console.error(`  - ${problem}`)
  process.exit(1)
}

console.log(`\nBundle check passed: ${HEAVY.name} is split out and the entry chunk is under budget.`)

function fail(message) {
  console.error(`Bundle check failed: ${message}`)
  process.exit(1)
}
