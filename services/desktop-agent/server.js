'use strict';

// desktop-agent - the personal install's hands on the laptop. Runs ON the host
// (never in a container: GUI access is the whole point) and exposes the screen
// and input devices to core over a loopback HTTP API, so the Assistant can do
// computer-use work: see the screen, name its buttons, click, type, open apps,
// notify and read the clipboard.
//
// Security model: it listens on 127.0.0.1 only and every call needs
// `Authorization: Bearer $DESKTOP_AGENT_SECRET` (no exceptions - an unauthenticated
// performs are NOT sandboxed - this process can touch anything the logged-in
// user can. Core's tool guard still gates each call (classification gate plus
// the optional approval pin), and every request is logged on the core side.
//
// macOS is the primary platform: clicks go through CoreGraphics events via JXA
// (no external binaries), typing and the accessibility tree through System
// Events. Both need the running terminal/grant: Accessibility + Screen
// Recording permissions in System Settings > Privacy & Security.
// Linux uses xdotool / gnome-screenshot / scrot / xclip / notify-send when
// present and answers 501 with the missing tool otherwise.
//
// API (every call needs the Bearer secret):
//   POST   /screenshot      {}                    -> {path, bytes}           PNG under DATA_DIR
//   POST   /ui              {app?}                -> {app, window, elements:[{role,label,x,y,w,h}]}
//   POST   /ui/press        {label, role?, app?}  -> {ok:true, matched:{...}}   press a named element
//   POST   /click           {x, y, button?}       -> {ok:true}
//   POST   /type            {text}                -> {ok:true, chars}
//   POST   /key             {key}                 -> {ok:true}                return|tab|escape|space|delete|arrows|enter
//   POST   /open            {target, application?} -> {ok:true}               app name, file or URL
//   POST   /activate        {app}                 -> {ok:true}
//   POST   /notify          {title, body}         -> {ok:true}
//   GET    /clipboard                             -> {text}
//   POST   /clipboard       {text}                -> {ok:true}
//   GET    /frame?scale&max                       -> image/jpeg|png bytes + X-Frame-Format
//   GET    /capabilities                          -> {screenshotFormats, ui, clipboard, stream}

const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFile } = require('node:child_process');

const PORT = parseInt(process.env.DESKTOP_AGENT_PORT || '8787', 10);
const BIND = process.env.DESKTOP_AGENT_BIND || '127.0.0.1';
const SECRET = (process.env.DESKTOP_AGENT_SECRET || '').trim();
const DATA_DIR =
  process.env.DESKTOP_AGENT_DATA_DIR ||
  path.join(os.homedir(), 'vegaduta-workspace', 'screenshots');
const MAX_BODY_BYTES = 256 * 1024;
const MAX_ELEMENTS = 250;
const MAX_LABEL_CHARS = 120;
const EXEC_TIMEOUT_MS = 15000;
const FRAME_TIMEOUT_MS = 5000;
const PLATFORM = process.platform;

const KEY_CODES_MAC = {
  return: 36, enter: 76, tab: 48, space: 49, delete: 51, escape: 53,
  left: 123, right: 124, down: 125, up: 126,
  f1: 122, f2: 120, f3: 99, f4: 118, f5: 96, f6: 97, f7: 98, f8: 100,
  f9: 101, f10: 109, f11: 103, f12: 111,
};

const KEY_NAMES_XDOTOOL = {
  return: 'Return', enter: 'KP_Enter', tab: 'Tab', space: 'space', delete: 'BackSpace',
  escape: 'Escape', left: 'Left', right: 'Right', down: 'Down', up: 'Up',
};

// ---------------------------------------------------------------------------
// Command helpers
// ---------------------------------------------------------------------------

function run(argv, timeoutMs = EXEC_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const [cmd, ...args] = argv;
    execFile(cmd, args, { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) {
        const detail = (stderr || err.message || '').toString().trim().split('\n').pop();
        reject(Object.assign(new Error(detail || 'command failed'), { code: err.code }));
        return;
      }
      resolve(stdout.toString());
    });
  });
}

async function hasBinary(name) {
  try {
    await run(['/usr/bin/which', name], 3000);
    return true;
  } catch {
    return false;
  }
}

function appleScript(script) {
  return run(['osascript', '-e', script]);
}

function jxa(script) {
  return run(['osascript', '-l', 'JavaScript', '-e', script]);
}

