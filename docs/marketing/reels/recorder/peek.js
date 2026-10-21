const { newSession, humanClick } = require('./record');
const { login } = require('./login');
(async () => {
  const [path, clickText, out] = process.argv.slice(2);
  const { browser, page } = await newSession({ outDir: '/home/user/rec/out/_peek' });
  await login(page);
  await page.goto(path, { waitUntil: 'load' }); await page.waitForTimeout(2000);
  if (clickText) { await humanClick(page, page.getByText(clickText, { exact: false }).first(), { delay: 100, settle: 1500 }); }
  await page.screenshot({ path: out, fullPage: true });
  const inputs = await page.locator('input, textarea, select, button').evaluateAll(els => els.filter(e => e.offsetParent).map(e => `${e.tagName}:${e.type||''}:${e.id||''}:${(e.placeholder||e.textContent||'').trim().slice(0,40)}`));
  console.log(page.url()); console.log(inputs.join('\n'));
  await browser.close();
})();
