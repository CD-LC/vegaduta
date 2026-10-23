// The Cost & ROI page with the seeded Example data (watermark on screen), then the price table and the
// pilot report. 540x960@2x.
const P = require('./paths');
const { newSession, humanClick } = require('./record');
const { login, dismissDialogs } = require('./login');
(async () => {
  const { browser, page, rec } = await newSession({ outDir: P.rec('07-roi') });
  await login(page);
  await page.goto('/roi', { waitUntil: 'load' }); await page.waitForTimeout(3500); await dismissDialogs(page);
  await rec.start(page); await page.waitForTimeout(1500);
  rec.mark('overview');
  await page.screenshot({ path: P.shot('roi-top.png') });
  for (let i = 0; i < 5; i++) { await page.mouse.wheel(0, 300); await page.waitForTimeout(800); }
  rec.mark('prices');
  await page.screenshot({ path: P.shot('roi-prices.png') });
  for (let i = 0; i < 5; i++) { await page.mouse.wheel(0, 300); await page.waitForTimeout(800); }
  rec.mark('report');
  const rep = page.getByRole('link', { name: /pilot report/i }).or(page.getByRole('button', { name: /pilot report/i })).first();
  if (await rep.count()) { await humanClick(page, rep, { delay: 800, settle: 3000 }); }
  await page.screenshot({ path: P.shot('roi-report.png') });
  await page.waitForTimeout(2000);
  rec.mark('done');
  await page.screenshot({ path: P.shot('roi-full.png'), fullPage: true });
  console.log('frames', await rec.stop(), 'url', page.url());
  await browser.close();
})().catch(e => { console.error('FAILED', e.message.split('\n')[0]); process.exit(1); });
