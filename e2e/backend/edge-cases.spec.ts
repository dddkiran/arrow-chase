// Black-box edge cases of the public WebSocket API: every way a request can be refused, malformed input, identity
// checks, and retries across connections. The engine's rules are unit-tested; these check that each one reaches a
// real client correctly over the wire.
import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { closedBlock, farBlock, GameClient, newPlayerId, serverViewOf, type Message } from '../support/game-client';

/** game.max-open-bets-per-player in application.yml (not part of the welcome's game info). */
const MAX_OPEN_BETS = 20;

let client: GameClient | undefined;

test.afterEach(() => client?.close());

test('each simple rejection reaches the client with its reason and charges nothing', async () => {
  client = await GameClient.connect();
  const welcome = await client.waitForType('welcome');
  const { minStakeCents, maxStakeCents, rows, cols } = welcome.game;
  const start = welcome.balanceCents;

  // The stake is checked before whether the block is open, so any block on the grid will do for the stake cases.
  const cases = [
    { row: rows, col: 0, stakeCents: 100, reason: 'INVALID_CELL' },
    { row: 0, col: cols, stakeCents: 100, reason: 'INVALID_CELL' },
    { row: 0, col: -1, stakeCents: 100, reason: 'INVALID_CELL' },
    { row: 0, col: 0, stakeCents: minStakeCents - 1, reason: 'INVALID_STAKE' },
    { row: 0, col: 0, stakeCents: maxStakeCents + 1, reason: 'INVALID_STAKE' },
    { row: 0, col: 0, stakeCents: 0, reason: 'INVALID_STAKE' },
  ];
  for (const { reason, ...bet } of cases) {
    const requestId = randomUUID();
    client.send({ type: 'placeBet', requestId, ...bet });
    const reply = await client.waitFor((m) => m.requestId === requestId || m.bet?.requestId === requestId);
    expect(reply, JSON.stringify(bet)).toMatchObject({ type: 'betRejected', requestId, reason, balanceCents: start });
  }
});

test('a bet on a closed block is refused as CELL_CLOSED', async () => {
  client = await GameClient.connect();
  let balance = (await client.waitForType('welcome')).balanceCents;
  // A closed block can reopen on the next step, so a bet sent across a step boundary may be accepted. Rare, and
  // legitimate: just try again.
  for (let attempt = 0; attempt < 5; attempt++) {
    const block = closedBlock(await client.waitForType('step'));
    if (!block) continue;
    const requestId = randomUUID();
    client.send({ type: 'placeBet', requestId, row: block[0], col: block[1], stakeCents: 100 });
    const reply = await client.waitFor((m) => m.requestId === requestId || m.bet?.requestId === requestId);
    if (reply.type === 'betLocked') {
      balance = reply.balanceCents;
      continue;
    }
    expect(reply).toMatchObject({ reason: 'CELL_CLOSED', balanceCents: balance });
    return;
  }
  throw new Error('never managed to bet on a closed block');
});

test(`a player cannot have more than ${MAX_OPEN_BETS} open bets`, async () => {
  client = await GameClient.connect();
  const welcome = await client.waitForType('welcome');
  const { refusal, openBets, balance } = await betUntilRefused(client, welcome, welcome.game.minStakeCents, 'TOO_MANY_OPEN_BETS');
  expect(openBets).toBe(MAX_OPEN_BETS);
  expect(refusal.balanceCents).toBe(balance);
});

test('a player cannot stake more than their balance', async () => {
  client = await GameClient.connect();
  const welcome = await client.waitForType('welcome');
  const stake = welcome.game.maxStakeCents;
  const { refusal, balance } = await betUntilRefused(client, welcome, stake, 'INSUFFICIENT_FUNDS');
  expect(refusal.balanceCents).toBe(balance);
  expect(refusal.balanceCents).toBeLessThan(stake);
});

