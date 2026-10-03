import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';

/** Where the backend under test lives. Point at a deployed service with API_URL=https://... */
export const API_URL = process.env.API_URL ?? 'http://localhost:8080';

export type Message = { type: string; [key: string]: any };

export interface ConnectOptions {
  playerId?: string;
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
    const query = new URLSearchParams({ playerId: options.playerId ?? newPlayerId(), name: options.name ?? 'E2E' });
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

  close(): void {
    this.socket.close();
  }

  /**
   * Bets on a block with a mid-range multiplier. The board re-quotes every step, so a block can close between
   * reading it and betting; that rejection is legitimate and we simply try again on the next step.
   */
  async placeBetOnAnOpenBlock(stakeCents: number, requestId = randomUUID()): Promise<Message> {
    for (let attempt = 0; attempt < 10; attempt++) {
      const step = await this.waitForType('step');
      const [row, col] = firstOpenBlock(step.multipliersX100);
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

function firstOpenBlock(grid: number[][]): [number, number] {
  for (let r = 0; r < grid.length; r++) {
    for (let c = 0; c < grid[r].length; c++) {
      if (grid[r][c] >= 150 && grid[r][c] <= 600) return [r, c];
    }
  }
  throw new Error('no open block in range on this board');
}
