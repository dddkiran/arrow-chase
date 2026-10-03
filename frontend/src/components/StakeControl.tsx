import { formatMoney, parseStake } from '../game/format';

const QUICK_STAKES = ['0.50', '1.00', '5.00', '10.00', '25.00'];

interface StakeControlProps {
  value: string;
  onChange: (value: string) => void;
  minStakeCents: number;
  maxStakeCents: number;
  balanceCents: number;
}

/** Returns the stake in cents if valid, or a message explaining why not. */
export function validateStake(
  input: string,
  minStakeCents: number,
  maxStakeCents: number,
  balanceCents: number,
): { cents: number } | { error: string } {
  const cents = parseStake(input);
  if (cents === null) return { error: 'Enter an amount like 2.50' };
  if (cents < minStakeCents) return { error: `Minimum stake is ${formatMoney(minStakeCents)}` };
  if (cents > maxStakeCents) return { error: `Maximum stake is ${formatMoney(maxStakeCents)}` };
  if (cents > balanceCents) return { error: 'Not enough balance' };
  return { cents };
}

export function StakeControl({ value, onChange, minStakeCents, maxStakeCents, balanceCents }: StakeControlProps) {
  const result = validateStake(value, minStakeCents, maxStakeCents, balanceCents);
  return (
    <section className="panel stake">
      <label htmlFor="stake">Bet amount</label>
      <input
        id="stake"
        inputMode="decimal"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={'error' in result}
        aria-describedby="stake-hint"
      />
      <div className="chips">
        {QUICK_STAKES.map((s) => (
          <button key={s} type="button" className={s === value ? 'chip active' : 'chip'} onClick={() => onChange(s)}>
            {s}
          </button>
        ))}
      </div>
      <p id="stake-hint" className={'error' in result ? 'hint error' : 'hint'}>
        {'error' in result ? result.error : 'Click any open block to bet this amount on it.'}
      </p>
    </section>
  );
}
