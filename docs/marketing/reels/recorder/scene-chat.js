const { newSession, humanClick, humanType } = require('./record');
const { login, dismissDialogs } = require('./login');
(async () => {
  const [agentId, outDir, question, waitMs] = process.argv.slice(2);
  const { browser, page, rec } = await newSession({ outDir });
  process.on('exit', () => {});
  page.on('close', () => {});
  global.__page = page;
  await login(page);
  await page.goto('/agents?agentId=' + agentId, { waitUntil: 'load' }); await page.waitForTimeout(2000); await dismissDialogs(page);
  const box = page.getByPlaceholder('Send a test message…');
  await box.scrollIntoViewIfNeeded(); await page.waitForTimeout(800);
  await rec.start(page); await page.waitForTimeout(1000);
  rec.mark('type');
  await humanType(page, box, question, { cps: 24 });
  rec.mark('send');
  await humanClick(page, page.getByRole('button', { name: 'Send' }), { delay: 500, settle: 300 });
  const t0 = Date.now(); let seen = false;
  while (Date.now() - t0 < +waitMs) {
    await page.waitForTimeout(1500);
    const txt = await page.locator('body').innerText();
    if (/Reply failed/.test(txt)) { console.log('reply failed'); break; }
    // Scroll so the newest message stays in view.
    await page.evaluate(() => { const el = document.scrollingElement; el.scrollTop = el.scrollHeight; }).catch(() => {});
    if (!seen && /playground[\s\S]*Northwind Support[\s\S]*(return|days|policy)/i.test(txt.split('you · playground')[1] || '')) { seen = true; rec.mark('reply-started'); }
  }
  await page.waitForTimeout(3000);
  rec.mark('answered');
  await page.screenshot({ path: outDir + '/answer.png' });
  await page.screenshot({ path: outDir + '/answer-full.png', fullPage: true });
  console.log('frames', await rec.stop(), 'elapsed', Math.round((Date.now() - t0) / 1000), 's');
  await browser.close();
})().catch(async e => { console.error('FAILED', e.message.split('\n')[0]); if (global.__page) await global.__page.screenshot({ path: process.argv[3] + '/failed.png' }).catch(()=>{}); try { const fs=require('fs'); } catch(_){} process.exit(1); });
