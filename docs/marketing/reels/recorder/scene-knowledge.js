const { newSession, humanClick } = require('./record');
const { login } = require('./login');
(async () => {
  const { browser, page, rec } = await newSession({ outDir: '/home/user/rec/out/03-knowledge' });
  await login(page);
  await page.goto('/knowledge', { waitUntil: 'load' }); await page.waitForTimeout(1500);
  const btn = page.getByText('Choose a file to upload', { exact: false }).first();
  await btn.scrollIntoViewIfNeeded(); await page.evaluate(() => window.scrollBy(0, -200)); await page.waitForTimeout(600);
  await rec.start(page); await page.waitForTimeout(1200);
  const files = ['northwind-returns-policy.md', 'northwind-warranty.md', 'northwind-shipping-and-stores.md'].map(f => '/home/user/demo/knowledge/' + f);
  for (const [i, f] of files.entries()) {
    rec.mark('upload-' + i);
    const [chooser] = await Promise.all([page.waitForEvent('filechooser', { timeout: 15000 }), humanClick(page, btn, { delay: 600, settle: 200 })]);
    await chooser.setFiles(f);
    await page.waitForTimeout(3500);
  }
  await page.waitForTimeout(3000);
  await page.screenshot({ path: '/home/user/rec/shots/knowledge-uploaded.png', fullPage: true });
  rec.mark('search');
  const q = page.getByPlaceholder(/how do I return/i).first();
  if (await q.count()) {
    const { humanType } = require('./record');
    await humanType(page, q, 'can I return an e-bike after 20 days?', { cps: 24 });
    await humanClick(page, page.getByRole('button', { name: 'Go' }), { delay: 400, settle: 400 });
    await page.waitForTimeout(4000);
  }
  rec.mark('done');
  await page.screenshot({ path: '/home/user/rec/shots/knowledge-search.png', fullPage: true });
  console.log('frames', await rec.stop());
  await browser.close();
})().catch(e => { console.error('FAILED', e.message.split('\n')[0]); process.exit(1); });
