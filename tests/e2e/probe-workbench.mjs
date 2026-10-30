import { chromium } from '@playwright/test';
const D = '127.0.0.1.nip.io';
const APP = `http://app.${D}`, API = `http://api.${D}`;
const origins = ['app','api','auth','admin'].map(h=>`http://${h}.${D}`).join(',');
const browser = await chromium.launch({ args: [`--unsafely-treat-insecure-origin-as-secure=${origins}`] });
const page = await browser.newPage({ bypassCSP: true });
await page.addInitScript((apiBase) => {
  Object.defineProperty(window, '__ENV', { configurable: true, enumerable: true,
    get: () => ({ NEXT_PUBLIC_API_BASE_URL: apiBase }),
    set(v) { Object.defineProperty(window, '__ENV', { value: Object.freeze({ ...(v ?? {}), NEXT_PUBLIC_API_BASE_URL: apiBase }), writable: true, configurable: true, enumerable: true }); } });
}, API);
await page.goto(`${APP}/login`, { waitUntil: 'domcontentloaded' });
const btn = page.getByRole('button', { name: /continue to secure/i });
if (await btn.isVisible({timeout:20000}).catch(()=>false)) {
  const input = page.getByLabel(/email/i).first();
  for (let i=0;i<4;i++){ await input.fill('a2atest@example.org'); await btn.click();
    if (await page.waitForURL(/auth\./,{timeout:10000}).then(()=>true).catch(()=>false)) break; }
}
await page.waitForURL(/auth\./, { timeout: 45000 });
await page.locator('#username').fill('a2atest@example.org');
await page.locator('#password').fill('TestPassw0rd!x');
await page.locator('#kc-login').click();
await page.waitForURL(u => /app\./.test(u.hostname) && !u.hash.includes('state='), { waitUntil:'commit', timeout: 45000 });
await page.locator('header').waitFor({ timeout: 30000 });
const dl = Date.now()+30000;
while (Date.now()<dl) { const b = await page.locator('body').innerText(); if (/sign out|workspace/i.test(b)) break; await page.waitForTimeout(500); }
await page.goto(`${APP}/agents`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(15000);
const body = await page.locator('body').innerText();
console.log('--- has Reasoning & autonomy:', /reasoning & autonomy/i.test(body));
console.log('--- has platform llm:', /platform llm/i.test(body), '| managed:', /managed/i.test(body));
console.log('--- has autonomy-tier testid:', await page.locator('[data-testid^="autonomy-tier"]').count());
console.log('--- has create-agent switcher:', await page.locator('button[aria-haspopup="listbox"][data-tour="create-agent"]').count());
console.log('--- agent rows:', await page.locator('table tr, [role=row]').count());
console.log('--- URL:', page.url());
console.log('--- BODY excerpt:', JSON.stringify(body.slice(0,1500)));
await browser.close();
