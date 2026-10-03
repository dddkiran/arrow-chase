import { describe, expect, it } from 'vitest';
import type { Bet, ServerMessage, StepMessage, WelcomeMessage } from './protocol';
import { initialState, openBets, reducer, settledBets, tileViews, type Action, type GameState } from './state';

const step = (n: number, overrides: Partial<StepMessage> = {}): StepMessage => ({
  type: 'step',
  step: n,
  arrow: { row: 0, col: 0, direction: 'RIGHT' },
  trail: [{ row: 0, col: 0 }],
  multipliersX100: [
    [0, 150, 300],
    [200, 250, 0],
    [400, 0, 0],
  ],
  playersOnline: 1,
  wins: [],
  serverTimeMillis: 0,
  ...overrides,
});

const bet = (overrides: Partial<Bet> = {}): Bet => ({
  id: 'b1',
  requestId: 'r1',
  row: 1,
  col: 1,
  stakeCents: 100,
  lockedMultiplierX100: 250,
  placedStep: 10,
  expiresAtStep: 20,
  status: 'OPEN',
  payoutCents: 0,
  settledStep: null,
  ...overrides,
});

const welcome = (overrides: Partial<WelcomeMessage> = {}): WelcomeMessage => ({
  type: 'welcome',
  playerId: 'p1',
  name: 'Fox-1',
  balanceCents: 10_000,
  game: { rows: 3, cols: 3, stepMillis: 700, betWindowSteps: 10, minStakeCents: 10, maxStakeCents: 10_000 },
  state: step(10),
  openBets: [],
  recentBets: [],
  ...overrides,
});

const server = (message: ServerMessage): Action => ({ type: 'server', message });

function play(...actions: Action[]): GameState {
  return actions.reduce(reducer, initialState);
}

describe('game state', () => {
  it('takes balance, board and bets from the welcome snapshot', () => {
    const state = play(server(welcome({ openBets: [bet()], recentBets: [bet({ id: 'b0', status: 'LOST', settledStep: 9 })] })));

    expect(state.connection).toBe('open');
    expect(state.balanceCents).toBe(10_000);
    expect(state.board?.step).toBe(10);
    expect(openBets(state).map((b) => b.id)).toEqual(['b1']);
    expect(settledBets(state).map((b) => b.id)).toEqual(['b0']);
  });

  it('shows a pending bet until the server locks it, then shows the locked multiplier', () => {
    let state = play(server(welcome()), { type: 'betRequested', bet: { requestId: 'r1', row: 1, col: 1, stakeCents: 100 } });
    expect(tileViews(state)[1][1].pending).toHaveLength(1);
    expect(tileViews(state)[1][1].open).toHaveLength(0);

    state = reducer(state, server({ type: 'betLocked', bet: bet(), balanceCents: 9_900 }));

    const tile = tileViews(state)[1][1];
    expect(tile.pending).toHaveLength(0);
    expect(tile.open[0].lockedMultiplierX100).toBe(250);
    expect(state.balanceCents).toBe(9_900);
  });

  it('keeps the locked multiplier when the live board changes', () => {
    const state = play(
      server(welcome()),
      server({ type: 'betLocked', bet: bet(), balanceCents: 9_900 }),
      server(step(11, { multipliersX100: [[0, 0, 0], [0, 999, 0], [0, 0, 0]] })),
    );
    const tile = tileViews(state)[1][1];
    expect(tile.multiplierX100).toBe(999);
    expect(tile.open[0].lockedMultiplierX100).toBe(250);
  });

  it('shows the outcome only when the server settles it', () => {
    const state = play(
      server(welcome()),
      server({ type: 'betLocked', bet: bet(), balanceCents: 9_900 }),
      server(step(11)),
      server({ type: 'betSettled', bet: bet({ status: 'WON', payoutCents: 250, settledStep: 11 }), balanceCents: 10_150 }),
    );
    expect(openBets(state)).toHaveLength(0);
    expect(settledBets(state)[0].status).toBe('WON');
    expect(state.balanceCents).toBe(10_150);
    expect(state.notices[0]).toMatchObject({ kind: 'win', text: 'Won 2.50 at 2.50x' });
    expect(tileViews(state)[1][1].justSettled).toBe('WON');
  });

  it('never turns a settled bet back into an open one if messages arrive out of order', () => {
    const state = play(
      server(welcome()),
      server({ type: 'betSettled', bet: bet({ status: 'LOST', settledStep: 20 }), balanceCents: 9_900 }),
      server({ type: 'betLocked', bet: bet(), balanceCents: 9_900 }),
    );
    expect(state.bets.b1.status).toBe('LOST');
  });

  it('clears the pending bet and explains a rejection', () => {
    const state = play(
      server(welcome()),
      { type: 'betRequested', bet: { requestId: 'r9', row: 0, col: 0, stakeCents: 100 } },
      server({ type: 'betRejected', requestId: 'r9', reason: 'CELL_CLOSED', balanceCents: 10_000 }),
    );
    expect(state.pending).toEqual({});
    expect(state.notices[0]).toMatchObject({ kind: 'error', text: 'That block is closed right now.' });
  });

  it('keeps pending bets across a reconnect so they can be re-sent', () => {
    const state = play(
      server(welcome()),
      { type: 'betRequested', bet: { requestId: 'r1', row: 1, col: 1, stakeCents: 100 } },
      { type: 'connection', status: 'reconnecting' },
      server(welcome()),
    );
    expect(Object.keys(state.pending)).toEqual(['r1']);
  });

  it('puts the newest wins from everyone at the top of the feed', () => {
    const state = play(
      server(welcome()),
      server(step(11, { wins: [{ name: 'A', row: 0, col: 1, multiplierX100: 150, payoutCents: 150 }] })),
      server(step(12, { wins: [{ name: 'B', row: 0, col: 2, multiplierX100: 300, payoutCents: 300 }] })),
    );
    expect(state.feed.map((w) => w.name)).toEqual(['B', 'A']);
  });

  it('marks the arrow and its trail on the board', () => {
    const state = play(
      server(welcome({ state: step(10, { arrow: { row: 1, col: 0, direction: 'DOWN' }, trail: [{ row: 1, col: 0 }, { row: 0, col: 0 }] }) })),
    );
    const tiles = tileViews(state);
    expect(tiles[1][0].isArrow).toBe(true);
    expect(tiles[1][0].trailIndex).toBe(0);
    expect(tiles[0][0].trailIndex).toBe(1);
  });
});
