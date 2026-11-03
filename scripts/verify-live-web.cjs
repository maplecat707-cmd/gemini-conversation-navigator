// Opt-in live check. Sends only generated test prompts in a fresh, unsigned-in
// browser profile. Never loads a user's browser profile or writes cookies.
const { chromium, expect } = require('@playwright/test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const rounds = Number(process.env.ANCHOR_LIVE_ROUNDS || 20);
  assert(Number.isInteger(rounds) && rounds >= 2 && rounds <= 20);
  const output = path.resolve('test-results/live-web');
  fs.mkdirSync(output, { recursive: true });
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'gemini-anchor-live-'));
  const proxyValue = process.env.https_proxy || process.env.HTTPS_PROXY;
  const proxyUrl = proxyValue ? new URL(proxyValue) : null;
  const proxy = proxyUrl ? {
    server: `${proxyUrl.protocol}//${proxyUrl.hostname}:${proxyUrl.port}`,
    ...(proxyUrl.username ? { username: decodeURIComponent(proxyUrl.username), password: decodeURIComponent(proxyUrl.password) } : {})
  } : undefined;
  const extension = path.resolve(__dirname, '../extension');
  const context = await chromium.launchPersistentContext(profile, {
    channel: 'chromium', headless: true, proxy, viewport: { width: 1440, height: 1000 },
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`]
  });
  const evidence = { startedAt: new Date().toISOString(), source: 'Live https://gemini.google.com/app; no interception or injected messages', signedIn: false, roundsRequested: rounds, roundsCompleted: 0, checks: [] };
  const page = await context.newPage();
  const progress = setInterval(() => console.log(`Live check running: ${evidence.roundsCompleted}/${rounds} replies received.`), 30000);
  try {
    console.log('Opening live Gemini with installed extension in a fresh profile.');
    await page.goto('https://gemini.google.com/app', { waitUntil: 'domcontentloaded', timeout: 60000 });
    const editor = page.getByRole('textbox', { name: 'Enter a prompt for Gemini' });
    await editor.waitFor({ state: 'visible', timeout: 60000 });
    const prompts = [];
    for (let i = 1; i <= rounds; i++) {
      const prompt = `Navigation check ${String(i).padStart(2, '0')}. Please reply with "Anchor reply ${i}" and two short sentences about organizing a long conversation. This is synthetic UI test data.`;
      prompts.push(prompt);
      console.log(`Sending generated test prompt ${i}/${rounds}.`);
      await editor.fill(prompt); await editor.press('Enter');
      await expect(page.locator('user-query')).toHaveCount(i, { timeout: 90000 });
      await expect.poll(async () => {
        const answer = page.locator('model-response').nth(i - 1);
        return await answer.count() ? (await answer.innerText()).length : 0;
      }, { timeout: 120000, message: `Wait for Gemini's actual reply ${i}` }).toBeGreaterThan(25);
      // Wait for generation to settle before sending another turn.
      let last = '', stable = 0;
      for (let tick = 0; tick < 30; tick++) {
        const text = await page.locator('model-response').nth(i - 1).innerText();
        stable = text === last ? stable + 1 : 0; last = text;
        if (stable >= 2 && !await page.locator('button[aria-label*="Stop"]').isVisible()) break;
        await page.waitForTimeout(1000);
      }
      evidence.roundsCompleted = i;
      console.log(`Received live reply ${i}; directory count ${await page.locator('#gemini-anchor-extension .entry').count()}.`);
    }
    const sidebar = page.locator('#gemini-anchor-extension');
    await sidebar.getByRole('button', { name: '展开对话目录', exact: true }).click();
    await expect(sidebar.locator('.entry')).toHaveCount(rounds);
    const titles = await sidebar.locator('.entry').evaluateAll(nodes => nodes.map(n => n.title));
    assert.deepEqual(titles, prompts);
    evidence.checks.push('Every generated prompt appears exactly once, in order, without hidden accessibility copies.');
    await sidebar.getByRole('searchbox').fill('Navigation check 02.');
    await expect(sidebar.locator('.entry')).toHaveCount(1);
    await sidebar.locator('.entry').click();
    await expect.poll(() => page.locator('user-query').nth(1).evaluate(n => {
      const r = n.getBoundingClientRect(); return r.top >= 0 && r.top < innerHeight * .8 && r.bottom > 80;
    }), { timeout: 15000 }).toBe(true);
    evidence.checks.push('Search from the end and click brings the second live prompt into the viewport.');
    await sidebar.getByRole('searchbox').fill('');
    await sidebar.getByRole('button', { name: '最新 ↓' }).click();
    await expect.poll(() => page.locator('user-query').last().evaluate(n => n.getBoundingClientRect().top), { timeout: 15000 }).toBeLessThan(800);
    await sidebar.getByRole('button', { name: '最早提问 ↑' }).click();
    await expect.poll(() => page.locator('user-query').first().evaluate(n => n.getBoundingClientRect().bottom), { timeout: 15000 }).toBeGreaterThan(80);
    evidence.checks.push('Latest and earliest controls scroll real conversation nodes into view.');
    await page.screenshot({ path: path.join(output, 'live-web.png') });
    evidence.passed = true;
  } catch (error) {
    evidence.passed = false; evidence.error = error.message;
    await page.screenshot({ path: path.join(output, 'live-web-failure.png') }).catch(() => {});
    console.error(error.message); process.exitCode = 1;
  } finally {
    clearInterval(progress);
    evidence.finishedAt = new Date().toISOString();
    fs.writeFileSync(path.join(output, 'evidence.json'), JSON.stringify(evidence, null, 2));
    await context.close(); fs.rmSync(profile, { recursive: true, force: true });
    console.log(JSON.stringify(evidence));
  }
})();
