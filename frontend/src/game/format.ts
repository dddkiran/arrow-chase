export function formatMoney(cents: number): string {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100).toLocaleString('en-US');
  const fraction = String(abs % 100).padStart(2, '0');
  return `${sign}${whole}.${fraction}`;
}

export function formatMultiplier(x100: number): string {
  return `${(x100 / 100).toFixed(2)}x`;
}

/** Parses what a player typed ("2", "2.5", "2.50") into cents. Returns null if it is not a valid amount. */
export function parseStake(input: string): number | null {
  const trimmed = input.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) {
    return null;
  }
  const [whole, fraction = ''] = trimmed.split('.');
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
}

const REASONS: Record<string, string> = {
  CELL_CLOSED: 'That block is closed right now.',
  INVALID_STAKE: 'Stake is outside the allowed range.',
  INSUFFICIENT_FUNDS: 'Not enough balance for that stake.',
  TOO_MANY_OPEN_BETS: 'Too many open bets. Wait for some to settle.',
  INVALID_CELL: 'That block does not exist.',
  UNKNOWN_PLAYER: 'Session not recognised. Reload the page.',
};

export function describeRejection(reason: string): string {
  return REASONS[reason] ?? `Bet rejected (${reason}).`;
}