/** Escape a JS string for embedding inside an AppleScript double-quoted literal. */
function appleQuote(s) {
  return String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

// ---------------------------------------------------------------------------
// Actions - macOS
// ---------------------------------------------------------------------------

async function macScreenshot() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const file = path.join(DATA_DIR, `screenshot-${Date.now()}.png`);
  await run(['screencapture', '-x', '-t', 'png', file], 20000);
  const bytes = fs.statSync(file).size;
  return { path: file, bytes };
}

/**
 * The accessibility tree of the frontmost (or named) app's front window:
 * interactive elements with their on-screen position, so a model can name what
 * to press instead of guessing pixels. Depth-bounded, label-truncated.
 */
async function macUiTree(appName) {
  const script = `
    set out to ""
    set maxN to ${MAX_ELEMENTS}
    set n to 0
    tell application "System Events"
      set p to first application process whose frontmost is true
      ${appName ? `set p to first application process whose name is "${appleQuote(appName)}"` : ''}
      set appTitle to name of p
      set winTitle to ""
      try
        set winTitle to name of front window of p
      end try
      repeat with w in (every window of p)
        set kids to {}
        try
          set kids to entire contents of w
        end try
        repeat with e in kids
          if n >= maxN then exit repeat
          try
            set r to role of e
            set lbl to ""
            try
              set lbl to name of e
            end try
            if lbl is "" then
              try
                set lbl to title of e
              end try
            end if
            if r is in {"AXButton", "AXTextField", "AXTextArea", "AXCheckBox", "AXRadioButton", "AXMenuButton", "AXPopUpButton", "AXLink", "AXMenuItem", "AXTab", "AXSlider", "AXComboBox", "AXStaticText"} and lbl is not "" then
              set pos to position of e
              set sz to size of e
              set out to out & r & "\\t" & lbl & "\\t" & (item 1 of pos) & "\\t" & (item 2 of pos) & "\\t" & (item 1 of sz) & "\\t" & (item 2 of sz) & "\\n"
              set n to n + 1
            end if
          end try
        end repeat
        if n >= maxN then exit repeat
      end repeat
      return appTitle & "\\n" & winTitle & "\\n" & out
    end tell`;
  const raw = await appleScript(script);
  const [app, window, ...lines] = raw.trim().split('\n');
  const elements = lines
    .map((line) => {
      const [role, label, x, y, w, h] = line.split('\t');
      return {
        role,
        label: String(label || '').slice(0, MAX_LABEL_CHARS),
        x: Number(x), y: Number(y), w: Number(w), h: Number(h),
      };
    })
    .filter((e) => e.label);
  return { app, window, elements };
}

/** Press the first UI element whose label contains `label` (case-insensitive). */
async function macUiPress(label, role, appName) {
  const tree = await macUiTree(appName);
  const needle = String(label).toLowerCase();
  const hit = tree.elements.find(
    (e) => e.label.toLowerCase().includes(needle) && (!role || e.role === role));
  if (!hit) {
    const err = new Error(`no UI element labelled "${label}" in ${tree.app || 'frontmost app'}`);
    err.status = 404;
    throw err;
  }
  const { x, y, w, h } = hit;
  await macClick(x + w / 2, y + h / 2, 'left');
  return { matched: hit };
}

async function macClick(x, y, button) {
  const type = button === 'right' ? 'right' : 'left';
  const jxaScript = `
    ObjC.import('CoreGraphics');
    var pt = $.CGPointMake(${Number(x)}, ${Number(y)});
    var downType = ${type === 'right' ? '$.kCGEventRightMouseDown' : '$.kCGEventLeftMouseDown'};
    var upType = ${type === 'right' ? '$.kCGEventRightMouseUp' : '$.kCGEventLeftMouseUp'};
    var btn = ${type === 'right' ? '$.kCGMouseButtonRight' : '$.kCGMouseButtonLeft'};
    $.CGEventPost($.kCGHIDEventTap, $.CGEventCreateMouseEvent($(), downType, pt, btn));
    delay(0.05);
    $.CGEventPost($.kCGHIDEventTap, $.CGEventCreateMouseEvent($(), upType, pt, btn));
    "ok"`;
  await jxa(jxaScript);
}

async function macType(text) {
  // keystroke respects the user's keyboard layout; split long text so one
  // AppleScript call does not stall the event tap.
  const chunks = String(text).match(/.{1,80}/gs) || [];
  for (const chunk of chunks) {
    await appleScript(
      `tell application "System Events" to keystroke "${appleQuote(chunk)}"`);
  }
  return { chars: String(text).length };
}

