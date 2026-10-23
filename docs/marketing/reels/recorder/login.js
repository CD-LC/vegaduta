const P = require('./paths');
// Signs the recording session in through the real login form (password), like a user would.
const fs = require('fs');
const { humanClick, humanType } = require('./record');
async function login(page, { record = false, rec } = {}) {
  const pw = P.password();
  await page.goto('/login', { waitUntil: 'load' });
  await page.waitForTimeout(800);
  if (record && rec) { await rec.start(page); await page.waitForTimeout(600); }
  const sw = page.getByText('Use a password instead');
  if (await sw.count()) await humanClick(page, sw, { delay: 400, settle: 600 });
  await humanType(page, '#local-email', 'owner@vegaduta.local', { cps: record ? 26 : 200 });
  await humanType(page, '#local-password', pw, { cps: record ? 30 : 200 });
  await humanClick(page, page.locator('button[type="submit"]').first(), { delay: record ? 500 : 50, settle: 300 });
  await page.waitForURL(u => !u.toString().includes('/login'), { timeout: 30000 });
  await page.waitForTimeout(1200);
  // The first-run "launch path" dialog: mark the tour as skipped for this user, as the
  // dialog's own Skip button does, so later pages do not show it again.
  await page.evaluate(() => {
    try {
      const keys = Object.keys(localStorage);
      let sub = null;
      for (const k of keys) { const v = localStorage.getItem(k) || ''; const m = v.match(/"sub":"([0-9a-f-]{36})"/); if (m) { sub = m[1]; break; } }
      const tok = keys.map(k => localStorage.getItem(k)).find(v => v && v.split('.').length === 3 && v.startsWith('eyJ'));
      if (!sub && tok) { try { sub = JSON.parse(atob(tok.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sub; } catch (_) {} }
      localStorage.setItem('vd_tour_status', 'skipped');
      if (sub) localStorage.setItem('vd_tour_status:' + sub, 'skipped');
    } catch (_) {}
  });
  const skip = page.getByRole('button', { name: /Skip for now/ });
  await skip.first().waitFor({ state: 'visible', timeout: 4000 }).catch(() => {});
  if (await skip.count()) { await skip.first().click().catch(() => {}); await page.waitForTimeout(500); }
}
module.exports = { login };
async function dismissDialogs(page) {
  for (const name of [/Got it/, /Skip for now/]) {
    const b = page.getByRole('button', { name });
    await b.first().waitFor({ state: 'visible', timeout: 2500 }).catch(() => {});
    if (await b.count()) { await b.first().click().catch(() => {}); await page.waitForTimeout(500); }
  }
}
module.exports.dismissDialogs = dismissDialogs;
