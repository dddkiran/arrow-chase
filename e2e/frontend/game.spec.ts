// Full stack, nothing mocked: real browser -> frontend -> backend game engine.
import { expect, test, type Page } from '@playwright/test';
import { serverViewOf } from '../support/game-client';
import { balanceCents, clickAnOpenBlock, playerIdOf } from '../support/game-page';

test('a player sets a stake, bets on a block, sees the lock-in and then a win or a miss', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /Arrow Chase/ })).toBeVisible();
  await expect(page.getByText('connected')).toBeVisible();

  await page.getByRole('button', { name: '5.00', exact: true }).click();
  const before = await balanceCents(page);

  await clickAnOpenBlock(page);

  // Lock-in: the server confirmed and the tile shows the locked multiplier.
  await expect(page.getByTitle('Multiplier locked for your bet').first()).toBeVisible({ timeout: 3_000 });
  await expect.poll(() => balanceCents(page)).toBe(before - 500);
  await expect(page.locator('.bet.open')).toHaveCount(1);

  // Settlement: within the bet window (10 moves at 0.7 s) the server reports a win or a miss.
  await expect(page.locator('.bet.won, .bet.lost')).toHaveCount(1, { timeout: 15_000 });
  await expect(page.locator('.bet.open')).toHaveCount(0);

  // The balance shown includes the payout, and matches the server's books to the cent.
  const server = await serverViewOf(await playerIdOf(page));
  const [bet] = server.recentBets;
  expect(server.balanceCents).toBe(before - 500 + bet.payoutCents);
  await expect.poll(() => balanceCents(page)).toBe(server.balanceCents);
  await page.screenshot({ path: 'test-results/after-settlement.png', fullPage: true });
});

test('an invalid stake is explained and no block can be bet on', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('connected')).toBeVisible();
  const stake = page.getByLabel('Bet amount');
  const openTiles = page.locator('button.tile:not(:disabled)');

  for (const [typed, hint] of [
    ['abc', 'Enter an amount like 2.50'],
    ['2.505', 'Enter an amount like 2.50'],
    ['0.05', 'Minimum stake is 0.10'],
    ['100.01', 'Maximum stake is 100.00'],
  ]) {
    await stake.fill(typed);
    await expect(page.locator('#stake-hint'), typed).toHaveText(hint);
    await expect(stake).toHaveAttribute('aria-invalid', 'true');
    await expect(openTiles, typed).toHaveCount(0);
  }

  await stake.fill('2.50');
  await expect(stake).toHaveAttribute('aria-invalid', 'false');
  await expect.poll(() => openTiles.count()).toBeGreaterThan(0);
});

test('two players watching see the same arrow', async ({ browser }) => {
  const a = await (await browser.newContext()).newPage();
  const b = await (await browser.newContext()).newPage();
  await Promise.all([a.goto('/'), b.goto('/')]);
  await expect(a.getByText('connected')).toBeVisible();
  await expect(b.getByText('connected')).toBeVisible();

  const arrowOf = (p: Page) => p.locator('.arrow').evaluate((el) => (el as HTMLElement).style.transform);
  expect(await arrowOf(a)).toMatch(/^translate\(/);
  // Both receive the same server step broadcast; allow one step of skew between the two reads.
  await expect.poll(async () => (await arrowOf(a)) === (await arrowOf(b)), { timeout: 5_000 }).toBe(true);
  await expect(a.getByTitle('Players connected right now')).not.toHaveText('👥 1');
});
