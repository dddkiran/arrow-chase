import { describe, expect, it } from 'vitest';
import { formatMoney, formatMultiplier, parseStake } from './format';

describe('money formatting', () => {
  it.each([
    [0, '0.00'],
    [5, '0.05'],
    [250, '2.50'],
    [100_000, '1,000.00'],
    [-150, '-1.50'],
  ])('formats %i cents as %s', (cents, text) => {
    expect(formatMoney(cents)).toBe(text);
  });

  it('formats multipliers with two decimals', () => {
    expect(formatMultiplier(245)).toBe('2.45x');
    expect(formatMultiplier(5000)).toBe('50.00x');
  });
});

describe('stake parsing', () => {
  it.each([
    ['1', 100],
    ['2.5', 250],
    ['2.50', 250],
    [' 10.05 ', 1005],
    ['0.10', 10],
  ])('parses %s as %i cents without floating point error', (input, cents) => {
    expect(parseStake(input)).toBe(cents);
  });

  it.each(['', 'abc', '1.234', '-1', '1e3', '.5'])('rejects %s', (input) => {
    expect(parseStake(input)).toBeNull();
  });
});
