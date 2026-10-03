import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Tile } from './Board';
import { StakeControl, validateStake } from './StakeControl';
import type { TileView } from '../game/state';
import type { Bet } from '../game/protocol';

const tile = (overrides: Partial<TileView> = {}): TileView => ({
  row: 2,
  col: 3,
  multiplierX100: 310,
  open: [],
  pending: [],
  justSettled: null,
  trailIndex: null,
  isArrow: false,
  ...overrides,
});

const lockedBet: Bet = {
  id: 'b1',
  requestId: 'r1',
  row: 2,
  col: 3,
  stakeCents: 100,
  lockedMultiplierX100: 245,
  placedStep: 5,
  expiresAtStep: 15,
  status: 'OPEN',
  payoutCents: 0,
  settledStep: null,
};

describe('Tile', () => {
  it('shows the live multiplier and bets on click', async () => {
    const onSelect = vi.fn();
    render(<Tile tile={tile()} step={5} disabled={false} onSelect={onSelect} />);

    await userEvent.click(screen.getByRole('button', { name: 'Block 3-4, 3.10x' }));

    expect(onSelect).toHaveBeenCalledWith(2, 3);
  });

  it('shows the locked multiplier separately from the live one, with moves remaining', () => {
    render(<Tile tile={tile({ open: [lockedBet] })} step={8} disabled={false} onSelect={() => {}} />);

    expect(screen.getByText('3.10x')).toBeInTheDocument();
    expect(screen.getByTitle('Multiplier locked for your bet')).toHaveTextContent('2.45x');
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('says "locking…" while the server has not confirmed yet', () => {
    render(
      <Tile
        tile={tile({ pending: [{ requestId: 'r1', row: 2, col: 3, stakeCents: 100 }] })}
        step={5}
        disabled={false}
        onSelect={() => {}}
      />,
    );
    expect(screen.getByText('locking…')).toBeInTheDocument();
  });

  it('cannot be clicked when the block is closed', async () => {
    const onSelect = vi.fn();
    render(<Tile tile={tile({ multiplierX100: 0 })} step={5} disabled={false} onSelect={onSelect} />);

    const button = screen.getByRole('button', { name: 'Block 3-4, closed' });
    expect(button).toBeDisabled();
    await userEvent.click(button);
    expect(onSelect).not.toHaveBeenCalled();
  });
});

describe('stake', () => {
  it('validates against limits and balance', () => {
    expect(validateStake('2.50', 10, 10_000, 1_000)).toEqual({ cents: 250 });
    expect(validateStake('0.05', 10, 10_000, 1_000)).toEqual({ error: 'Minimum stake is 0.10' });
    expect(validateStake('101', 10, 10_000, 1_000_000)).toEqual({ error: 'Maximum stake is 100.00' });
    expect(validateStake('20', 10, 10_000, 1_000)).toEqual({ error: 'Not enough balance' });
    expect(validateStake('x', 10, 10_000, 1_000)).toEqual({ error: 'Enter an amount like 2.50' });
  });

  it('lets a player pick a quick stake', async () => {
    const onChange = vi.fn();
    render(<StakeControl value="1.00" onChange={onChange} minStakeCents={10} maxStakeCents={10_000} balanceCents={100_000} />);

    await userEvent.click(screen.getByRole('button', { name: '5.00' }));

    expect(onChange).toHaveBeenCalledWith('5.00');
  });
});
