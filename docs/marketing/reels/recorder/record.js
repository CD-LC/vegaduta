// Screen recorder for the reels: drives the real console with Playwright and
// captures CDP screencast frames (device pixels) so phone-sized captures stay
// crisp. Output: <dir>/frames/*.png + <dir>/frames.txt (ffmpeg concat with
// per-frame durations) + <dir>/events.json (timestamped markers for the edit).
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

class Recorder {
  constructor(outDir) {
    this.outDir = outDir; fs.mkdirSync(path.join(outDir, 'frames'), { recursive: true });
    this.frames = []; this.events = []; this.t0 = null; this.n = 0;
  }
  async start(page, { maxWidth = 1080, maxHeight = 1920 } = {}) {
    this.client = await page.context().newCDPSession(page);
    this.client.on('Page.screencastFrame', async ({ data, sessionId, metadata }) => {
      const t = metadata.timestamp * 1000;
      if (this.t0 === null) this.t0 = t;
      const f = `f${String(this.n++).padStart(6, '0')}.jpg`;
      fs.writeFileSync(path.join(this.outDir, 'frames', f), Buffer.from(data, 'base64'));
      this.frames.push({ f, t: t - this.t0 });
      try { await this.client.send('Page.screencastFrameAck', { sessionId }); } catch (_) {}
    });
    this.startWall = Date.now();
    await this.client.send('Page.startScreencast', { format: 'jpeg', quality: 92, maxWidth, maxHeight, everyNthFrame: 2 });
  }
  mark(name) { if (this.startWall) this.events.push({ name, t: Date.now() - this.startWall }); }
  async stop() {
    try { await this.client.send('Page.stopScreencast'); } catch (_) {}
    await new Promise(r => setTimeout(r, 300));
    const lines = ['ffconcat version 1.0'];
    for (let i = 0; i < this.frames.length; i++) {
      const d = i + 1 < this.frames.length ? (this.frames[i + 1].t - this.frames[i].t) / 1000 : 1.0;
      lines.push(`file 'frames/${this.frames[i].f}'`, `duration ${Math.max(d, 0.01).toFixed(4)}`);
    }
    if (this.frames.length) lines.push(`file 'frames/${this.frames[this.frames.length - 1].f}'`);
    fs.writeFileSync(path.join(this.outDir, 'frames.txt'), lines.join('\n') + '\n');
    fs.writeFileSync(path.join(this.outDir, 'events.json'), JSON.stringify(this.events, null, 1));
    return this.frames.length;
  }
}

// A visible pointer so viewers can follow the action; moved before each click.
const CURSOR_CSS = `
#vd-cursor{position:fixed;z-index:2147483647;width:44px;height:44px;left:0;top:0;pointer-events:none;
 transform:translate(-8px,-6px);transition:left .45s cubic-bezier(.2,.8,.2,1),top .45s cubic-bezier(.2,.8,.2,1);opacity:0}
#vd-cursor svg{filter:drop-shadow(0 2px 4px rgba(0,0,0,.45))}
#vd-cursor.click{animation:vdclick .35s ease-out}
@keyframes vdclick{0%{transform:translate(-8px,-6px) scale(1)}40%{transform:translate(-8px,-6px) scale(.78)}100%{transform:translate(-8px,-6px) scale(1)}}`;
const CURSOR_SVG = `<svg viewBox="0 0 24 24" width="44" height="44"><path d="M5 3l14 8.5-6.2 1.4L16 20l-3 1.3-3.2-7.2L5 18z" fill="#fff" stroke="#111" stroke-width="1.4" stroke-linejoin="round"/></svg>`;

async function installCursor(page) {
  await page.addInitScript(({ css, svg }) => {
    const add = () => {
      if (document.getElementById('vd-cursor')) return;
      const s = document.createElement('style'); s.textContent = css; document.documentElement.appendChild(s);
      const c = document.createElement('div'); c.id = 'vd-cursor'; c.innerHTML = svg; document.documentElement.appendChild(c);
    };
    if (document.readyState !== 'loading') add(); else document.addEventListener('DOMContentLoaded', add);
  }, { css: CURSOR_CSS, svg: CURSOR_SVG });
}

async function moveCursor(page, x, y, { show = true } = {}) {
  await page.evaluate(({ x, y, show }) => {
    const c = document.getElementById('vd-cursor'); if (!c) return;
    c.style.opacity = show ? '1' : '0'; c.style.left = x + 'px'; c.style.top = y + 'px';
  }, { x, y, show }).catch(() => {});
}

async function humanClick(page, locator, { delay = 500, settle = 400 } = {}) {
  const el = (typeof locator === 'string' ? page.locator(locator) : locator).filter({ visible: true }).first();
  await el.waitFor({ state: 'visible', timeout: 20000 });
  await el.scrollIntoViewIfNeeded().catch(() => {});
  const box = await el.boundingBox();
  if (box) {
    await moveCursor(page, box.x + Math.min(box.width / 2, 120), box.y + box.height / 2);
    await page.waitForTimeout(delay);
    await page.evaluate(() => { const c = document.getElementById('vd-cursor'); if (c) { c.classList.remove('click'); void c.offsetWidth; c.classList.add('click'); } }).catch(() => {});
    await page.mouse.move(box.x + Math.min(box.width / 2, 120), box.y + box.height / 2);
  }
  await el.click();
  await page.waitForTimeout(settle);
}

async function humanType(page, locator, text, { cps = 28, settle = 300 } = {}) {
  const el = (typeof locator === 'string' ? page.locator(locator) : locator).filter({ visible: true }).first();
  await humanClick(page, el, { delay: 350, settle: 150 });
  await el.fill('');
  for (const ch of text) {
    await el.type(ch, { delay: 0 });
    await page.waitForTimeout(1000 / cps + (ch === ' ' ? 25 : 0) + Math.random() * 30);
  }
  await page.waitForTimeout(settle);
}

async function newSession({ outDir, width = 540, height = 960, scale = 2, baseURL = require('./paths').BASE_URL, storageState } = {}) {
  const browser = await chromium.launch({ args: ['--force-device-scale-factor=' + scale, '--hide-scrollbars'] });
  const context = await browser.newContext({
    viewport: { width, height }, deviceScaleFactor: scale, baseURL, storageState,
    colorScheme: 'dark', locale: 'en-US', reducedMotion: 'no-preference',
    userAgent: scale === 2 && width < 700 ? 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1' : undefined,
    isMobile: width < 700, hasTouch: false,
  });
  const page = await context.newPage();
  await installCursor(page);
  const rec = new Recorder(outDir); rec.wall0 = Date.now();
  return { browser, context, page, rec };
}

module.exports = { Recorder, newSession, humanClick, humanType, moveCursor, installCursor };