const MAC_MODIFIERS = {
  cmd: 'command down', command: 'command down',
  ctrl: 'control down', control: 'control down',
  alt: 'option down', option: 'option down',
  shift: 'shift down',
};

async function macKey(key) {
  const parts = String(key).toLowerCase().split('+').map((p) => p.trim()).filter(Boolean);
  const keyName = parts.pop();
  const mods = parts.map((m) => {
    const mod = MAC_MODIFIERS[m];
    if (!mod) {
      const err = new Error(`unknown modifier "${m}" - one of ${Object.keys(MAC_MODIFIERS).join(', ')}`);
      err.status = 400;
      throw err;
    }
    return mod;
  });
  // A single letter/character presses it as a keystroke (respects modifiers);
  // a named key goes through key code.
  const script =
    mods.length === 0
      ? namedKeyScript(keyName)
      : keyName && keyName.length === 1
        ? `tell application "System Events" to keystroke "${appleQuote(keyName)}" using {${mods.join(', ')}}`
        : namedKeyScript(keyName, mods);
  if (!script) {
    const err = new Error(`unknown key "${keyName}" - one of ${Object.keys(KEY_CODES_MAC).join(', ')}, a single letter, or modifier+key (cmd+s)`);
    err.status = 400;
    throw err;
  }
  await appleScript(script);
}

function namedKeyScript(name, mods = []) {
  const code = KEY_CODES_MAC[name];
  if (code === undefined) return null;
  return mods.length
    ? `tell application "System Events" to key code ${code} using {${mods.join(', ')}}`
    : `tell application "System Events" to key code ${code}`;
}

async function macOpen(target, application) {
  const t = String(target);
  const argv = ['open'];
  if (application) argv.push('-a', application);
  else if (!t.startsWith('http://') && !t.startsWith('https://') && !fs.existsSync(t)) {
    // No handler named and the target is not a file that exists: treat it as an
    // app name ('open -a TextEdit'), not a path - plain 'open TextEdit' only
    // looks for a file and fails with "does not exist".
    argv.push('-a');
  }
  argv.push(t);
  await run(argv);
}

async function macActivate(app) {
  await appleScript(`tell application "${appleQuote(app)}" to activate`);
}

async function macNotify(title, body) {
  await appleScript(
    `display notification "${appleQuote(body)}" with title "${appleQuote(title)}"`);
}

// ---------------------------------------------------------------------------
// Actions - Linux (best effort; needs the X11/Wayland tools to be installed)
// ---------------------------------------------------------------------------

async function linuxScreenshot() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const file = path.join(DATA_DIR, `screenshot-${Date.now()}.png`);
  for (const argv of [
    ['gnome-screenshot', '-f', file],
    ['scrot', file],
    ['import', '-window', 'root', file],
    ['grim', file],
  ]) {
    if (await hasBinary(argv[0])) {
      await run(argv, 20000);
      return { path: file, bytes: fs.statSync(file).size };
    }
  }
  const err = new Error('no screenshot tool found (gnome-screenshot, scrot, import or grim)');
  err.status = 501;
  throw err;
}

async function linuxClick(x, y, button) {
  if (!(await hasBinary('xdotool'))) {
    const err = new Error('xdotool not installed');
    err.status = 501;
    throw err;
  }
  const btn = button === 'right' ? '3' : '1';
  await run(['xdotool', 'mousemove', String(Math.round(x)), String(Math.round(y)), 'click', btn]);
}

async function linuxType(text) {
  if (!(await hasBinary('xdotool'))) {
    const err = new Error('xdotool not installed');
    err.status = 501;
    throw err;
  }
  await run(['xdotool', 'type', '--delay', '20', '--', String(text)]);
  return { chars: String(text).length };
}

const XDOTOOL_MODIFIERS = {
  cmd: 'super', command: 'super', super: 'super',
  ctrl: 'ctrl', control: 'ctrl',
  alt: 'alt', option: 'alt', meta: 'meta',
  shift: 'shift',
};

