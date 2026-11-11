import { chromium } from '@playwright/test';
const D = '127.0.0.1.nip.io';
const APP = `http://app.${D}`, API = `http://api.${D}`;
const origins = ['app','api','auth','admin'].map(h=>`http://${h}.${D}`).join(',');
const browser = await chromium.launch({ args: [`--unsafely-treat-insecure-origin-as-secure=${origins}`] });
const page = await browser.newPage({ bypassCSP: true });
let bearer = null;
page.on('request', r => { const a = r.headers()['authorization']; if (r.url().startsWith(API) && a && /^bearer /i.test(a)) bearer = a.replace(/^bearer /i,''); });
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
await page.waitForURL(u => /app\./.test(u.hostname), { waitUntil:'commit', timeout: 45000 });
const dl = Date.now()+25000; while(!bearer && Date.now()<dl) await page.waitForTimeout(300);
console.log('token:', !!bearer);
const H = { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' };
const req = page.request;

// pick a provider/model from existing agent
const list = await (await req.get(`${API}/api/agents`, { headers: H })).json();
const tmpl = list.find(a=>a.providerName==='PLATFORM_HOSTED') || list[0];
console.log('template:', tmpl?.providerName, tmpl?.model);
const create = await req.post(`${API}/api/agents`, { headers: H, data: {
  name: `PROBE-SAMPLING-${Date.now()}`, systemPrompt: 'You are terse.',
  providerName: tmpl?.providerName, model: tmpl?.model } });
const agent = await create.json();
console.log('agent:', create.status(), agent.id);

async function patch(path, data) {
  const r = await req.fetch(`${API}/api/agents/${agent.id}${path}`, { method:'PATCH', headers: H, data: JSON.stringify(data) });
  return r.status();
}
async function chat(msg) {
  const r = await req.post(`${API}/api/agents/${agent.id}/chat`, { headers: H,
    data: { message: msg, stepEvents: true }, timeout: 180000 });
  const body = await r.text();
  const reply = body.split(/\r?\n\r?\n/).map(b=>b.split(/\r?\n/).filter(l=>l.startsWith('data:')).map(l=>l.slice(5)).join('\n'))
    .filter(p=>p && !p.startsWith('@@AGENT_')).join('');
  return { status: r.status(), reply };
}
async function cfg() { return (await (await req.get(`${API}/api/agents/${agent.id}`, { headers: H })).json()); }

// try config PATCH endpoints used by the spec
console.log('patch maxTokens=24 ->', await patch('/generation-params', { maxTokens: 24 }));
console.log('cfg.maxTokens =', (await cfg()).maxTokens);
const r1 = await chat('Count from one to thirty, one number per line.');
console.log('reply@24 words:', r1.reply.split(/\s+/).length, '| status', r1.status, '| head:', JSON.stringify(r1.reply.slice(0,120)));

console.log('patch maxTokens=1500 ->', await patch('/generation-params', { maxTokens: 1500 }));
console.log('cfg.maxTokens =', (await cfg()).maxTokens);
const r2 = await chat('Count from one to thirty, one number per line.');
console.log('reply@1500 words:', r2.reply.split(/\s+/).length, '| head:', JSON.stringify(r2.reply.slice(0,120)));

console.log('patch stopSequences [STOP] ->', await patch('/generation-params', { stopSequences: ['STOP'] }));
const c = await cfg();
console.log('cfg stop fields:', JSON.stringify({stopSequences: c.stopSequences, stop: c.stop, generationConfig: c.generationConfig && Object.keys(c.generationConfig)}));
const r3 = await chat('Reply with exactly: ONE TWO STOP THREE FOUR');
console.log('reply@stop:', JSON.stringify(r3.reply.slice(0,200)));

await req.delete(`${API}/api/agents/${agent.id}`, { headers: H });
await browser.close();
