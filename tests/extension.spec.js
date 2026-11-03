const { test, expect, chromium } = require('@playwright/test');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const extensionPath = path.resolve(__dirname, '../extension');
const fixture = fs.readFileSync(path.resolve(__dirname, '../docs/demo.html'), 'utf8').replace('<script src="../extension/src/content.js"></script>', '');
let context, page, profile;
test.beforeAll(async () => {
  profile = fs.mkdtempSync(path.join(os.tmpdir(), 'gemini-anchor-test-'));
  context = await chromium.launchPersistentContext(profile, {
    channel: 'chromium', headless: true, viewport: { width: 1440, height: 960 },
    args: [`--disable-extensions-except=${extensionPath}`, `--load-extension=${extensionPath}`]
  });
  await context.route('https://gemini.google.com/**', route => route.fulfill({ contentType: 'text/html', body: fixture }));
});
test.beforeEach(async () => {
  page = await context.newPage(); await page.goto('https://gemini.google.com/app/demo');
  await expect(page.locator('#gemini-anchor-extension')).toHaveCount(1);
  if (await page.locator('.launcher').isVisible()) await page.getByRole('button', { name: '展开对话目录', exact: true }).click();
});
test.afterEach(async () => { await page.close(); });
test.afterAll(async () => { await context?.close(); fs.rmSync(profile, { recursive: true, force: true }); });
test('installed MV3 extension indexes questions and scrolls the nested chat, not the page', async () => {
  await expect(page.locator('.entry')).toHaveCount(12);
  await page.locator('.entry').nth(9).click();
  await expect.poll(() => page.locator('user-query').nth(9).evaluate(n => n.getBoundingClientRect().top)).toBeLessThan(700);
  expect(await page.locator('.chat').evaluate(n => n.scrollTop)).toBeGreaterThan(2000);
  expect(await page.evaluate(() => window.scrollY)).toBe(0);
  await page.getByRole('button', { name: '最早提问 ↑' }).click();
  await expect.poll(() => page.locator('user-query').first().evaluate(n => n.getBoundingClientRect().top)).toBeGreaterThan(50);
});
test('search highlights matches, handles no results, and cannot execute prompt HTML', async () => {
  await page.getByRole('searchbox').fill('书店');
  await expect(page.locator('.entry')).toHaveCount(1); await expect(page.locator('.entry mark')).toHaveText('书店');
  await page.getByRole('searchbox').fill('不存在的关键词'); await expect(page.locator('.empty')).toContainText('没有找到');
  await page.evaluate(() => { const n = document.createElement('user-query'); n.textContent = '<img src=x onerror="window.pwned=true">'; document.querySelector('.chat').append(n); });
  await page.getByRole('searchbox').fill('<img'); await expect(page.locator('.entry')).toHaveCount(1);
  await expect(page.locator('.entry img')).toHaveCount(0); expect(await page.evaluate(() => window.pwned)).toBeUndefined();
});
test('streaming updates, duplicate prompts, and nested query markup stay correct', async () => {
  await page.evaluate(() => {
    const node = document.createElement('user-query'); node.innerHTML = '<div class="user-query-container"><div class="query-text"><span class="query-text-line">相同问题</span></div><button>编辑</button></div>';
    document.querySelector('.chat').append(node, node.cloneNode(true));
  });
  await expect(page.locator('.entry')).toHaveCount(14);
  await page.getByRole('searchbox').fill('相同问题'); await expect(page.locator('.entry')).toHaveCount(2);
  await page.evaluate(() => { document.querySelector('user-query:last-child .query-text-line').textContent = '修改后的问题'; });
  await expect(page.locator('.entry')).toHaveCount(1);
});
test('client-side conversation change clears old entries and filter', async () => {
  await page.getByRole('searchbox').fill('京都');
  await page.evaluate(() => { history.pushState({}, '', '/app/another'); document.querySelector('.chat').innerHTML = '<user-query><div class="query-text">新的对话</div></user-query>'; });
  await expect(page.locator('.entry')).toHaveCount(1); await expect(page.locator('.entry')).toContainText('新的对话');
  await expect(page.getByRole('searchbox')).toHaveValue('');
});
test('live Gemini screen-reader headings do not duplicate prompt text', async () => {
  await page.evaluate(() => {
    const node = document.createElement('user-query');
    node.innerHTML = '<span class="user-query-container"><user-query-content class="user-query-container"><div class="query-text"><h5 class="cdk-visually-hidden screen-reader-user-query-label"><span>You said</span> Navigation check 01.</h5><p class="query-text-line">Navigation check 01.</p><p class="query-text-line">Second line.</p><span aria-hidden="true">Decorative label</span></div><button>Copy prompt</button></user-query-content></span>';
    document.querySelector('.chat').append(node);
  });
  await expect(page.locator('.entry')).toHaveCount(13);
  await expect(page.locator('.entry').last().locator('.title')).toHaveText('Navigation check 01. Second line.');
  await expect(page.locator('.entry').last()).toHaveAttribute('title', 'Navigation check 01. Second line.');
  // The screen-reader label in Gemini must remain untouched.
  await expect(page.locator('user-query h5')).toHaveText('You said Navigation check 01.');
});
test('load earlier reveals lazy history and restores reading position', async () => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.locator('.entry').nth(8).click();
  const before = await page.locator('user-query').nth(8).evaluate(n => n.getBoundingClientRect().top);
  await page.evaluate(() => {
    const chat = document.querySelector('.chat'); let loaded = false;
    chat.addEventListener('scroll', () => {
      if (chat.scrollTop === 0 && !loaded) {
        loaded = true; setTimeout(() => { const node = document.createElement('user-query'); node.textContent = '更早的一条历史消息'; chat.prepend(node); }, 150);
      }
    });
  });
  await page.getByRole('button', { name: '加载前文' }).click();
  await expect(page.locator('.entry')).toHaveCount(13);
  await expect(page.locator('.status')).toContainText('本轮加载结束', { timeout: 12000 });
  const after = await page.locator('user-query').nth(9).evaluate(n => n.getBoundingClientRect().top);
  expect(Math.abs(after - before)).toBeLessThan(12);
});
test('loading can be stopped and is cancelled when navigating away', async () => {
  await page.getByRole('button', { name: '加载前文' }).click(); await page.getByRole('button', { name: '停止加载' }).click();
  await expect(page.locator('.status')).toContainText('已停止');
  await page.getByRole('button', { name: '加载前文' }).click();
  await page.evaluate(() => { history.pushState({}, '', '/app/new'); document.querySelector('.chat').innerHTML = ''; });
  await expect(page.getByRole('button', { name: '加载前文' })).toBeVisible();
  await expect(page.locator('.entry')).toHaveCount(0);
});
test('Escape closes, narrow viewport stays in bounds and jump closes the panel', async () => {
  await page.getByRole('searchbox').press('Escape'); await expect(page.locator('.panel')).toBeHidden();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '展开对话目录', exact: true }).click();
  const box = await page.locator('.panel').boundingBox(); expect(box.x).toBeGreaterThanOrEqual(0); expect(box.x + box.width).toBeLessThanOrEqual(390);
  await page.locator('.entry').nth(4).click(); await expect(page.locator('.panel')).toBeHidden();
});
test('dark theme and empty state render without overflow; capture a reviewable screenshot', async () => {
  await page.locator('.entry').nth(3).click(); await page.waitForTimeout(700);
  await page.screenshot({ path: 'docs/web-preview.png' });
  await page.emulateMedia({ colorScheme: 'dark' });
  expect(await page.locator('.panel').evaluate(n => getComputedStyle(n).backgroundColor)).toBe('rgb(32, 42, 36)');
  await page.evaluate(() => document.querySelector('.chat').replaceChildren());
  await expect(page.locator('.empty')).toContainText('从一段对话开始');
  await expect(page.getByRole('button', { name: '最早提问 ↑' })).toBeDisabled();
});
