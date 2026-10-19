// Starter apps for the Phone app studio. Hand-written, not generated:
// a small on-device model edits a good app far more reliably than it writes
// one from nothing, so every starter is a complete, working, offline,
// single-file mobile app the model only has to adapt. Each one:
//   * keeps its colours/radius/font in the studio:theme block (Design panel),
//   * saves its data in localStorage (the preview harness backs it),
//   * stays small (<~8 KB) so the whole file fits a 4k-token model window.

import { DEFAULT_TOKENS, themeBlockCss, type ThemeTokens } from "./theme";

export interface StudioTemplate {
  id: string;
  name: string;
  emoji: string;
  blurb: string;
  keywords: string[];
  html: string;
}

const BASE_CSS = `*{box-sizing:border-box;margin:0;padding:0}
html,body{min-height:100%}
body{font-family:var(--font);background:var(--bg);color:var(--text);-webkit-font-smoothing:antialiased;-webkit-tap-highlight-color:transparent}
.app{max-width:520px;margin:0 auto;min-height:100vh;display:flex;flex-direction:column;padding-bottom:env(safe-area-inset-bottom)}
header.top{padding:calc(20px + env(safe-area-inset-top)) 20px 6px}
header.top h1{font-size:26px;font-weight:800;letter-spacing:-.02em}
header.top p{color:var(--muted);font-size:14px;margin-top:2px}
main{flex:1;padding:12px 16px 110px;display:flex;flex-direction:column;gap:12px}
.card{background:var(--surface);border-radius:var(--radius);padding:16px;box-shadow:0 1px 2px rgba(0,0,0,.05),0 6px 20px rgba(0,0,0,.05)}
.btn{appearance:none;border:0;border-radius:calc(var(--radius) - 4px);background:var(--primary);color:var(--on-primary,#fff);font:600 15px var(--font);padding:12px 18px;min-height:44px;cursor:pointer}
.btn:active{transform:scale(.97)}
.btn.ghost{background:transparent;color:var(--primary);box-shadow:inset 0 0 0 1.5px var(--primary)}
input,select,textarea{width:100%;font:16px var(--font);color:var(--text);background:var(--bg);border:1.5px solid rgba(127,127,140,.25);border-radius:calc(var(--radius) - 6px);padding:12px 14px;min-height:44px}
input:focus,select:focus,textarea:focus{outline:none;border-color:var(--primary)}
.row{display:flex;align-items:center;gap:10px}
.muted{color:var(--muted)}
.empty{text-align:center;color:var(--muted);padding:36px 12px;font-size:14px}
.fab{position:fixed;right:max(20px,calc(50% - 240px));bottom:calc(24px + env(safe-area-inset-bottom));width:60px;height:60px;border-radius:50%;border:0;background:var(--primary);color:var(--on-primary,#fff);font-size:30px;box-shadow:0 8px 24px rgba(0,0,0,.2);cursor:pointer}
.sheet{position:fixed;inset:0;background:rgba(0,0,0,.35);display:none;align-items:flex-end;justify-content:center;z-index:10}
.sheet.open{display:flex}
.sheet>form{background:var(--surface);width:100%;max-width:520px;border-radius:var(--radius) var(--radius) 0 0;padding:20px 16px calc(20px + env(safe-area-inset-bottom));display:flex;flex-direction:column;gap:10px}
.sheet h2{font-size:18px}`;

function themeBlock(t: ThemeTokens): string {
  return themeBlockCss(t);
}

function page(o: { title: string; theme?: Partial<ThemeTokens>; css: string; body: string; script: string }): string {
  const t = { ...DEFAULT_TOKENS, ...(o.theme || {}) };
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="${t.primary}">
<title>${o.title}</title>
<style>
${themeBlock(t)}
${BASE_CSS}
${o.css.trim()}
</style>
</head>
<body>
${o.body.trim()}
<script>
const esc = s => String(s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const load = (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } };
const save = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} };
${o.script.trim()}
</script>
</body>
</html>
`;
}

const habits = page({
  title: "Habit Streaks",
  theme: { primary: "#f97316", bg: "#fff8f1", text: "#1f1a17", muted: "#7a6a5e" },
  css: `.habit{display:flex;flex-direction:column;gap:12px}
