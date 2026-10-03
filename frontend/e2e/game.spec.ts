import { expect, test, type Page } from '@playwright/test';

const balance = async (page: Page) =>
  Number((await page.getByLabel('Balance').innerText()).replace(/,/g, ''));

/**
 * Clicks an open block with a mid-range multiplier. Done inside the page in one go because the board re-quotes
 * every step, so an accessible name read now may be stale a moment later.
 */
const clickAnOpenBlock = (page: Page) =>
  page.evaluate(() => {
    const tiles = [...document.querySelectorAll<HTMLButtonElement>('button.tile:not(:disabled)')];
    const pick = tiles.find((b) => {
      const m = parseFloat((b.getAttribute('aria-label') ?? '').split(', ')[1]);
      return m >= 1.5 && m <= 6;
    });
    if (!pick) throw new Error('no open block in range');
    pick.click();
    return pick.getAttribute('aria-label');
  });

test('a player sets a stake, bets on a block, sees the lock-in and then a win or a miss', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /Arrow Chase/ })).toBeVisible();
  await expect(page.getByText('connected')).toBeVisible();

  await page.getByRole('button', { name: '5.00', exact: true }).click();
  const before = await balance(page);

  await clickAnOpenBlock(page);

  // Lock-in: the server confirmed and the tile shows the locked multiplier.
  await expect(page.getByTitle('Multiplier locked for your bet').first()).toBeVisible({ timeout: 3_000 });
  await expect.poll(() => balance(page)).toBeCloseTo(before - 5, 2);
  await expect(page.locator('.bet.open')).toHaveCount(1);

  // Settlement: within the bet window (10 moves at 0.7 s) the server reports a win or a miss.
  await expect(page.locator('.bet.won, .bet.lost')).toHaveCount(1, { timeout: 15_000 });
  await expect(page.locator('.bet.open')).toHaveCount(0);
  await page.screenshot({ path: 'test-results/after-settlement.png', fullPage: true });
});

test('two players watching see the same arrow', async ({ browser }) => {
  const a = await (await browser.newContext()).newPage();
  const b = await (await browser.newContext()).newPage();
  await Promise.all([a.goto('/'), b.goto('/')]);
  await expect(a.getByText('connected')).toBeVisible();
  await expect(b.getByText('connected')).toBeVisible();

  const arrowOf = (p: Page) => p.locator('.arrow').evaluate((el) => (el as HTMLElement).style.transform);
  // Both receive the same server step broadcast; allow one step of skew between the two reads.
  await expect.poll(async () => (await arrowOf(a)) === (await arrowOf(b)), { timeout: 5_000 }).toBe(true);
  await expect(a.getByTitle('Players connected right now')).not.toHaveText('👥 1');
});
