// The Connect page: the channel list, then the Telegram channel's setup card. 540x960@2x, like the other takes.
const P = require('./paths');
const { newSession, humanClick, moveCursor } = require('./record');
const { login, dismissDialogs } = require('./login');
(async () => {
  const { browser, page, rec } = await newSession({ outDir: P.rec('06-connect') });
  await login(page);
  await page.goto('/connect', { waitUntil: 'load' }); await page.waitForTimeout(2500); await dismissDialogs(page);
  await rec.start(page); await page.waitForTimeout(1500);
  rec.mark('list');
  // Scroll the channel list slowly so every channel type passes through the frame.
  for (let i = 0; i < 6; i++) { await page.mouse.wheel(0, 260); await page.waitForTimeout(700); }
  await page.screenshot({ path: P.shot('connect-list.png') });
  rec.mark('telegram');
  const tg = page.getByText('Telegram', { exact: true }).first();
  if (await tg.count()) { await humanClick(page, tg, { delay: 800, settle: 2500 }); }
  await page.screenshot({ path: P.shot('connect-telegram.png') });
  await page.waitForTimeout(2500);
  rec.mark('done');
  await page.screenshot({ path: P.shot('connect-telegram-full.png'), fullPage: true });
  console.log('frames', await rec.stop(), 'url', page.url());
  await browser.close();
})().catch(e => { console.error('FAILED', e.message.split('\n')[0]); process.exit(1); });