.habit .name{font-weight:700;font-size:17px;flex:1}
.streak{font-weight:700;color:var(--primary);font-size:14px}
.week{display:grid;grid-template-columns:repeat(7,1fr);gap:6px}
.day{display:flex;flex-direction:column;align-items:center;gap:4px;font-size:11px;color:var(--muted)}
.dot{width:36px;height:36px;border-radius:50%;border:2px solid rgba(127,127,140,.25);background:transparent;cursor:pointer;font-size:16px;color:var(--on-primary,#fff)}
.dot.on{background:var(--primary);border-color:var(--primary)}
.dot.today{box-shadow:0 0 0 3px rgba(249,115,22,.25)}
.del{border:0;background:none;color:var(--muted);font-size:18px;cursor:pointer;min-width:44px;min-height:44px}`,
  body: `<div class="app">
<header class="top"><h1>Habit Streaks</h1><p id="summary">Small steps, every day.</p></header>
<main id="list"></main>
<button class="fab" id="add" aria-label="Add habit">+</button>
<div class="sheet" id="sheet"><form id="form"><h2>New habit</h2>
<input id="name" placeholder="e.g. Read 10 pages" maxlength="40" required>
<button class="btn" type="submit">Add habit</button>
<button class="btn ghost" type="button" id="cancel">Cancel</button></form></div>
</div>`,
  script: `let habits = load("habits", [
  { id: 1, name: "Drink 8 glasses of water", done: [] },
  { id: 2, name: "Walk 5,000 steps", done: [] },
  { id: 3, name: "Read 10 pages", done: [] }
]);
const key = d => d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
const days = () => { const out = []; const now = new Date(); for (let i = 6; i >= 0; i--) { const d = new Date(now); d.setDate(now.getDate() - i); out.push(d); } return out; };
// Older versions saved UTC date keys (toISOString). Read both styles so existing streaks survive; new ticks always use the local key.
const utc = d => d.toISOString().slice(0, 10);
const has = (h, d) => h.done.includes(key(d)) || h.done.includes(utc(d));
function streak(h) { let n = 0; const d = new Date(); if (!has(h, d)) d.setDate(d.getDate() - 1); while (has(h, d)) { n++; d.setDate(d.getDate() - 1); } return n; }
function render() {
  const list = document.getElementById("list");
  if (!habits.length) { list.innerHTML = '<div class="empty">No habits yet. Tap + to start one.</div>'; }
  else list.innerHTML = habits.map(h => '<div class="card habit"><div class="row"><span class="name">' + esc(h.name) + '</span><span class="streak">🔥 ' + streak(h) + '</span><button class="del" data-del="' + h.id + '" aria-label="Delete">×</button></div><div class="week">' +
    days().map((d, i) => '<div class="day">' + "SMTWTFS"[d.getDay()] + '<button class="dot' + (has(h, d) ? " on" : "") + (i === 6 ? " today" : "") + '" data-h="' + h.id + '" data-d="' + key(d) + '" data-u="' + utc(d) + '">' + (has(h, d) ? "✓" : "") + '</button></div>').join("") + '</div></div>').join("");
  const doneToday = habits.filter(h => has(h, new Date())).length;
  document.getElementById("summary").textContent = habits.length ? doneToday + " of " + habits.length + " done today" : "Small steps, every day.";
  save("habits", habits);
}
document.getElementById("list").addEventListener("click", e => {
  const b = e.target.closest("button"); if (!b) return;
  if (b.dataset.del) { habits = habits.filter(h => h.id != b.dataset.del); return render(); }
  const h = habits.find(x => x.id == b.dataset.h); if (!h) return;
  const k = h.done.includes(b.dataset.d) ? b.dataset.d : h.done.includes(b.dataset.u) ? b.dataset.u : null;
  h.done = k ? h.done.filter(x => x !== k) : [...h.done, b.dataset.d];
  render();
});
const sheet = document.getElementById("sheet");
document.getElementById("add").onclick = () => { sheet.classList.add("open"); document.getElementById("name").focus(); };
document.getElementById("cancel").onclick = () => sheet.classList.remove("open");
document.getElementById("form").onsubmit = e => { e.preventDefault(); const n = document.getElementById("name"); habits.push({ id: Date.now(), name: n.value.trim(), done: [] }); n.value = ""; sheet.classList.remove("open"); render(); };
render();`,
});

const expenses = page({
  title: "Pocket Spend",
  theme: { primary: "#10b981", bg: "#f2fbf7", text: "#10201a", muted: "#5b7169" },
  css: `.total{background:var(--primary);color:var(--on-primary,#fff)}
.total .label{opacity:.85;font-size:13px}
.total .amt{font-size:34px;font-weight:800;letter-spacing:-.02em;margin-top:4px}
.chips{display:flex;gap:8px;overflow-x:auto;padding-bottom:2px}
.chip{flex:none;border:0;border-radius:999px;padding:8px 14px;background:var(--surface);color:var(--text);font:600 13px var(--font);min-height:36px;cursor:pointer}
.chip.on{background:var(--text);color:var(--bg)}
.item{display:flex;align-items:center;gap:12px;padding:12px 16px}
.item .ico{width:40px;height:40px;border-radius:12px;background:var(--bg);display:grid;place-items:center;font-size:20px}
.item .what{flex:1;min-width:0}
.item .what b{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.item .what small{color:var(--muted)}
.item .amt{font-weight:700}
.x{border:0;background:none;color:var(--muted);font-size:18px;min-width:36px;min-height:44px;cursor:pointer}`,
  body: `<div class="app">
<header class="top"><h1>Pocket Spend</h1><p>Where did the money go?</p></header>
<main>
<div class="card total"><div class="label" id="month"></div><div class="amt" id="total">₹0</div></div>
<div class="chips" id="chips"></div>
<div class="card" style="padding:0" id="list"></div>
</main>
<button class="fab" id="add" aria-label="Add expense">+</button>
<div class="sheet" id="sheet"><form id="form"><h2>Add expense</h2>
<input id="amount" type="number" inputmode="decimal" min="0" step="0.01" placeholder="Amount (₹)" required>
<input id="note" placeholder="What was it for?" maxlength="40">
<select id="cat"></select>
<button class="btn" type="submit">Save</button>
<button class="btn ghost" type="button" id="cancel">Cancel</button></form></div>
</div>`,
  script: `const CATS = { Food: "🍛", Travel: "🛺", Shopping: "🛍️", Bills: "💡", Fun: "🎬", Other: "📦" };
const CURRENCY = "₹";
let items = load("expenses", [
  { id: 1, amount: 180, note: "Lunch thali", cat: "Food", at: Date.now() - 3600e3 },
  { id: 2, amount: 90, note: "Auto to office", cat: "Travel", at: Date.now() - 86400e3 },
  { id: 3, amount: 1299, note: "Electricity bill", cat: "Bills", at: Date.now() - 2 * 86400e3 }
]);
let filter = "All";
const money = n => CURRENCY + Number(n).toLocaleString("en-IN", { maximumFractionDigits: 2 });
function render() {
  const now = new Date();
  const month = items.filter(i => { const d = new Date(i.at); return d.getMonth() === now.getMonth() && d.getFullYear() === now.getFullYear(); });
  document.getElementById("month").textContent = "Spent in " + now.toLocaleString("en", { month: "long" });
  document.getElementById("total").textContent = money(month.reduce((s, i) => s + Number(i.amount), 0));
  document.getElementById("chips").innerHTML = ["All", ...Object.keys(CATS)].map(c => '<button class="chip' + (c === filter ? " on" : "") + '" data-c="' + c + '">' + (CATS[c] || "") + " " + c + '</button>').join("");
  const shown = items.filter(i => filter === "All" || i.cat === filter).sort((a, b) => b.at - a.at);
  document.getElementById("list").innerHTML = shown.length ? shown.map(i => '<div class="item"><div class="ico">' + (CATS[i.cat] || "📦") + '</div><div class="what"><b>' + esc(i.note || i.cat) + '</b><small>' + new Date(i.at).toLocaleDateString() + " · " + i.cat + '</small></div><div class="amt">' + money(i.amount) + '</div><button class="x" data-del="' + i.id + '" aria-label="Delete">×</button></div>').join("") : '<div class="empty">Nothing here yet.</div>';
  save("expenses", items);
}
document.getElementById("chips").onclick = e => { const b = e.target.closest("[data-c]"); if (b) { filter = b.dataset.c; render(); } };
document.getElementById("list").onclick = e => { const b = e.target.closest("[data-del]"); if (b) { items = items.filter(i => i.id != b.dataset.del); render(); } };
document.getElementById("cat").innerHTML = Object.keys(CATS).map(c => "<option>" + c + "</option>").join("");
const sheet = document.getElementById("sheet");
document.getElementById("add").onclick = () => { sheet.classList.add("open"); document.getElementById("amount").focus(); };
document.getElementById("cancel").onclick = () => sheet.classList.remove("open");
document.getElementById("form").onsubmit = e => { e.preventDefault(); items.push({ id: Date.now(), amount: parseFloat(document.getElementById("amount").value), note: document.getElementById("note").value.trim(), cat: document.getElementById("cat").value, at: Date.now() }); e.target.reset(); sheet.classList.remove("open"); render(); };
render();`,
});

const timer = page({
  title: "Focus Timer",
  theme: { primary: "#8b7bff", bg: "#0e0f16", surface: "#191b26", text: "#ecebf5", muted: "#9a9ab0", radius: "20px" },
  css: `main{align-items:center;justify-content:center;gap:24px}
.modes{display:flex;gap:8px;background:var(--surface);padding:6px;border-radius:999px}
.mode{border:0;background:transparent;color:var(--muted);font:600 14px var(--font);padding:10px 16px;border-radius:999px;min-height:40px;cursor:pointer}
.mode.on{background:var(--primary);color:var(--on-primary,#fff)}
.ring{position:relative;width:260px;height:260px}
.ring svg{transform:rotate(-90deg)}
.ring .time{position:absolute;inset:0;display:grid;place-items:center;font-size:56px;font-weight:800;font-variant-numeric:tabular-nums;letter-spacing:-.03em}
.controls{display:flex;gap:12px}
.controls .btn{min-width:120px}
.stats{color:var(--muted);font-size:14px}`,
  body: `<div class="app">
<header class="top"><h1>Focus Timer</h1><p>One thing at a time.</p></header>
<main>
<div class="modes" id="modes"><button class="mode on" data-m="25">Focus</button><button class="mode" data-m="5">Short break</button><button class="mode" data-m="15">Long break</button></div>
<div class="ring"><svg width="260" height="260"><circle cx="130" cy="130" r="118" stroke="rgba(127,127,140,.2)" stroke-width="12" fill="none"/><circle id="arc" cx="130" cy="130" r="118" stroke="var(--primary)" stroke-width="12" fill="none" stroke-linecap="round"/></svg><div class="time" id="time">25:00</div></div>
<div class="controls"><button class="btn" id="go">Start</button><button class="btn ghost" id="reset">Reset</button></div>
<div class="stats" id="stats"></div>
</main>
</div>`,
  script: `const C = 2 * Math.PI * 118;
const arc = document.getElementById("arc");
arc.style.strokeDasharray = C;
let minutes = 25, left = 25 * 60, running = null, endAt = 0;
let log = load("focusLog", {});
const today = () => { const d = new Date(); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
function draw() {
  document.getElementById("time").textContent = String(Math.floor(left / 60)).padStart(2, "0") + ":" + String(left % 60).padStart(2, "0");
  arc.style.strokeDashoffset = C * (1 - left / (minutes * 60));
  const n = log[today()] || 0;
  document.getElementById("stats").textContent = n ? n + " focus session" + (n > 1 ? "s" : "") + " today" : "No sessions yet today";
  document.getElementById("go").textContent = running ? "Pause" : left < minutes * 60 ? "Resume" : "Start";
}
// Wall-clock based: the countdown is end time minus now, so it neither drifts nor stalls when the tab is in the background.
function stop() { if (running) left = Math.max(0, Math.ceil((endAt - Date.now()) / 1000)); clearInterval(running); running = null; }
function tick() {
  left = Math.max(0, Math.ceil((endAt - Date.now()) / 1000));
  if (left <= 0) {
    clearInterval(running); running = null;
    if (minutes === 25) { log[today()] = (log[today()] || 0) + 1; save("focusLog", log); }
    if (navigator.vibrate) navigator.vibrate([200, 100, 200]);
    left = minutes * 60;
    draw();
    alert(minutes === 25 ? "Focus session done — take a break!" : "Break over — back to it!");
    return;
  }
  draw();
}
document.getElementById("go").onclick = () => { if (running) stop(); else { endAt = Date.now() + left * 1000; running = setInterval(tick, 250); } draw(); };
document.getElementById("reset").onclick = () => { stop(); left = minutes * 60; draw(); };
document.getElementById("modes").onclick = e => {
  const b = e.target.closest("[data-m]"); if (!b) return;
  document.querySelectorAll(".mode").forEach(x => x.classList.toggle("on", x === b));
  stop(); minutes = Number(b.dataset.m); left = minutes * 60; draw();
};
draw();`,
});

const shop = page({
  title: "Sweet Crumbs Bakery",
  theme: { primary: "#e11d48", bg: "#fff5f7", text: "#23121a", muted: "#7d5e69" },
  css: `.hero{background:linear-gradient(135deg,var(--primary),#fb923c);color:#fff}
.hero b{font-size:18px}
.hero p{opacity:.9;font-size:14px;margin-top:4px}
.item{display:flex;gap:14px;align-items:center}
.item .pic{width:64px;height:64px;border-radius:calc(var(--radius) - 4px);background:var(--bg);display:grid;place-items:center;font-size:34px;flex:none}
.item .info{flex:1;min-width:0}
.item .info b{display:block}
.item .info small{color:var(--muted);font-size:13px}
.item .price{font-weight:700;margin-top:4px}
.qty{display:flex;align-items:center;gap:8px}
.qty button{width:36px;height:36px;border-radius:50%;border:0;background:var(--bg);color:var(--primary);font-size:20px;font-weight:700;cursor:pointer}
.qty span{min-width:18px;text-align:center;font-weight:700}
.cart{position:fixed;left:16px;right:16px;bottom:calc(16px + env(safe-area-inset-bottom));max-width:488px;margin:0 auto;display:none;justify-content:space-between;align-items:center;box-shadow:0 10px 30px rgba(0,0,0,.25)}
.cart.show{display:flex}`,
  body: `<div class="app">
<header class="top"><h1>Sweet Crumbs</h1><p>Fresh from the oven, every morning.</p></header>
<main>
<div class="card hero"><b>🎉 Free delivery over ₹499</b><p>Order before 6 pm for same-day delivery.</p></div>
<div id="menu" style="display:flex;flex-direction:column;gap:12px"></div>
</main>
<button class="btn cart" id="cart"><span id="count"></span><span id="sum"></span></button>
</div>`,
  script: `// Your WhatsApp number with country code, digits only.
const WHATSAPP = "919999999999";
const SHOP = "Sweet Crumbs Bakery";
const MENU = [
  { id: "cake", pic: "🎂", name: "Chocolate truffle cake", note: "500 g · eggless", price: 650 },
  { id: "cup", pic: "🧁", name: "Red velvet cupcakes", note: "Box of 4", price: 240 },
  { id: "bread", pic: "🍞", name: "Sourdough loaf", note: "Baked today", price: 180 },
  { id: "cookie", pic: "🍪", name: "Butter cookies", note: "250 g jar", price: 199 },
  { id: "croissant", pic: "🥐", name: "Butter croissant", note: "Flaky, 2 pcs", price: 150 }
];
let cart = load("cart", {});
const money = n => "₹" + n.toLocaleString("en-IN");
function render() {
  document.getElementById("menu").innerHTML = MENU.map(m => '<div class="card item"><div class="pic">' + m.pic + '</div><div class="info"><b>' + esc(m.name) + '</b><small>' + esc(m.note) + '</small><div class="price">' + money(m.price) + '</div></div><div class="qty">' +
    (cart[m.id] ? '<button data-dec="' + m.id + '" aria-label="Remove one">−</button><span>' + cart[m.id] + '</span>' : "") + '<button data-inc="' + m.id + '" aria-label="Add one">+</button></div></div>').join("");
  const count = Object.values(cart).reduce((a, b) => a + b, 0);
  const sum = MENU.reduce((s, m) => s + (cart[m.id] || 0) * m.price, 0);
  document.getElementById("cart").classList.toggle("show", count > 0);
  document.getElementById("count").textContent = count + " item" + (count > 1 ? "s" : "") + " · Order on WhatsApp";
  document.getElementById("sum").textContent = money(sum);
  save("cart", cart);
}
document.getElementById("menu").onclick = e => {
  const b = e.target.closest("button"); if (!b) return;
  if (b.dataset.inc) cart[b.dataset.inc] = (cart[b.dataset.inc] || 0) + 1;
  if (b.dataset.dec) { cart[b.dataset.dec]--; if (!cart[b.dataset.dec]) delete cart[b.dataset.dec]; }
  render();
};
document.getElementById("cart").onclick = () => {
  const lines = MENU.filter(m => cart[m.id]).map(m => "• " + cart[m.id] + " × " + m.name + " — " + money(cart[m.id] * m.price));
  const sum = MENU.reduce((s, m) => s + (cart[m.id] || 0) * m.price, 0);
  const text = "Hi " + SHOP + "! I'd like to order:\\n" + lines.join("\\n") + "\\nTotal: " + money(sum);
  window.open("https://wa.me/" + WHATSAPP + "?text=" + encodeURIComponent(text), "_blank");
};
render();`,
});

const flashcards = page({
  title: "Flashcards",
  theme: { primary: "#0ea5e9", bg: "#f1f8fc", text: "#0f1c24", muted: "#5a6d78" },
  css: `.deck{perspective:1000px;height:300px}
.flip{position:relative;width:100%;height:100%;transition:transform .45s;transform-style:preserve-3d;cursor:pointer}
.flip.back{transform:rotateY(180deg)}
.face{position:absolute;inset:0;backface-visibility:hidden;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:24px;font-size:24px;font-weight:700}
.face small{font-size:12px;font-weight:600;color:var(--muted);text-transform:uppercase;letter-spacing:.08em;margin-bottom:10px}
.face.b{transform:rotateY(180deg);background:var(--primary);color:var(--on-primary,#fff)}
.face.b small{color:rgba(255,255,255,.8)}
.grade{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.bar{height:8px;border-radius:99px;background:rgba(127,127,140,.2);overflow:hidden}
.bar i{display:block;height:100%;background:var(--primary);transition:width .3s}`,
  body: `<div class="app">
<header class="top"><h1>Flashcards</h1><p id="progress">Tap the card to flip it.</p></header>
<main>
<div class="bar"><i id="bar" style="width:0"></i></div>
<div class="deck"><div class="flip" id="flip"><div class="card face"><small>Question</small><span id="q"></span></div><div class="card face b"><small>Answer</small><span id="a"></span></div></div></div>
<div class="grade"><button class="btn ghost" id="again">Again</button><button class="btn" id="know">I knew it</button></div>
</main>
<button class="fab" id="add" aria-label="Add card">+</button>
<div class="sheet" id="sheet"><form id="form"><h2>New card</h2>
<input id="nq" placeholder="Question" required maxlength="120"><input id="na" placeholder="Answer" required maxlength="120">
<button class="btn" type="submit">Add card</button><button class="btn ghost" type="button" id="cancel">Cancel</button></form></div>
</div>`,
  script: `let cards = load("cards", [
  { q: "Capital of Japan?", a: "Tokyo", known: false },
  { q: "H₂O is…", a: "Water", known: false },
  { q: "7 × 8 = ?", a: "56", known: false },
  { q: "Largest planet?", a: "Jupiter", known: false }
]);
let i = 0;
const flip = document.getElementById("flip");
function next() { const todo = cards.map((c, n) => n).filter(n => !cards[n].known); i = todo.length ? todo[Math.floor(Math.random() * todo.length)] : -1; render(); }
function render() {
  flip.classList.remove("back");
  const known = cards.filter(c => c.known).length;
  document.getElementById("bar").style.width = (cards.length ? known / cards.length * 100 : 0) + "%";
  document.getElementById("progress").textContent = known + " of " + cards.length + " learned";
  if (i < 0) { document.getElementById("q").textContent = "🎉 All learned!"; document.getElementById("a").textContent = "Tap “Again” to restart"; }
  else { document.getElementById("q").textContent = cards[i].q; document.getElementById("a").textContent = cards[i].a; }
  save("cards", cards);
}
flip.onclick = () => flip.classList.toggle("back");
document.getElementById("know").onclick = () => { if (i >= 0) cards[i].known = true; next(); };
document.getElementById("again").onclick = () => { if (i < 0) cards.forEach(c => c.known = false); next(); };
const sheet = document.getElementById("sheet");
document.getElementById("add").onclick = () => sheet.classList.add("open");
document.getElementById("cancel").onclick = () => sheet.classList.remove("open");
document.getElementById("form").onsubmit = e => { e.preventDefault(); cards.push({ q: document.getElementById("nq").value.trim(), a: document.getElementById("na").value.trim(), known: false }); e.target.reset(); sheet.classList.remove("open"); next(); };
next();`,
});

const tasks = page({
  title: "Tasks",
  theme: { primary: "#6d5dfc" },
  css: `.add{display:flex;gap:8px}
.add input{flex:1}
.tabs{display:flex;gap:6px}
.tab{flex:1;border:0;background:var(--surface);color:var(--muted);font:600 14px var(--font);border-radius:calc(var(--radius) - 6px);min-height:40px;cursor:pointer}
.tab.on{background:var(--primary);color:var(--on-primary,#fff)}
.task{display:flex;align-items:center;gap:12px;padding:10px 12px 10px 16px}
.task label{flex:1;display:flex;align-items:center;gap:12px;min-height:44px;cursor:pointer}
.task input{width:22px;height:22px;min-height:0;accent-color:var(--primary)}
.task.done span{text-decoration:line-through;color:var(--muted)}
.x{border:0;background:none;color:var(--muted);font-size:18px;min-width:44px;min-height:44px;cursor:pointer}`,
  body: `<div class="app">
<header class="top"><h1>Tasks</h1><p id="left"></p></header>
<main>
<form class="add" id="form"><input id="text" placeholder="Add a task…" maxlength="80" required><button class="btn" type="submit">Add</button></form>
<div class="tabs" id="tabs"><button class="tab on" data-f="all">All</button><button class="tab" data-f="open">To do</button><button class="tab" data-f="done">Done</button></div>
<div class="card" style="padding:4px 0" id="list"></div>
</main>
</div>`,
  script: `let tasks = load("tasks", [
  { id: 1, text: "Plan the week", done: true },
  { id: 2, text: "Call the plumber", done: false },
  { id: 3, text: "Buy groceries", done: false }
]);
let f = "all";
function render() {
  const shown = tasks.filter(t => f === "all" || (f === "done" ? t.done : !t.done));
  document.getElementById("list").innerHTML = shown.length ? shown.map(t => '<div class="task' + (t.done ? " done" : "") + '"><label><input type="checkbox" data-id="' + t.id + '"' + (t.done ? " checked" : "") + '><span>' + esc(t.text) + '</span></label><button class="x" data-del="' + t.id + '" aria-label="Delete">×</button></div>').join("") : '<div class="empty">Nothing here.</div>';
  const open = tasks.filter(t => !t.done).length;
  document.getElementById("left").textContent = open ? open + " to do" : "All done! 🎉";
  save("tasks", tasks);
}
document.getElementById("form").onsubmit = e => { e.preventDefault(); const i = document.getElementById("text"); tasks.unshift({ id: Date.now(), text: i.value.trim(), done: false }); i.value = ""; render(); };
document.getElementById("list").onchange = e => { const t = tasks.find(x => x.id == e.target.dataset.id); if (t) { t.done = e.target.checked; render(); } };
document.getElementById("list").onclick = e => { const b = e.target.closest("[data-del]"); if (b) { tasks = tasks.filter(t => t.id != b.dataset.del); render(); } };
document.getElementById("tabs").onclick = e => { const b = e.target.closest("[data-f]"); if (!b) return; f = b.dataset.f; document.querySelectorAll(".tab").forEach(x => x.classList.toggle("on", x === b)); render(); };
render();`,
});

// The hero phone on the studio landing page opens this: a bright VegaDūta
// demo that shows what a no-code app with agents/workflows/AI feels like.
// Deliberately NOT in TEMPLATES - the starter grid stays six.
const vegadutaSample = page({
  title: "VegaDuta Launchpad",
  theme: { primary: "#f4521e", bg: "#fff6ee", surface: "#ffffff", text: "#17203f", muted: "#6e7390", radius: "20px" },
  css: `.brand{display:flex;align-items:center;gap:12px}
.logo{width:42px;height:42px;border-radius:14px;background:linear-gradient(135deg,#ff8a3d,#f43f5e);display:grid;place-items:center;color:#fff;font-weight:800;font-size:22px;box-shadow:0 8px 18px rgba(244,63,94,.3)}
.brand h1{font-size:22px}
.hero{background:linear-gradient(130deg,#1b2350 0%,#232a63 55%,#f4521e 150%);color:#fff;position:relative;overflow:hidden}
.hero::after{content:"";position:absolute;right:-40px;top:-40px;width:150px;height:150px;border-radius:50%;background:radial-gradient(circle,rgba(255,138,61,.5),transparent 70%)}
.eyebrow{font-size:11px;font-weight:700;letter-spacing:.12em;color:#ffb08a;text-transform:uppercase}
.hero b{display:block;font-size:20px;font-weight:800;letter-spacing:-.02em;margin:6px 0 4px}
.hero p{font-size:13px;opacity:.85}
.actions{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.act{border:1.5px solid rgba(244,82,30,.18);background:var(--surface);border-radius:16px;padding:12px 6px;display:flex;flex-direction:column;align-items:center;gap:6px;font:600 12px var(--font);color:var(--text);cursor:pointer;min-height:88px;justify-content:center;text-align:center}
.act .ico{width:38px;height:38px;border-radius:12px;background:#fff0e6;display:grid;place-items:center;font-size:19px}
.act.on{border-color:var(--primary);background:linear-gradient(160deg,#fff3ea,#ffe4d3);box-shadow:0 6px 16px rgba(244,82,30,.18)}
.result{border:2px solid rgba(244,82,30,.3);background:linear-gradient(160deg,#fff,#fff7f0)}
.result .out{font-size:14px;font-weight:600;margin-top:6px}
.result .sub{font-size:12px;color:var(--muted);margin-top:4px}
.feed .row{padding:10px 0;border-top:1px solid rgba(127,127,140,.15);justify-content:space-between}
.feed .row:first-child{border-top:0}
.feed b{font-size:13px}
.feed small{color:var(--muted);display:block;font-size:11px;margin-top:2px}
.st{flex:none;font-size:11px;font-weight:700;padding:3px 9px;border-radius:999px;background:#e8f9ee;color:#15803d}
.st.busy{background:#fff0e2;color:#c2410c}
.bnav{position:fixed;left:16px;right:16px;bottom:calc(14px + env(safe-area-inset-bottom));max-width:488px;margin:0 auto;background:rgba(23,32,63,.93);backdrop-filter:blur(8px);border-radius:20px;display:flex;padding:6px;box-shadow:0 12px 30px rgba(23,32,63,.35);z-index:5}
.bnav button{flex:1;border:0;background:transparent;color:#9aa2c8;font:600 12px var(--font);padding:9px 4px;border-radius:14px;cursor:pointer;display:flex;flex-direction:column;align-items:center;gap:2px}
.bnav button.on{background:linear-gradient(135deg,#ff8a3d,#f4521e);color:#fff}`,
  body: `<div class="app">
<header class="top"><div class="brand"><div class="logo">V</div><div><h1>VegaDūta</h1><p id="hello">Good morning, maker 👋</p></div></div></header>
<main>
<div class="card hero"><span class="eyebrow">Your launchpad</span><b>What will you make possible today?</b><p>Agents answer, workflows act — you just tap.</p></div>
<div class="actions" id="actions"></div>
<div class="card result"><span class="eyebrow" id="rtag">Welcome</span><div class="out" id="rout">Tap a card above to see what a VegaDūta app can do.</div><div class="sub" id="rsub">Real agents, workflows and AI — wired in with no code.</div></div>
<div class="card feed"><span class="eyebrow" style="color:var(--muted)">Happening now</span><div id="feed"></div></div>
</main>
<nav class="bnav" id="nav"></nav>
</div>`,
  script: `const ACTS = [
  { k: "agent", ico: "🤖", label: "Ask an agent", tag: "Agent · Support scout", out: "“What's our refund policy?” — answered in 2s", sub: "The agent read your docs and replied in your brand voice." },
  { k: "flow", ico: "⚡", label: "Run a workflow", tag: "Workflow · Morning brief", out: "3-step job finished: brief ready", sub: "Gathered leads, drafted the summary, filed it away." },
  { k: "create", ico: "✨", label: "Create with AI", tag: "AI · Idea sketch", out: "“A loyalty card for my café” — drafted", sub: "Screens, buttons and logic sketched. Refine by tapping." }
];
const FEED = [
  { t: "Research brief is ready", s: "Done", busy: 0, d: "Scout agent · 9:41" },
  { t: "Lead follow-up sent to 12 contacts", s: "Done", busy: 0, d: "Outreach workflow · 9:12" },
  { t: "Weekly report is running", s: "Running", busy: 1, d: "Reporter workflow · now" }
];
const NAV = [["🏠", "Home"], ["🤖", "Agents"], ["⚡", "Flows"]];
const q = id => document.getElementById(id);
q("actions").innerHTML = ACTS.map(a => '<button class="act" data-k="' + a.k + '"><span class="ico">' + a.ico + "</span>" + a.label + "</button>").join("");
q("feed").innerHTML = FEED.map(f => '<div class="row"><div style="flex:1;min-width:0"><b>' + f.t + "</b><small>" + f.d + '</small></div><span class="st' + (f.busy ? " busy" : "") + '">' + f.s + "</span></div>").join("");
q("nav").innerHTML = NAV.map((n, i) => '<button class="' + (i ? "" : "on") + '" data-n="' + n[1] + '">' + n[0] + "<span>" + n[1] + "</span></button>").join("");
q("actions").onclick = e => {
  const b = e.target.closest("[data-k]"); if (!b) return;
  const a = ACTS.find(x => x.k === b.dataset.k);
  document.querySelectorAll(".act").forEach(x => x.classList.toggle("on", x === b));
  q("rtag").textContent = a.tag;
  q("rout").textContent = a.out;
  q("rsub").textContent = a.sub;
};
q("nav").onclick = e => {
  const b = e.target.closest("[data-n]"); if (!b) return;
  document.querySelectorAll(".bnav button").forEach(x => x.classList.toggle("on", x === b));
  q("hello").textContent = b.dataset.n === "Home" ? "Good morning, maker 👋" : "Your " + b.dataset.n.toLowerCase() + ", one tap away";
};`,
});

export const VEGADUTA_SAMPLE_APP: StudioTemplate = {
  id: "vegaduta-launchpad",
  name: "VegaDuta Launchpad",
  emoji: "🚀",
  blurb: "AI agents and workflows in your pocket",
  keywords: ["vegaduta", "agent", "workflow", "ai", "launchpad"],
  html: vegadutaSample,
};

/** The skeleton a from-scratch generation fills in. */
export const BLANK_APP = page({
  title: "My App",
  css: `/* app styles */`,
  body: `<div class="app">
<header class="top"><h1>My App</h1><p>Subtitle</p></header>
<main id="main">
<div class="card">Content</div>
</main>
</div>`,
  script: `// app logic`,
});

export const TEMPLATES: StudioTemplate[] = [
  { id: "habits", name: "Habit tracker", emoji: "🔥", blurb: "Daily check-ins with streaks", keywords: ["habit", "streak", "daily", "routine", "goal", "water", "gym", "workout", "meditat"], html: habits },
  { id: "expenses", name: "Expense tracker", emoji: "💸", blurb: "Monthly spend by category", keywords: ["expense", "spend", "budget", "money", "finance", "cost", "bill", "split", "account", "ledger", "kharcha"], html: expenses },
  { id: "shop", name: "Shop & WhatsApp orders", emoji: "🧁", blurb: "Menu, cart, order on WhatsApp", keywords: ["shop", "store", "menu", "order", "bakery", "restaurant", "cafe", "cart", "product", "catalog", "whatsapp", "business", "sell", "boutique", "kirana"], html: shop },
  { id: "timer", name: "Focus timer", emoji: "⏱️", blurb: "Pomodoro with a progress ring", keywords: ["timer", "pomodoro", "focus", "countdown", "stopwatch", "study", "clock"], html: timer },
  { id: "flashcards", name: "Flashcards", emoji: "🧠", blurb: "Flip cards, track what you know", keywords: ["flashcard", "quiz", "learn", "study", "vocab", "exam", "revision", "language", "card"], html: flashcards },
  { id: "tasks", name: "To-do list", emoji: "✅", blurb: "Tasks with filters", keywords: ["todo", "to-do", "task", "checklist", "list", "chore", "shopping list", "grocery"], html: tasks },
];

/** Generic words that appear in many unrelated app ideas: they only count a
 * little, so on their own they never pick a starter. */
const WEAK_KEYWORDS = new Set([
  "bill", "account", "cost", "money", "list", "card", "order", "business", "study", "daily", "goal", "clock", "learn", "language", "product", "sell", "focus", "split", "task",
]);
/** A description that names a different kind of app blocks a starter unless a
 * strong keyword also matched. */
const OTHER_INTENT = /(calculator|converter|game|chat|weather|calendar|notes?|map|player|generator|translator|planner)/;
const MIN_SCORE = 2;

/** Best starter for a description, or null when nothing fits confidently
 * (then the studio writes the app from the blank skeleton). */
export function matchTemplate(description: string): StudioTemplate | null {
  const d = description.toLowerCase();
  let best: StudioTemplate | null = null;
  let bestScore = 0;
  let bestStrong = 0;
  for (const t of TEMPLATES) {
    let score = 0;
    let strong = 0;
    for (const k of t.keywords) {
      const re = new RegExp("(^|[^a-z])" + k.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
      if (!re.test(d)) continue;
      if (WEAK_KEYWORDS.has(k)) score += 1;
      else {
        score += 2;
        strong += 1;
      }
    }
    if (score > bestScore) {
      best = t;
      bestScore = score;
      bestStrong = strong;
    }
  }
  if (!best || bestScore < MIN_SCORE) return null;
  if (bestStrong === 0 && OTHER_INTENT.test(d)) return null;
  return best;
}
