import type { Win } from '../game/protocol';
import { formatMoney, formatMultiplier } from '../game/format';

export function WinFeed({ wins }: { wins: Win[] }) {
  return (
    <section className="panel feed">
      <h2>Live wins</h2>
      {wins.length === 0 ? (
        <p className="muted">Nobody has won yet. Wins from everyone watching show here.</p>
      ) : (
        <ul>
          {wins.map((w, i) => (
            <li key={i}>
              <strong>{w.name}</strong> won {formatMoney(w.payoutCents)} at {formatMultiplier(w.multiplierX100)}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
