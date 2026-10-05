import type { Page } from '@playwright/test';

/** The balance shown in the header, in cents. */
export const balanceCents = async (page: Page) =>
  Math.round(Number((await page.getByLabel('Balance').innerText()).replace(/,/g, '')) * 100);

/** The player id the page keeps in localStorage, so a test can ask the server about the same player. */
export const playerIdOf = (page: Page) =>
  page.evaluate(() => localStorage.getItem('arrow-chase.playerId') ?? '');

/** Every bet the page lists that the server has confirmed: open, won or lost. */
export const confirmedBets = (page: Page) => page.locator('.bet.open, .bet.won, .bet.lost');

/**
 * Clicks an open block with a mid-range multiplier. Done inside the page in one go because the board re-quotes
 * every step, so an accessible name read now may be stale a moment later.
 */
export const clickAnOpenBlock = (page: Page) =>
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
