const P = require('./paths');
const { newSession } = require('./record');
const { login } = require('./login');
(async () => {
  const args = process.argv.slice(2);
  const desktop = args[0] === '--desktop'; if (desktop) args.shift();
  const { browser, page } = await newSession({ outDir: P.rec('_tour'), width: desktop ? 1440 : 540, height: desktop ? 900 : 960, scale: desktop ? 1 : 2 });
  await login(page);
  for (const path of args) {
    await page.goto(path, { waitUntil: 'load', timeout: 60000 }).catch(e => console.log('goto', path, e.message));
    await page.waitForTimeout(2500);
    const out = P.shot('t2' + (desktop ? 'd' : '') + path.replace(/[^a-z0-9]+/gi, '_') + '.png');
    await page.screenshot({ path: out, fullPage: true });
    console.log(path, '->', await page.title(), page.url());
  }
  await browser.close();
})();
