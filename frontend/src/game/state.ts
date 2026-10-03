import type { Bet, GameInfo, ServerMessage, StepMessage, Win } from './protocol';
import { describeRejection, formatMoney, formatMultiplier } from './format';

/**
 * Client state is a projection of server messages. The browser never decides an outcome: it only shows what the
 * server said, plus "pending" placeholders for bets it has sent but the server has not confirmed yet.
 */

export type Connection = 'connecting' | 'open' | 'reconnecting';

export interface PendingBet {
  requestId: string;
  row: number;
  col: number;
  stakeCents: number;
}

export interface Notice {
  id: number;
  kind: 'win' | 'loss' | 'error' | 'info';
  text: string;
}

export interface GameState {
  connection: Connection;
  playerId: string | null;
  name: string | null;
  game: GameInfo | null;
  board: StepMessage | null;
  balanceCents: number;
  bets: Record<string, Bet>;
  pending: Record<string, PendingBet>;
  feed: Win[];
  notices: Notice[];
  nextNoticeId: number;
}

export type Action =
  | { type: 'connection'; status: Connection }
  | { type: 'server'; message: ServerMessage }
  | { type: 'betRequested'; bet: PendingBet }
  | { type: 'dismissNotice'; id: number };

export const MAX_FEED = 8;
export const MAX_NOTICES = 4;
export const MAX_SETTLED_KEPT = 30;

export const initialState: GameState = {
  connection: 'connecting',
  playerId: null,
  name: null,
  game: null,
  board: null,
  balanceCents: 0,
  bets: {},
  pending: {},
  feed: [],
  notices: [],
  nextNoticeId: 1,
};

export function reducer(state: GameState, action: Action): GameState {
  switch (action.type) {
    case 'connection':
      return { ...state, connection: action.status };
    case 'betRequested':
      return { ...state, pending: { ...state.pending, [action.bet.requestId]: action.bet } };
    case 'dismissNotice':
      return { ...state, notices: state.notices.filter((n) => n.id !== action.id) };
    case 'server':
      return onServerMessage(state, action.message);
  }
}

function onServerMessage(state: GameState, message: ServerMessage): GameState {
  switch (message.type) {
    case 'welcome': {
      const bets: Record<string, Bet> = {};
      for (const bet of [...message.recentBets, ...message.openBets]) {
        bets[bet.id] = bet;
      }
      return {
        ...state,
        connection: 'open',
        playerId: message.playerId,
        name: message.name,
        game: message.game,
        board: message.state,
        balanceCents: message.balanceCents,
        bets,
        // Keep pending requests: the socket layer re-sends them and the server de-duplicates by requestId.
      };
    }
    case 'step':
      return {
        ...state,
        board: message,
        feed: message.wins.length ? [...[...message.wins].reverse(), ...state.feed].slice(0, MAX_FEED) : state.feed,
      };
    case 'betLocked': {
      const existing = state.bets[message.bet.id];
      // A settlement can never precede its lock from one server, but be defensive: never un-settle a bet.
      const bet = existing && existing.status !== 'OPEN' ? existing : message.bet;
      return {
        ...state,
        balanceCents: message.balanceCents,
        bets: { ...state.bets, [bet.id]: bet },
        pending: without(state.pending, message.bet.requestId),
      };
    }
    case 'betRejected':
      return addNotice(
        {
          ...state,
          balanceCents: message.balanceCents,
          pending: without(state.pending, message.requestId),
        },
        'error',
        describeRejection(message.reason),
      );
    case 'betSettled': {
      const bet = message.bet;
      const next = {
        ...state,
        balanceCents: message.balanceCents,
        bets: pruneSettled({ ...state.bets, [bet.id]: bet }),
        pending: without(state.pending, bet.requestId),
      };
      return bet.status === 'WON'
        ? addNotice(next, 'win', `Won ${formatMoney(bet.payoutCents)} at ${formatMultiplier(bet.lockedMultiplierX100)}`)
        : addNotice(next, 'loss', `Missed ${formatMoney(bet.stakeCents)} on block ${bet.row + 1}-${bet.col + 1}`);
    }
    case 'error':
      return addNotice(state, 'error', message.message);
  }
}

function addNotice(state: GameState, kind: Notice['kind'], text: string): GameState {
  const notice = { id: state.nextNoticeId, kind, text };
  return {
    ...state,
    notices: [notice, ...state.notices].slice(0, MAX_NOTICES),
    nextNoticeId: state.nextNoticeId + 1,
  };
}

function without<T>(record: Record<string, T>, key: string): Record<string, T> {
  if (!(key in record)) {
    return record;
  }
  const copy = { ...record };
  delete copy[key];
  return copy;
}

function pruneSettled(bets: Record<string, Bet>): Record<string, Bet> {
  const settled = Object.values(bets)
    .filter((b) => b.status !== 'OPEN')
    .sort((a, b) => (b.settledStep ?? 0) - (a.settledStep ?? 0));
  if (settled.length <= MAX_SETTLED_KEPT) {
    return bets;
  }
  const copy = { ...bets };
  for (const old of settled.slice(MAX_SETTLED_KEPT)) {
    delete copy[old.id];
  }
  return copy;
}

// ---- selectors ----

export function openBets(state: GameState): Bet[] {
  return Object.values(state.bets)
    .filter((b) => b.status === 'OPEN')
    .sort((a, b) => a.expiresAtStep - b.expiresAtStep);
}

export function settledBets(state: GameState): Bet[] {
  return Object.values(state.bets)
    .filter((b) => b.status !== 'OPEN')
    .sort((a, b) => (b.settledStep ?? 0) - (a.settledStep ?? 0));
}

/** Everything the board needs to draw one block. */
export interface TileView {
  row: number;
  col: number;
  multiplierX100: number;
  open: Bet[];
  pending: PendingBet[];
  /** Set for a short while after one of my bets on this block settles, so the tile can flash. */
  justSettled: 'WON' | 'LOST' | null;
  trailIndex: number | null;
  isArrow: boolean;
}

export const FLASH_STEPS = 2;

export function tileViews(state: GameState): TileView[][] {
  const { game, board } = state;
  if (!game || !board) {
    return [];
  }
  const tiles: TileView[][] = [];
  for (let r = 0; r < game.rows; r++) {
    const row: TileView[] = [];
    for (let c = 0; c < game.cols; c++) {
      row.push({
        row: r,
        col: c,
        multiplierX100: board.multipliersX100[r]?.[c] ?? 0,
        open: [],
        pending: [],
        justSettled: null,
        trailIndex: null,
        isArrow: board.arrow.row === r && board.arrow.col === c,
      });
    }
    tiles.push(row);
  }
  board.trail.forEach((cell, i) => {
    const tile = tiles[cell.row]?.[cell.col];
    if (tile && tile.trailIndex === null) {
      tile.trailIndex = i;
    }
  });
  for (const bet of Object.values(state.bets)) {
    const tile = tiles[bet.row]?.[bet.col];
    if (!tile) continue;
    if (bet.status === 'OPEN') {
      tile.open.push(bet);
    } else if (bet.settledStep !== null && board.step - bet.settledStep < FLASH_STEPS) {
      tile.justSettled = tile.justSettled === 'WON' ? 'WON' : bet.status;
    }
  }
  for (const p of Object.values(state.pending)) {
    tiles[p.row]?.[p.col]?.pending.push(p);
  }
  return tiles;
}
