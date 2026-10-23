const P = require('./paths');
const { newSession, humanClick, humanType } = require('./record');
const { login } = require('./login');
const shot = (page, n) => page.screenshot({ path: P.shot(`agent-${n}.png`) });
(async () => {
  const { browser, page, rec } = await newSession({ outDir: P.rec('04-agent') });
  await login(page);
  await page.goto('/agents', { waitUntil: 'load' }); await page.waitForTimeout(1500);
  await rec.start(page); await page.waitForTimeout(1000);
  rec.mark('new-agent');
  await humanClick(page, page.getByText('New agent', { exact: true }), { delay: 700, settle: 1500 });
  const gotit = page.getByRole('button', { name: /Got it/ });
  if (await gotit.count()) { await humanClick(page, gotit, { delay: 900, settle: 600 }); }
  rec.mark('name');
  await humanType(page, '#agent-guide-name', 'Northwind Support', { cps: 16 });
  rec.mark('persona');
  await humanClick(page, page.getByRole('button', { name: 'PERSONA · SYSTEM PROMPT' }), { delay: 500, settle: 800 });
  await humanType(page, page.getByRole('textbox', { name: 'PERSONA · SYSTEM PROMPT' }),
    "You are the support agent for Northwind Bikes. Answer questions about returns, warranty, shipping and stores from the knowledge base. Be brief and friendly, quote the policy, and say when a manager must approve.", { cps: 40, settle: 500 });
  rec.mark('model');
  await humanClick(page, page.getByRole('button', { name: 'MODEL & GENERATION' }), { delay: 500, settle: 800 });
  await shot(page, 'provider-preselected');
  await humanClick(page, '#agent-draft-model-select', { delay: 500, settle: 900 });
  await shot(page, 'model-menu');
  const modelRe = new RegExp(process.env.VD_MODEL_MATCH || 'qwen', 'i');
  let opt = page.getByRole('option', { name: modelRe });
  if (!(await opt.count())) opt = page.getByText(modelRe);
  if (await opt.count()) await humanClick(page, opt, { delay: 600, settle: 800 }); else { console.log('no model option'); await page.keyboard.press('Escape'); }
  await shot(page, 'model-picked');
  rec.mark('knowledge');
  await humanClick(page, page.getByRole('button', { name: 'KNOWLEDGE COLLECTIONS' }), { delay: 500, settle: 800 });
  await page.waitForTimeout(1200);
  await shot(page, 'knowledge-checked');
  rec.mark('save');
  await humanClick(page, page.getByRole('button', { name: 'Save draft' }).first(), { delay: 600, settle: 2500 });
  await shot(page, 'saved');
  rec.mark('chat');
  const box = page.getByPlaceholder('Send a test message…');
  await box.scrollIntoViewIfNeeded(); await page.waitForTimeout(500);
  await humanType(page, box, 'Can I return my e-bike after 20 days? I still have the box.', { cps: 24 });
  await humanClick(page, page.getByRole('button', { name: 'Send' }), { delay: 500, settle: 300 });
  const t0 = Date.now();
  await page.waitForTimeout(45000);
  rec.mark('answered');
  await shot(page, 'answer');
  await page.screenshot({ path: P.shot('agent-answer-full.png'), fullPage: true });
  console.log('frames', await rec.stop(), 'url', page.url());
  await browser.close();
})().catch(e => { console.error('FAILED', e.message.split('\n')[0]); process.exit(1); });