async function linuxKey(key) {
  if (!(await hasBinary('xdotool'))) {
    const err = new Error('xdotool not installed');
    err.status = 501;
    throw err;
  }
  // Modifier combos land as "ctrl+s"/"super+n" in xdotool's own syntax.
  const parts = String(key).toLowerCase().split('+').map((p) => p.trim()).filter(Boolean);
  const keyName = parts.pop();
  const mods = parts.map((m) => {
    const mod = XDOTOOL_MODIFIERS[m];
    if (!mod) {
      const err = new Error(`unknown modifier "${m}" - one of ${Object.keys(XDOTOOL_MODIFIERS).join(', ')}`);
      err.status = 400;
      throw err;
    }
    return mod;
  });
  const name = KEY_NAMES_XDOTOOL[keyName] || keyName;
  await run(['xdotool', 'key', ...mods, name]);
}

async function linuxOpen(target, application) {
  if (application) await run([application]);
  else await run(['xdg-open', String(target)]);
}

async function linuxActivate(app) {
  if (!(await hasBinary('wmctrl'))) {
    const err = new Error('wmctrl not installed');
    err.status = 501;
    throw err;
  }
  await run(['wmctrl', '-a', String(app)]);
}

async function linuxNotify(title, body) {
  if (!(await hasBinary('notify-send'))) {
    const err = new Error('notify-send not installed');
    err.status = 501;
    throw err;
  }
  await run(['notify-send', String(title), String(body)]);
}

async function linuxClipboardGet() {
  for (const argv of [['xclip', '-o', '-selection', 'clipboard'], ['xsel', '-bo'], ['wl-paste']]) {
    if (await hasBinary(argv[0])) return { text: await run(argv) };
  }
  const err = new Error('no clipboard tool found (xclip, xsel or wl-paste)');
  err.status = 501;
  throw err;
}

async function linuxClipboardSet(text) {
  for (const argv of [['xclip', '-selection', 'clipboard'], ['xsel', '-bi'], ['wl-copy']]) {
    if (await hasBinary(argv[0])) {
      await runStdin(argv, String(text));
      return;
    }
  }
  const err = new Error('no clipboard tool found (xclip, xsel or wl-copy)');
  err.status = 501;
  throw err;
}

function runStdin(argv, input) {
  return new Promise((resolve, reject) => {
    const [cmd, ...args] = argv;
    const child = execFile(cmd, args, { timeout: EXEC_TIMEOUT_MS }, (err) =>
      err ? reject(err) : resolve());
    child.stdin.end(input);
  });
}

// ---------------------------------------------------------------------------
// Live monitor frames - one serialized capture at a time, ~5s child budget.
// The action answers {frame:{bytes, format:'jpeg'|'png'}} and the HTTP layer
// writes the bytes with the matching Content-Type / X-Frame-Format headers.
// ---------------------------------------------------------------------------

/** Parse the /frame query: scale is (0,1], max is a positive pixel bound. */
function frameParams(params) {
  let scale = Number(params && params.scale);
  if (!Number.isFinite(scale) || scale <= 0 || scale > 1) scale = 1;
  let max = parseInt(params && params.max, 10);
  if (!Number.isFinite(max) || max <= 0) max = 0;
  return { scale, max };
}

/**
 * Downscale `file` in place with the built-in `sips`: `max` caps the largest
 * side directly; `scale` < 1 shrinks it to that fraction of the native size.
 * No-op when neither applies. `left()` returns the remaining frame budget.
 */
async function sipsShrink(file, scale, max, left) {
  let target = max;
  if (scale < 1) {
    const out = await run(
      ['sips', '-g', 'pixelWidth', '-g', 'pixelHeight', file], left());
    const sides = [...out.matchAll(/pixel(?:Width|Height):\s*(\d+)/g)]
      .map((m) => Number(m[1]));
    const biggest = Math.max(0, ...sides);
    if (biggest > 0) {
      const scaled = Math.max(1, Math.round(biggest * scale));
      target = target ? Math.min(target, scaled) : scaled;
    }
  }
  if (target) await run(['sips', '-Z', String(target), file], left());
}

async function macFrame(scale, max) {
  const deadline = Date.now() + FRAME_TIMEOUT_MS;
  const left = () => Math.max(200, deadline - Date.now());
  const file = path.join(
    os.tmpdir(), `desktop-agent-frame-${process.pid}-${Date.now()}.jpg`);
  try {
    await run(['screencapture', '-x', '-t', 'jpg', file], left());
    // Read once and judge the bytes: stat-then-read here trips the
    // file-system-race check, and an empty file fails the capture the same way.
    let bytes = fs.readFileSync(file);
    if (!bytes.length) throw new Error('empty capture');
    await sipsShrink(file, scale, max, left);
    return { frame: { bytes: fs.readFileSync(file), format: 'jpeg' } };
  } catch {
    // The jpeg path failed (no Screen Recording permission, sips trouble...) -
    // hand back the same png /screenshot would have produced.
    const shot = await macScreenshot();
    return { frame: { bytes: fs.readFileSync(shot.path), format: 'png' } };
  } finally {
    fs.rmSync(file, { force: true });
  }
}