test('malformed messages get an error and leave the connection usable', async () => {
  client = await GameClient.connect();
  const start = (await client.waitForType('welcome')).balanceCents;

  const frames = [
    'not json',
    '{}',
    JSON.stringify({ type: 'cashOut' }),
    JSON.stringify({ type: 'placeBet', row: 0, col: 0, stakeCents: 100 }),
    JSON.stringify({ type: 'placeBet', requestId: 'x'.repeat(65), row: 0, col: 0, stakeCents: 100 }),
    JSON.stringify({ type: 'placeBet', requestId: 'has space', row: 0, col: 0, stakeCents: 100 }),
    JSON.stringify({ type: 'placeBet', requestId: 'r-1', row: 'top', col: 0, stakeCents: 100 }),
  ];
  for (const frame of frames) {
    client.sendRaw(frame);
    const reply = await client.waitFor((m) => m.type !== 'step');
    expect(reply.type, frame).toBe('error');
  }

  const locked = await client.placeBetOnAnOpenBlock(100);
  expect(locked.balanceCents).toBe(start - 100);
});

test.describe('player ids', () => {
  const randomId = (length: number) => (randomUUID() + randomUUID()).replace(/-/g, '').repeat(2).slice(0, length);

  for (const [label, playerId] of [
    ['missing', null],
    ['7 characters', randomId(7)],
    ['65 characters', randomId(65)],
    ['with a character outside A-Z a-z 0-9 -', `${randomId(10)}_x`],
  ] as const) {
    test(`a player id that is ${label} is closed with a policy violation`, async () => {
      client = await GameClient.connect({ playerId });
      expect((await client.closed).code).toBe(1008);
    });
  }

  for (const length of [8, 64]) {
    test(`a player id of ${length} characters is accepted`, async () => {
      const playerId = randomId(length);
      client = await GameClient.connect({ playerId });
      expect(await client.waitForType('welcome')).toMatchObject({ playerId });
    });
  }
});

test.describe('display names', () => {
  for (const [given, shown] of [
    ['Ana Lima', 'Ana Lima'],
    ['José', 'José'],
    ['<b>x</b>', 'bxb'],
    ['a'.repeat(30), 'a'.repeat(20)],
    ['', 'Player'],
    ['!!!', 'Player'],
  ]) {
    test(`"${given.slice(0, 12)}" is shown as "${shown.slice(0, 12)}"`, async () => {
      client = await GameClient.connect({ name: given });
      expect((await client.waitForType('welcome')).name).toBe(shown);
    });
  }
});

test("every one of a player's connections sees their bets lock and settle", async () => {
  const playerId = newPlayerId();
  client = await GameClient.connect({ playerId });
  const other = await GameClient.connect({ playerId });
  try {
    await Promise.all([client.waitForType('welcome'), other.waitForType('welcome')]);
    const locked = await client.placeBetOnAnOpenBlock(200);

    const seen = await other.waitFor((m) => m.type === 'betLocked');
    expect(seen).toMatchObject({ bet: { id: locked.bet.id }, balanceCents: locked.balanceCents });
    const [a, b] = await Promise.all(
      [client, other].map((c) => c.waitFor((m) => m.type === 'betSettled' && m.bet.id === locked.bet.id, 15_000)),
    );
    expect(b).toEqual(a);
  } finally {
    other.close();
  }
});

test('a request retried on a new connection returns the original bet with the balance as it is now', async () => {
  // This is what the browser does after a dropped connection: re-send an unconfirmed bet with the same requestId.
  const playerId = newPlayerId();
  client = await GameClient.connect({ playerId });
  await client.waitForType('welcome');
  const first = await client.placeBetOnAnOpenBlock(300, undefined, farBlock);
  client.close();

  client = await GameClient.connect({ playerId });
  await client.waitForType('welcome');
  // Something else changes the balance before the retry arrives.
  const another = await client.placeBetOnAnOpenBlock(200, undefined, farBlock);

  // Same requestId, different block and stake: the original bet wins, nothing new is charged.
  client.send({ type: 'placeBet', requestId: first.bet.requestId, row: 0, col: 0, stakeCents: 5_000 });
  const retried = await client.waitFor((m) => m.type === 'betLocked' && m.bet.requestId === first.bet.requestId);
  expect(retried.bet).toMatchObject({ id: first.bet.id, stakeCents: 300, row: first.bet.row, col: first.bet.col });
  expect(retried.balanceCents).toBe(another.balanceCents);

  const view = await serverViewOf(playerId);
  expect(view.balanceCents).toBe(another.balanceCents);
  expect([...view.openBets, ...view.recentBets]).toHaveLength(2);
});

