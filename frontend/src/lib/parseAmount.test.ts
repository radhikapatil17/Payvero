import { describe, expect, it } from 'vitest'
import { minorUnitsToAmountInput, parseAmountToMinorUnits } from './parseAmount'

describe('parseAmountToMinorUnits', () => {
  it.each([
    ['1', 100],
    ['1.5', 150],
    ['1.50', 150],
    ['0.01', 1],
    ['0.1', 10],
    ['125.50', 12_550],
    ['1,250.75', 125_075],
    ['  42  ', 4_200],
    ['.99', 99],
  ])('reads %s as %d minor units', (input, expected) => {
    expect(parseAmountToMinorUnits(input)).toBe(expected)
  })

  /**
   * The values a float would get wrong. Each of these is exact here because the
   * arithmetic never leaves the integers.
   */
  it.each([
    ['1.15', 115],
    ['1.16', 116],
    ['8.35', 835],
    ['1.005', null], // three decimals: refused, not silently rounded
    ['1.999', null],
  ])('handles %s without float drift', (input, expected) => {
    expect(parseAmountToMinorUnits(input)).toBe(expected)
  })

  it.each([['abc'], [''], ['   '], ['-5'], ['1.2.3'], ['$5'], ['1e3']])(
    'refuses %s rather than guessing',
    (input) => {
      expect(parseAmountToMinorUnits(input)).toBeNull()
    },
  )

  it('round-trips through the input format', () => {
    expect(minorUnitsToAmountInput(12_550)).toBe('125.50')
    expect(parseAmountToMinorUnits(minorUnitsToAmountInput(12_550))).toBe(12_550)
  })
})