async function linuxFrame(scale, max) {
  const deadline = Date.now() + FRAME_TIMEOUT_MS;
  const left = () => Math.max(200, deadline - Date.now());
  const file = path.join(
    os.tmpdir(), `desktop-agent-frame-${process.pid}-${Date.now()}.jpg`);
  try {
    if (await hasBinary('import')) {
      const argv = ['import', '-window', 'root'];
      if (scale < 1) argv.push('-resize', `${Math.round(scale * 100)}%`);
      if (max) argv.push('-resize', `${max}x${max}>`);
      argv.push(`jpg:${file}`);
      if (await tryCapture(argv, file, left)) {
        return { frame: { bytes: fs.readFileSync(file), format: 'jpeg' } };
      }
    }
    if (await hasBinary('maim') &&
        await tryCapture(['maim', '-f', 'jpg', file], file, left)) {
      return { frame: { bytes: fs.readFileSync(file), format: 'jpeg' } };
    }
    // No jpeg-capable tool, or they failed (import/maim are X11-only) - the png
    // path still covers Wayland through grim.
    const shot = await linuxScreenshot();
    return { frame: { bytes: fs.readFileSync(shot.path), format: 'png' } };
  } finally {
    fs.rmSync(file, { force: true });
  }
}

/** Run a frame capture; true when the tool wrote a non-empty file. */
async function tryCapture(argv, file, left) {
  try {
    await run(argv, left());
    return fs.existsSync(file) && fs.statSync(file).size > 0;
  } catch {
    return false;
  }
}

/** What the console's feature probe needs to know - cheap, and honest on both platforms. */
async function capabilities() {
  const jpeg = mac || (await hasBinary('import')) || (await hasBinary('maim'));
  return {
    screenshotFormats: jpeg ? ['jpeg', 'png'] : ['png'],
    ui: mac,
    clipboard: true,
    stream: false,
  };
}

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

const mac = PLATFORM === 'darwin';

const actions = {
  'POST /screenshot': async () => (mac ? macScreenshot() : linuxScreenshot()),
  'POST /ui': async (b) => {
    if (!mac) throw Object.assign(new Error('UI tree needs macOS accessibility; not implemented on ' + PLATFORM), { status: 501 });
    return macUiTree(b.app);
  },
  'POST /ui/press': async (b) => {
    if (!mac) throw Object.assign(new Error('UI press needs macOS accessibility; not implemented on ' + PLATFORM), { status: 501 });
    if (!b.label) throw Object.assign(new Error('label is required'), { status: 400 });
    return macUiPress(b.label, b.role, b.app);
  },
  'POST /click': async (b) => {
    if (!Number.isFinite(b.x) || !Number.isFinite(b.y)) {
      throw Object.assign(new Error('x and y are required numbers'), { status: 400 });
    }
    return mac ? macClick(b.x, b.y, b.button) : linuxClick(b.x, b.y, b.button);
  },
  'POST /type': async (b) => {
    if (typeof b.text !== 'string' || !b.text) {
      throw Object.assign(new Error('text is required'), { status: 400 });
    }
    return mac ? macType(b.text) : linuxType(b.text);
  },
  'POST /key': async (b) => {
    if (!b.key) throw Object.assign(new Error('key is required'), { status: 400 });
    return mac ? macKey(b.key) : linuxKey(b.key);
  },
  'POST /open': async (b) => {
    if (!b.target) throw Object.assign(new Error('target is required'), { status: 400 });
    return mac ? macOpen(b.target, b.application) : linuxOpen(b.target, b.application);
  },
  'POST /activate': async (b) => {
    if (!b.app) throw Object.assign(new Error('app is required'), { status: 400 });
    return mac ? macActivate(b.app) : linuxActivate(b.app);
  },
  'POST /notify': async (b) => {
    if (!b.title) throw Object.assign(new Error('title is required'), { status: 400 });
    return mac ? macNotify(b.title, b.body || '') : linuxNotify(b.title, b.body || '');
  },
  'GET /clipboard': async () => {
    if (mac) return { text: await run(['pbpaste']) };
    return linuxClipboardGet();
  },
  'POST /clipboard': async (b) => {
    if (typeof b.text !== 'string') {
      throw Object.assign(new Error('text is required'), { status: 400 });
    }
    if (mac) await runStdin(['pbcopy'], b.text);
    else await linuxClipboardSet(b.text);
    return { ok: true };
  },
  // One screen frame for the console's live monitor. Answers
  // {frame:{bytes, format}}; the HTTP layer ships the raw bytes.
  'GET /frame': async (params) => {
    const { scale, max } = frameParams(params);
    return mac ? macFrame(scale, max) : linuxFrame(scale, max);
  },
};

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(payload);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('body too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function authorized(req) {
  const header = req.headers.authorization || '';
  const presented = Buffer.from(header);
  const expected = Buffer.from(`Bearer ${SECRET}`);
  return SECRET &&
    presented.length === expected.length &&
    crypto.timingSafeEqual(presented, expected);
}