test('a rate-limited request can be sent again once the client slows down', async () => {
  client = await GameClient.connect();
  await client.waitForType('welcome');
  // A 1-cent stake is invalid, so the requests that get past the limiter move no money.
  const ids = Array.from({ length: 15 }, () => randomUUID());
  for (const requestId of ids) client.send({ type: 'placeBet', requestId, row: 0, col: 0, stakeCents: 1 });
  const replies: Message[] = [];
  for (const _ of ids) replies.push(await client.waitForType('betRejected'));
  const limited = replies.find((r) => r.reason === 'RATE_LIMITED');
  expect(limited, 'expected the burst to be rate-limited').toBeDefined();
  expect(limited!.balanceCents).toBeNull();

  await client.waitForSteps(2); // well over the refill time for one bet
  client.send({ type: 'placeBet', requestId: limited!.requestId, row: 0, col: 0, stakeCents: 1 });
  const again = await client.waitFor((m) => m.type === 'betRejected' && m.requestId === limited!.requestId);
  expect(again.reason).toBe('INVALID_STAKE');
});

test('step broadcasts follow the rules of the board', async () => {
  client = await GameClient.connect();
  const welcome = await client.waitForType('welcome');
  const { rows, cols } = welcome.game;
  let previous = welcome.state;
  for (let i = 0; i < 6; i++) {
    const step = await client.waitForType('step', 3_000);
    expect(step.step).toBe(previous.step + 1);
    expect(Math.abs(step.arrow.row - previous.arrow.row) + Math.abs(step.arrow.col - previous.arrow.col)).toBe(1);
    expect(step.arrow.row).toBeGreaterThanOrEqual(0);
    expect(step.arrow.row).toBeLessThan(rows);
    expect(step.arrow.col).toBeGreaterThanOrEqual(0);
    expect(step.arrow.col).toBeLessThan(cols);
    expect(step.trail[0]).toEqual({ row: step.arrow.row, col: step.arrow.col });
    expect(step.multipliersX100).toHaveLength(rows);
    for (const quote of step.multipliersX100.flat()) {
      expect(quote === 0 || (quote >= 105 && quote <= 5_000), `quote ${quote}`).toBe(true);
    }
    expect(step.playersOnline).toBeGreaterThanOrEqual(1);
    expect(step.serverTimeMillis).toBeGreaterThan(previous.serverTimeMillis);
    previous = step;
  }
});

/**
 * Keeps placing bets of `stakeCents` on far blocks, one burst per step, until the server refuses one for
 * `reason`. Tracks the player's open bets and balance from the messages on the way, so the caller can check the
 * refusal against the state at the moment it was made. Bets that settle in the meantime (rare, the blocks are far)
 * just free room for more, and rate-limited requests are simply retried on the next step.
 */
async function betUntilRefused(socket: GameClient, welcome: Message, stakeCents: number, reason: string) {
  const sent = new Set<string>();
  const open = new Set<string>();
  let balance: number = welcome.balanceCents;
  let found: { refusal: Message; openBets: number; balance: number } | undefined;
  const observe = (m: Message) => {
    if (m.type === 'betLocked' && sent.has(m.bet.requestId)) {
      open.add(m.bet.id);
      balance = m.balanceCents;
    } else if (m.type === 'betSettled') {
      open.delete(m.bet.id);
      balance = m.balanceCents;
    } else if (m.type === 'betRejected' && m.reason === reason && sent.has(m.requestId)) {
      found ??= { refusal: m, openBets: open.size, balance };
    }
    return m.type === 'step';
  };

  const deadline = Date.now() + 20_000;
  while (!found && Date.now() < deadline) {
    const block = farBlock(await socket.waitFor(observe, 3_000));
    if (!block) continue;
    for (let i = 0; i < 10; i++) {
      const requestId = randomUUID();
      sent.add(requestId);
      socket.send({ type: 'placeBet', requestId, row: block[0], col: block[1], stakeCents });
    }
  }
  if (!found) throw new Error(`never refused with ${reason}`);
  return found;
}
