// Mirrors backend com.arrowchase.ws.Protocol. Money is integer cents, multipliers are integer x100.

export type Direction = 'UP' | 'RIGHT' | 'DOWN' | 'LEFT';
export type BetStatus = 'OPEN' | 'WON' | 'LOST';

export interface GameInfo {
  rows: number;
  cols: number;
  stepMillis: number;
  betWindowSteps: number;
  minStakeCents: number;
  maxStakeCents: number;
}

export interface Arrow {
  row: number;
  col: number;
  direction: Direction;
}

export interface CellRef {
  row: number;
  col: number;
}

export interface Win {
  name: string;
  row: number;
  col: number;
  multiplierX100: number;
  payoutCents: number;
}

export interface Bet {
  id: string;
  requestId: string;
  row: number;
  col: number;
  stakeCents: number;
  lockedMultiplierX100: number;
  placedStep: number;
  expiresAtStep: number;
  status: BetStatus;
  payoutCents: number;
  settledStep: number | null;
}

export interface StepMessage {
  type: 'step';
  step: number;
  arrow: Arrow;
  trail: CellRef[];
  /** 0 = closed for betting */
  multipliersX100: number[][];
  playersOnline: number;
  wins: Win[];
  serverTimeMillis: number;
}

export interface WelcomeMessage {
  type: 'welcome';
  playerId: string;
  name: string;
  balanceCents: number;
  game: GameInfo;
  state: StepMessage;
  openBets: Bet[];
  recentBets: Bet[];
}

export interface BetLockedMessage {
  type: 'betLocked';
  bet: Bet;
  balanceCents: number;
}

export interface BetRejectedMessage {
  type: 'betRejected';
  requestId: string;
  reason: RejectReason;
  balanceCents: number;
}

export interface BetSettledMessage {
  type: 'betSettled';
  bet: Bet;
  balanceCents: number;
}

export interface ErrorMessage {
  type: 'error';
  message: string;
}

export type RejectReason =
  | 'UNKNOWN_PLAYER'
  | 'INVALID_CELL'
  | 'CELL_CLOSED'
  | 'INVALID_STAKE'
  | 'INSUFFICIENT_FUNDS'
  | 'TOO_MANY_OPEN_BETS';

export type ServerMessage =
  | WelcomeMessage
  | StepMessage
  | BetLockedMessage
  | BetRejectedMessage
  | BetSettledMessage
  | ErrorMessage;

export interface PlaceBetCommand {
  type: 'placeBet';
  requestId: string;
  row: number;
  col: number;
  stakeCents: number;
}
