import { chromium } from '@playwright/test';
const D = '127.0.0.1.nip.io';
const APP = `http://app.${D}`, API = `http://api.${D}`, AUTH = `http://auth.${D}`;
const origins = ['app','api','auth','admin'].map(h=>`http://${h}.${D}`).join(',');
const browser = await chromium.launch({ args: [`--unsafely-treat-insecure-origin-as-secure=${origins}`] });
const page = await browser.newPage({ bypassCSP: true });
let bearer = null;
page.on('request', r => {
  const u = r.url();
  if (u.startsWith(API) || u.includes('localhost:8280')) {
    const a = r.headers()['authorization'];
    console.log('[req]', r.method(), u.slice(0,100), a ? 'AUTH+' : 'auth-');
    if (u.startsWith(API) && a && /^bearer /i.test(a)) bearer = a;
  }
});
// --- the fix: init script merges api base into window.__ENV ---
await page.addInitScript((apiBase) => {
  Object.defineProperty(window, '__ENV', {
    configurable: true, enumerable: true,
    get: () => ({ NEXT_PUBLIC_API_BASE_URL: apiBase }),
    set(v) { Object.defineProperty(window, '__ENV', { value: Object.freeze({ ...(v ?? {}), NEXT_PUBLIC_API_BASE_URL: apiBase }), writable: true, configurable: true, enumerable: true }); },
  });
}, API);
// --- mimic signInToConsole ---
await page.goto(`${APP}/login`, { waitUntil: 'domcontentloaded' });
const btn = page.getByRole('button', { name: /continue to secure/i });
const first = await Promise.race([
  page.waitForURL(new RegExp('auth\\.'), { timeout: 20000 }).then(()=>'auth'),
  btn.waitFor({ state: 'visible', timeout: 20000 }).then(()=>'email'),
]).catch(()=>'none');
console.log('first:', first, 'url:', page.url());
if (first === 'email') {
  const input = page.getByLabel(/email/i).first();
  for (let i=0;i<4;i++){
    await input.fill('a2atest@example.org');
    if ((await input.inputValue().catch(()=>'')) !== 'a2atest@example.org') { await page.waitForTimeout(500); continue; }
    await btn.click();
    if (await page.waitForURL(new RegExp('auth\\.'), {timeout:10000}).then(()=>true).catch(()=>false)) break;
  }
}
await page.waitForURL(new RegExp('auth\\.'), { timeout: 45000 });
await page.locator('#username').fill('a2atest@example.org');
await page.locator('#password').fill('TestPassw0rd!x');
await page.locator('#kc-login').click();
await page.waitForURL(u => /app\./.test(u.hostname) && !u.hash.includes('state='), { waitUntil:'commit', timeout: 45000 });
console.log('landed:', page.url());
await page.waitForTimeout(5000);
await page.goto(`${APP}/agents`, { waitUntil: 'domcontentloaded' }).catch(e=>console.log('goto err', e.message));
const deadline = Date.now() + 30000;
while (!bearer && Date.now() < deadline) await page.waitForTimeout(300);
console.log('bearer captured:', !!bearer);
const env = await page.evaluate(() => window.__ENV);
console.log('__ENV api:', env && env.NEXT_PUBLIC_API_BASE_URL);
const body2 = await page.locator('body').innerText().catch(()=>'');
console.log('agents page loaded:', /agents/i.test(body2), '| Sign out:', /sign out/i.test(body2));
await browser.close();
process.exit(bearer ? 0 : 1);
