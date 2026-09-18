/**
 * Money is integer minor units everywhere, end to end. It is divided by 100
 * exactly once, here, at the moment it becomes text for a human to read.
 * Nothing upstream of this ever holds a fractional amount.
 */

const formatters = new Map<string, Intl.NumberFormat>()

function formatterFor(currency: string) {
  let formatter = formatters.get(currency)
  if (!formatter) {
    formatter = new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })
    formatters.set(currency, formatter)
  }
  return formatter
}

export function formatMoney(minorUnits: number, currency = 'USD'): string {
  return formatterFor(currency).format(minorUnits / 100)
}

/**
 * Prefixed with an explicit sign as well as being coloured, so direction is
 * never carried by colour alone.
 */
export function formatSignedMoney(
  minorUnits: number,
  direction: 'DEBIT' | 'CREDIT',
  currency = 'USD',
): string {
  const sign = direction === 'CREDIT' ? '+' : '−'
  return `${sign}${formatMoney(minorUnits, currency)}`
}

export function formatCompactMoney(minorUnits: number, currency = 'USD'): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(minorUnits / 100)
}
