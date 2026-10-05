import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';

/** Where the backend under test lives. Point at a deployed service with API_URL=https://... */
export const API_URL = process.env.API_URL ?? 'http://localhost:8080';

export type Message = { type: string; [key: string]: any };

export interface ConnectOptions {
  /** Defaults to a fresh id; `null` leaves it out of the URL. */
  playerId?: string | null;
  name?: string;
  /** Browser Origin header to present. Omit to connect like a non-browser client. */
  origin?: string;
}

/** A unique, valid player id per test, so tests never share state on a long-running server. */
export const newPlayerId = () => `e2e-${randomUUID()}`;

/**
 * Minimal black-box game client: speaks the public WebSocket protocol only. Incoming messages are queued;
 * {@link waitFor} consumes them in order until one matches, like a player reading their screen.
 */
export class GameClient {
  private readonly inbox: Message[] = [];
  private notify: (() => void) | null = null;
  readonly closed: Promise<{ code: number; reason: string }>;

  private constructor(private readonly socket: WebSocket) {
    socket.on('message', (data) => {
      this.inbox.push(JSON.parse(data.toString()) as Message);
      this.notify?.();
    });
    this.closed = new Promise((resolve) =>
      socket.on('close', (code, reason) => resolve({ code, reason: reason.toString() })),
    );
  }

  static connect(options: ConnectOptions = {}): Promise<GameClient> {
    const query = new URLSearchParams({ name: options.name ?? 'E2E' });
    if (options.playerId !== null) query.set('playerId', options.playerId ?? newPlayerId());
    const url = `${API_URL.replace(/^http/, 'ws')}/ws?${query}`;
    const socket = new WebSocket(url, options.origin ? { origin: options.origin } : {});
    return new Promise((resolve, reject) => {
      socket.once('open', () => resolve(new GameClient(socket)));
      socket.once('unexpected-response', (_req, res) => reject(new Error(`handshake rejected: HTTP ${res.statusCode}`)));
      socket.once('error', reject);
    });
  }

  send(message: Message): void {
    this.socket.send(JSON.stringify(message));
  }

  /** Sends a frame exactly as given, for malformed-input tests. */
  sendRaw(text: string): void {
    this.socket.send(text);
  }

  /**
   * Resolves with the first queued or future message that matches. `match` sees every message it skips over, so a
   * test can also use it to observe messages on the way.
   */
  async waitFor(match: (m: Message) => boolean, timeoutMs = 5_000): Promise<Message> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      while (this.inbox.length) {
        const next = this.inbox.shift()!;
        if (match(next)) return next;
      }
      const remaining = deadline - Date.now();
      if (remaining <= 0) throw new Error(`timed out after ${timeoutMs} ms waiting for a matching message`);
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, remaining);
        this.notify = () => {
          clearTimeout(timer);
          resolve();
        };
      });
      this.notify = null;
    }
  }

  waitForType(type: string, timeoutMs?: number): Promise<Message> {
    return this.waitFor((m) => m.type === type, timeoutMs);
  }

  /** Consumes the next `count` step broadcasts and returns the last. */
  async waitForSteps(count: number): Promise<Message> {
    let step: Message | undefined;
    for (let i = 0; i < count; i++) step = await this.waitForType('step', 3_000);
    return step!;
  }

  close(): void {
    this.socket.close();
  }

  /**
   * Bets on a block chosen by `pick` (by default one with a mid-range multiplier). The board re-quotes every step,
   * so a block can close between reading it and betting; that rejection is legitimate and we simply try again on
   * the next step.
   */
  async placeBetOnAnOpenBlock(
    stakeCents: number,
    requestId = randomUUID(),
    pick: BlockPicker = midRangeBlock,
  ): Promise<Message> {
    for (let attempt = 0; attempt < 10; attempt++) {
      const block = pick(await this.waitForType('step'));
      if (!block) continue;
      const [row, col] = block;
      const id = attempt === 0 ? requestId : `${requestId}-${attempt}`;
      this.send({ type: 'placeBet', requestId: id, row, col, stakeCents });
      const reply = await this.waitFor(
        (m) =>
          (m.type === 'betLocked' && m.bet.requestId === id) || (m.type === 'betRejected' && m.requestId === id),
      );
      if (reply.type === 'betLocked') return reply;
      if (reply.reason !== 'CELL_CLOSED') throw new Error(`bet rejected: ${reply.reason}`);
    }
    throw new Error('could not get a bet accepted');
  }
}

/** Chooses a block to bet on from a step broadcast, or null to wait for the next step. */
export type BlockPicker = (step: Message) => [number, number] | null;

/** The first open block quoting between 1.5x and 6x. */
export const midRangeBlock: BlockPicker = (step) => {
  const grid: number[][] = step.multipliersX100;
  for (let r = 0; r < grid.length; r++) {
    for (let c = 0; c < grid[r].length; c++) {
      if (grid[r][c] >= 150 && grid[r][c] <= 600) return [r, c];
    }
  }
  throw new Error('no open block in range on this board');
};

/**
 * The arrow moves one block per step and a bet only wins when the arrow enters its block, so a bet on a block at
 * least this many moves away is guaranteed to stay open for that many steps.
 */
export const SAFE_DISTANCE = 4;

/** The open block farthest from the arrow, if it is at least {@link SAFE_DISTANCE} moves away. */
export const farBlock: BlockPicker = (step) => {
  const grid: number[][] = step.multipliersX100;
  let best: [number, number] | null = null;
  let bestDistance = SAFE_DISTANCE - 1;
  for (let r = 0; r < grid.length; r++) {
    for (let c = 0; c < grid[r].length; c++) {
      const distance = Math.abs(r - step.arrow.row) + Math.abs(c - step.arrow.col);
      if (grid[r][c] > 0 && distance > bestDistance) {
        best = [r, c];
        bestDistance = distance;
      }
    }
  }
  return best;
};

/** A block that is closed on this step, if any. */
export const closedBlock: BlockPicker = (step) => {
  const grid: number[][] = step.multipliersX100;
  for (let r = 0; r < grid.length; r++) {
    for (let c = 0; c < grid[r].length; c++) {
      if (grid[r][c] === 0) return [r, c];
    }
  }
  return null;
};

/** The server's current view of a player, read from a fresh connection's welcome snapshot. */
export async function serverViewOf(playerId: string): Promise<Message> {
  const client = await GameClient.connect({ playerId });
  try {
    return await client.waitForType('welcome');
  } finally {
    client.close();
  }
}
