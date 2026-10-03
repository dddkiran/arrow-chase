import type { Bet } from '../game/protocol';
import type { PendingBet } from '../game/state';
import { formatMoney, formatMultiplier } from '../game/format';

interface BetListProps {
  open: Bet[];
  pending: PendingBet[];
  settled: Bet[];
  step: number;
}

const block = (b: { row: number; col: number }) => `${b.row + 1}-${b.col + 1}`;

export function BetList({ open, pending, settled, step }: BetListProps) {
  return (
    <section className="panel bets">
      <h2>My bets</h2>
      {open.length === 0 && pending.length === 0 && settled.length === 0 && (
        <p className="muted">No bets yet. Pick a stake and click a block.</p>
      )}
      <ul>
        {pending.map((p) => (
          <li key={p.requestId} className="bet pending">
            <span>Block {block(p)}</span>
            <span>{formatMoney(p.stakeCents)}</span>
            <span className="status">locking…</span>
          </li>
        ))}
        {open.map((b) => (
          <li key={b.id} className="bet open">
            <span>Block {block(b)}</span>
            <span>
              {formatMoney(b.stakeCents)} @ <strong>{formatMultiplier(b.lockedMultiplierX100)}</strong>
            </span>
            <span className="status">
              live · {Math.max(0, b.expiresAtStep - step)} moves left
            </span>
          </li>
        ))}
        {settled.map((b) => (
          <li key={b.id} className={`bet ${b.status === 'WON' ? 'won' : 'lost'}`}>
            <span>Block {block(b)}</span>
            <span>
              {formatMoney(b.stakeCents)} @ {formatMultiplier(b.lockedMultiplierX100)}
            </span>
            <span className="status">{b.status === 'WON' ? `won ${formatMoney(b.payoutCents)}` : 'missed'}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
