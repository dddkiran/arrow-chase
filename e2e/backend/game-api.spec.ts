// Black-box functional tests of the backend's public WebSocket API. No browser and no frontend: these talk to
// a running server exactly as any client would. Set API_URL to run them against a deployed environment.
import { expect, test } from '@playwright/test';
import { GameClient, newPlayerId } from '../support/game-client';

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

  const settled = await client.waitFor((m) => m.type === 'betSettled' && m.bet.id === bet.id, SETTLEMENT_TIMEOUT_MS);
  expect(['WON', 'LOST']).toContain(settled.bet.status);
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

test('reconnecting restores balance and open bets', async () => {
  const playerId = newPlayerId();
  client = await GameClient.connect({ playerId });
  await client.waitForType('welcome');
  const locked = await client.placeBetOnAnOpenBlock(1_000);
  client.close();

  client = await GameClient.connect({ playerId });
  const welcome = await client.waitForType('welcome');

  const known = [...welcome.openBets, ...welcome.recentBets].map((b: { id: string }) => b.id);
  expect(known).toContain(locked.bet.id);
  const maxPayout = Math.floor((1_000 * locked.bet.lockedMultiplierX100) / 100);
  expect([locked.balanceCents, locked.balanceCents + maxPayout]).toContain(welcome.balanceCents);
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
