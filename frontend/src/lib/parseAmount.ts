/**
 * Turns what a person typed into integer minor units, without ever going
 * through a float.
 *
 * `Math.round(parseFloat('1.115') * 100)` is 111 on some inputs and 112 on
 * others, because the decimal never existed exactly in binary. Splitting the
 * string and padding the fraction keeps the arithmetic in integers, which is
 * the same discipline the journal uses on the other side of the wire.
 */
export function parseAmountToMinorUnits(input: string): number | null {
  const trimmed = input.trim().replace(/,/g, '')
  if (trimmed === '') return null

  // Optional leading digits, optional fraction of at most two places. Anything
  // else — a third decimal, a sign, a stray letter — is a refusal rather than
  // a silent rounding.
  const match = /^(\d*)(?:\.(\d{0,2}))?$/.exec(trimmed)
  if (!match) return null

  const [, whole = '', fraction = ''] = match
  if (whole === '' && fraction === '') return null

  const cents = Number(whole || '0') * 100 + Number(fraction.padEnd(2, '0') || '0')
  return Number.isSafeInteger(cents) ? cents : null
}

/** Minor units back to an editable string, for prefilling a retry. */
export function minorUnitsToAmountInput(minorUnits: number): string {
  return (minorUnits / 100).toFixed(2)
}
