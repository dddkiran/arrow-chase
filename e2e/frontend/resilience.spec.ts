// What a player sees when the network misbehaves or the page reloads. Still nothing mocked: the game socket goes
// through a Playwright pass-through proxy to the real backend, which lets a test cut the connection, or lose one
// reply, at exactly the moment it wants.
import { expect, test, type Page, type WebSocketRoute } from '@playwright/test';
import { serverViewOf } from '../support/game-client';
import { balanceCents, clickAnOpenBlock, confirmedBets, playerIdOf } from '../support/game-page';

/** What the proxy saw the page send, and a handle on the current connection so a test can cut it. */
interface Proxy {
  sent: Array<{ type: string; [key: string]: any }>;
  current: () => WebSocketRoute;
  /** Cut the connection instead of delivering the next server message that matches. */
  dropBefore: (match: (message: any) => boolean) => void;
}

async function proxyGameSocket(page: Page): Promise<Proxy> {
  const sent: Proxy['sent'] = [];
  let current: WebSocketRoute | undefined;
  let dropMatch: ((message: any) => boolean) | undefined;
  await page.routeWebSocket(/\/ws\?/, (ws) => {
    current = ws;
    const server = ws.connectToServer();
    ws.onMessage((frame) => {
      sent.push(JSON.parse(String(frame)));
      server.send(frame);
    });
    server.onMessage((frame) => {
      if (dropMatch?.(JSON.parse(String(frame)))) {
        dropMatch = undefined;
        ws.close({ code: 4000, reason: 'connection lost' });
        return;
      }
      ws.send(frame);
    });
  });
  return {
    sent,
    current: () => current!,
    dropBefore: (match) => {
      dropMatch = match;
    },
  };
}

/** The page shows exactly what the server holds for this player: same balance, same bets. */
async function expectInSyncWithServer(page: Page) {
  const playerId = await playerIdOf(page);
  await expect
    .poll(async () => {
      const server = await serverViewOf(playerId);
      const bets = server.openBets.length + Math.min(server.recentBets.length, 10);
      return [await balanceCents(page), await confirmedBets(page).count()].join() === [server.balanceCents, bets].join();
    }, { timeout: 5_000 })
    .toBe(true);
}

test('the game reconnects by itself after the connection drops and carries on', async ({ page }) => {
  const proxy = await proxyGameSocket(page);
  await page.goto('/');
  await expect(page.getByText('connected')).toBeVisible();
  await clickAnOpenBlock(page);
  await expect(confirmedBets(page)).toHaveCount(1);

  await proxy.current().close({ code: 4000, reason: 'network blip' });
  await expect(page.getByText('reconnecting…')).toBeVisible();
  await expect(page.getByText('connected')).toBeVisible({ timeout: 5_000 });

  // The bet placed before the drop is still there, and the balance matches the server.
  await expect(confirmedBets(page)).toHaveCount(1);
  await expectInSyncWithServer(page);

  // And the player can keep playing.
  await clickAnOpenBlock(page);
  await expect(confirmedBets(page)).toHaveCount(2);
  await expectInSyncWithServer(page);
});

test('a bet whose confirmation was lost is re-sent after reconnecting and charged once', async ({ page }) => {
  const proxy = await proxyGameSocket(page);
  await page.goto('/');
  await expect(page.getByText('connected')).toBeVisible();
  const before = await balanceCents(page);

  // The server accepts the bet, but the connection dies before the page hears about it.
  proxy.dropBefore((m) => m.type === 'betLocked');
  await clickAnOpenBlock(page);
  await expect(page.getByText('reconnecting…')).toBeVisible();
  await expect(page.getByText('connected')).toBeVisible({ timeout: 5_000 });

  // After reconnecting the page sent the same request again, and the server treated it as the same bet.
  await expect(page.locator('.bet.pending')).toHaveCount(0);
  await expect(confirmedBets(page)).toHaveCount(1);
  const placed = proxy.sent.filter((m) => m.type === 'placeBet');
  expect(placed).toHaveLength(2);
  expect(placed[1].requestId).toBe(placed[0].requestId);

  const server = await serverViewOf(await playerIdOf(page));
  const bets = [...server.openBets, ...server.recentBets];
  expect(bets).toHaveLength(1);
  expect(server.balanceCents).toBe(before - bets[0].stakeCents + bets[0].payoutCents);
  await expectInSyncWithServer(page);
});

test('reloading the page keeps the same player, balance and bets', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('connected')).toBeVisible();
  await clickAnOpenBlock(page);
  await expect(confirmedBets(page)).toHaveCount(1);
  const name = await page.locator('.who').innerText();
  const playerId = await playerIdOf(page);

  await page.reload();
  await expect(page.getByText('connected')).toBeVisible();

  expect(await playerIdOf(page)).toBe(playerId);
  await expect(page.locator('.who')).toHaveText(name);
  await expect(confirmedBets(page)).toHaveCount(1);
  await expectInSyncWithServer(page);
});
