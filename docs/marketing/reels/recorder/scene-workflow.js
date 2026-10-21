const { newSession, humanClick, humanType, moveCursor } = require('./record');
const { login, dismissDialogs } = require('./login');
(async () => {
  const [id, outDir, waitMs] = process.argv.slice(2);
  const { browser, page, rec } = await newSession({ outDir, width: 900, height: 1600, scale: 1.2 });
  await login(page);
  await page.goto('/workflows/' + id, { waitUntil: 'load' }); await page.waitForTimeout(3500); await dismissDialogs(page);
  const fit = page.getByRole('button', { name: 'Fit to screen (Shift+1)' }); if (await fit.count()) await fit.first().click().catch(() => {});
  await page.waitForTimeout(1200);
  await rec.start(page); await page.waitForTimeout(1500);
  rec.mark('canvas');
  // Hover the approval node so the viewer sees it.
  const appr = page.getByText('Manager approval', { exact: true }).first();
  const b = await appr.boundingBox(); if (b) { await moveCursor(page, b.x + 40, b.y + 20); await page.waitForTimeout(1500); }
  rec.mark('run-now');
  await humanClick(page, page.getByRole('button', { name: 'Run now' }), { delay: 600, settle: 1500 });
  await humanType(page, '#wf-run-prompt', 'Order 48213: customer asks for a refund of USD 1,250 on an e-bike delivered 12 days ago. Box opened, bike unused, receipt attached.', { cps: 40 });
  rec.mark('run');
  await humanClick(page, page.getByRole('button', { name: /Run$/ }), { delay: 600, settle: 1500 });
  await page.screenshot({ path: outDir + '/after-run-click.png' });
  const t0 = Date.now(); let approved = false;
  while (Date.now() - t0 < +waitMs) {
    await page.waitForTimeout(2000);
    const approve = page.getByRole('button', { name: /^Approve/ }).filter({ visible: true });
    if (!approved && await approve.count()) {
      rec.mark('paused');
      await page.screenshot({ path: outDir + '/paused.png' });
      await page.waitForTimeout(2500);
      rec.mark('approve');
      await humanClick(page, approve, { delay: 900, settle: 800 });
      approved = true;
      await page.screenshot({ path: outDir + '/approved.png' });
      continue;
    }
    if (approved) {
      const txt = await page.locator('body').innerText();
      if (/Completed|Succeeded|Finished|Done/i.test(txt) && !/Running/i.test(txt)) { await page.waitForTimeout(2500); break; }
    }
  }
  rec.mark('done');
  await page.screenshot({ path: outDir + '/done.png' });
  console.log('frames', await rec.stop(), 'approved', approved, 'elapsed', Math.round((Date.now() - t0) / 1000), 's');
  await browser.close();
})().catch(async e => { console.error('FAILED', e.message.split('\n')[0]); process.exit(1); });
