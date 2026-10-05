// Black-box functional tests of the backend's public WebSocket API. No browser and no frontend: these talk to
// a running server exactly as any client would. Set API_URL to run them against a deployed environment.
import { expect, test } from '@playwright/test';
import { farBlock, GameClient, newPlayerId, type Message } from '../support/game-client';

// A bet covers 10 moves at 0.7 s; allow for that plus slack.
const SETTLEMENT_TIMEOUT_MS = 15_000;

let client: GameClient | undefined;

test.afterEach(() => client?.close());

test('a player can join, bet, see the lock-in and see the bet settle', async () => {
  client = await GameClient.connect();

  const welcome = await client.waitForType('welcome');
  expect(welcome.game.rows).toBe(8);
  expect(welcome.state.multipliersX100).toHaveLength(8);
  const startBalance = welcome.balanceCents;

  const locked = await client.placeBetOnAnOpenBlock(500);
  const bet = locked.bet;
  expect(bet.status).toBe('OPEN');
  expect(bet.lockedMultiplierX100).toBeGreaterThanOrEqual(105);
  expect(locked.balanceCents).toBe(startBalance - 500);
  expect(bet.expiresAtStep - bet.placedStep).toBe(welcome.game.betWindowSteps);

  const settled = await client.waitFor((m) => m.type === 'betSettled' && m.bet.id === bet.id, SETTLEMENT_TIMEOUT_MS);
  expect(['WON', 'LOST']).toContain(settled.bet.status);
  // Settled inside its window: a win on any move up to the last, a loss exactly when the window ends.
  expect(settled.bet.settledStep).toBeGreaterThan(bet.placedStep);
  if (settled.bet.status === 'WON') expect(settled.bet.settledStep).toBeLessThanOrEqual(bet.expiresAtStep);
  else expect(settled.bet.settledStep).toBe(bet.expiresAtStep);
  // The payout uses the multiplier locked at placement, whatever the board shows now.
  expect(settled.bet.lockedMultiplierX100).toBe(bet.lockedMultiplierX100);
  const expectedPayout = settled.bet.status === 'WON' ? Math.floor((500 * bet.lockedMultiplierX100) / 100) : 0;
  expect(settled.bet.payoutCents).toBe(expectedPayout);
  expect(settled.balanceCents).toBe(startBalance - 500 + expectedPayout);
});

test('a retried request is not charged twice', async () => {
  client = await GameClient.connect();
  const startBalance = (await client.waitForType('welcome')).balanceCents;

  const first = await client.placeBetOnAnOpenBlock(300, 'retry-1');
  client.send({
    type: 'placeBet',
    requestId: first.bet.requestId,
    row: first.bet.row,
    col: first.bet.col,
    stakeCents: 300,
  });
  const second = await client.waitForType('betLocked');

  expect(second.bet.id).toBe(first.bet.id);
  expect(second.balanceCents).toBe(startBalance - 300);
});

test('reconnecting restores an open bet, and a bet that settled while away', async () => {
  const playerId = newPlayerId();
  client = await GameClient.connect({ playerId });
  await client.waitForType('welcome');
  // Far from the arrow, so the bet is certainly still open when we reconnect a moment later.
  const locked = await client.placeBetOnAnOpenBlock(1_000, undefined, farBlock);
  client.close();

  client = await GameClient.connect({ playerId });
  const back = await client.waitForType('welcome');
  expect(back.openBets.map((b: Message) => b.id)).toEqual([locked.bet.id]);
  expect(back.balanceCents).toBe(locked.balanceCents);
  client.close();

  // Stay away until the bet's window has certainly ended, watching the game as another player.
  const watcher = await GameClient.connect();
  try {
    await watcher.waitFor((m) => m.type === 'step' && m.step >= locked.bet.expiresAtStep, SETTLEMENT_TIMEOUT_MS);
  } finally {
    watcher.close();
  }

  client = await GameClient.connect({ playerId });
  const later = await client.waitForType('welcome');
  expect(later.openBets).toEqual([]);
  const settled = later.recentBets.find((b: Message) => b.id === locked.bet.id);
  expect(['WON', 'LOST']).toContain(settled?.status);
  expect(later.balanceCents).toBe(locked.balanceCents + settled.payoutCents);
});

test('a second connection does not give a player a second bet allowance', async () => {
  // 16 bets inside a second from two sockets of one player: within each socket's share of a per-connection
  // limit (burst of 10), but over the player's shared burst. The 1-cent stake is invalid, so no money moves.
  const playerId = newPlayerId();
  client = await GameClient.connect({ playerId });
  const second = await GameClient.connect({ playerId });
  try {
    await Promise.all([client.waitForType('welcome'), second.waitForType('welcome')]);
    const send = (socket: GameClient, prefix: string) => {
      const ids = Array.from({ length: 8 }, (_, i) => `${prefix}-${i}`);
      for (const requestId of ids) socket.send({ type: 'placeBet', requestId, row: 0, col: 0, stakeCents: 1 });
      return ids;
    };
    const replies = await Promise.all([
      collectRejections(client, send(client, 'a')),
      collectRejections(second, send(second, 'b')),
    ]);
    const reasons = replies.flat();

    expect(reasons.filter((r) => r === 'RATE_LIMITED').length).toBeGreaterThanOrEqual(1);
    expect(reasons.every((r) => r === 'RATE_LIMITED' || r === 'INVALID_STAKE')).toBe(true);
  } finally {
    second.close();
  }
});

test('a connection without a valid player id is closed with a policy violation', async () => {
  client = await GameClient.connect({ playerId: 'bad' });
  const { code } = await client.closed;
  expect(code).toBe(1008);
});

test('browsers from untrusted origins are refused', async () => {
  await expect(GameClient.connect({ origin: 'https://evil.example' })).rejects.toThrow(/HTTP 403/);
});

test('browsers from allowed origins are accepted', async () => {
  client = await GameClient.connect({ origin: 'http://localhost:5173' });
  await expect(client.waitForType('welcome')).resolves.toMatchObject({ type: 'welcome' });
});

/** The rejection reason for each request id, read from the socket that sent them. */
async function collectRejections(socket: GameClient, requestIds: string[]): Promise<string[]> {
  const pending = new Set(requestIds);
  const reasons: string[] = [];
  while (pending.size) {
    const reply = await socket.waitFor((m) => m.type === 'betRejected' && pending.has(m.requestId));
    pending.delete(reply.requestId);
    reasons.push(reply.reason);
  }
  return reasons;
}