// One frame capture at a time - the screen tools are not reentrant and the
// monitor only ever wants the freshest frame anyway.
let frameBusy = false;

function createServer() {
  return http.createServer(async (req, res) => {
    try {
      const q = req.url.indexOf('?');
      const pathname = q === -1 ? req.url : req.url.slice(0, q);
      // Every route requires the bearer token - no unauthenticated bypass.
      // A 401 from /health still answers the liveness question for anyone
      // checking whether the agent is up.
      if (!authorized(req)) {
        return json(res, 401, { error: 'unauthorized' });
      }
      if (pathname === '/health') {
        return json(res, 200, { ok: true, platform: PLATFORM });
      }
      if (pathname === '/capabilities') {
        return json(res, 200, await capabilities());
      }
      const route = `${req.method} ${pathname}`;
      // hasOwn (not just truthiness) keeps the dynamic call provably a member
      // of the fixed actions table - nothing user-supplied can dispatch.
      const action = Object.hasOwn(actions, route) ? actions[route] : undefined;
      if (!action) {
        return json(res, 404, { error: `no route ${route}` });
      }
      let body = {};
      if (req.method === 'POST') {
        const raw = await readBody(req);
        if (raw.length) {
          try {
            body = JSON.parse(raw.toString());
          } catch {
            return json(res, 400, { error: 'invalid JSON body' });
          }
        }
      }
      const isFrame = route === 'GET /frame';
      if (isFrame) {
        if (frameBusy) return json(res, 429, { error: 'busy' });
        frameBusy = true;
      }
      try {
        const arg = req.method === 'POST'
          ? body
          : Object.fromEntries(new URLSearchParams(q === -1 ? '' : req.url.slice(q + 1)));
        const result = (await action(arg)) || { ok: true };
        if (result.frame && Buffer.isBuffer(result.frame.bytes)) {
          const format = result.frame.format === 'jpeg' ? 'jpeg' : 'png';
          res.writeHead(200, {
            'content-type': `image/${format}`,
            'x-frame-format': format,
            'content-length': result.frame.bytes.length,
          });
          return res.end(result.frame.bytes);
        }
        return json(res, 200, { ok: true, ...result });
      } finally {
        if (isFrame) frameBusy = false;
      }
    } catch (e) {
      let message = e.message || 'internal error';
      if (message.includes('-25211') || message.includes('assistive access')) {
        message =
          'the process running desktop-agent has no Accessibility permission - ' +
          'grant it in System Settings > Privacy & Security > Accessibility ' +
          '(to Terminal or whichever app runs the agent), then retry';
      } else if (message.includes('Screen Recording') || message.includes('screen capture')) {
        message +=
          ' - grant Screen Recording in System Settings > Privacy & Security';
      }
      const status = e.status || (e.code === 'ENOENT' ? 501 : 500);
      return json(res, status, { error: message });
    }
  });
}

if (require.main === module) {
  if (!SECRET) {
    console.error('DESKTOP_AGENT_SECRET is required');
    process.exit(1);
  }
  createServer().listen(PORT, BIND, () => {
    console.log(`desktop-agent on ${BIND}:${PORT} (platform ${PLATFORM}, data ${DATA_DIR})`);
  });
}

module.exports = { createServer, actions, appleQuote };
