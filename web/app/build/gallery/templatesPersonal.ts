// Gallery templates, part 2: link-in-bio, dashboard, quiz, expense tracker.
// Same shape as templatesBusiness.ts: complete offline single-file apps.

import { galleryPage } from "./shell";

export const linkInBioHtml = galleryPage({
  title: "Link in Bio",
  theme: { primary: "#e11d48", bg: "#fff5f7", text: "#23121a", muted: "#7d5e69", radius: "999px" },
  css: String.raw`.hero{text-align:center;padding:calc(30px + env(safe-area-inset-top)) 20px 8px}
.pic{width:92px;height:92px;border-radius:50%;margin:0 auto 12px;background:var(--primary);color:var(--on-primary,#fff);display:grid;place-items:center;font-size:38px;font-weight:800}
.hero h1{font-size:22px}
.lk{display:flex;align-items:center;gap:12px;padding:16px 20px;text-decoration:none;color:var(--text);font-weight:700}
.lk .em{font-size:22px}
.lk small{margin-left:auto;color:var(--muted);font-weight:600}
.share{text-align:center;padding:8px}`,
  body: String.raw`<div class="app">
<section class="hero"><div class="pic" id="pic"></div><h1 id="nm"></h1><p class="muted" id="bio"></p></section>
<main id="links"></main>
<div class="share"><button class="btn ghost" id="copy">Copy my page link</button></div>
<div class="toast" id="toast"></div></div>`,
  script: String.raw`const CONFIG = {
  name: "Rhea Kapoor", bio: "Baker · Bengaluru · new recipes every Friday 🍰",
  links: [
    { t: "Order a cake", e: "🎂", u: "https://wa.me/910000000000" },
    { t: "This week's menu", e: "📋", u: "https://example.com/menu" },
    { t: "Watch on video", e: "🎬", u: "https://example.com/videos" },
    { t: "Email me", e: "✉️", u: "mailto:hello@example.com" }
  ]
};
document.title = CONFIG.name;
$("nm").textContent = CONFIG.name; $("bio").textContent = CONFIG.bio; $("pic").textContent = CONFIG.name.charAt(0).toUpperCase();
let clicks = load("clicks", {});
function render() {
  $("links").innerHTML = CONFIG.links.map((l, i) => '<a class="card lk" href="' + esc(l.u) + '" target="_blank" rel="noopener" data-i="' + i + '"><span class="em">' + esc(l.e) + '</span><span>' + esc(l.t) + '</span><small>' + (clicks[i] || 0) + ' taps</small></a>').join("");
}
$("links").addEventListener("click", e => { const a = e.target.closest("[data-i]"); if (!a) return; clicks[a.dataset.i] = (clicks[a.dataset.i] || 0) + 1; save("clicks", clicks); render(); });
if (location.protocol === "about:" || location.href === "about:srcdoc") $("copy").parentNode.style.display = "none";
$("copy").onclick = () => {
  const href = location.href;
  if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(href).then(() => toast("Link copied"), () => toast("Copy not available here"));
  else toast("Copy not available here");
};
render();`,
});

export const dashboardHtml = galleryPage({
  title: "Sales Dashboard",
  theme: { primary: "#6d5dfc", bg: "#f6f6fb", text: "#17171f", muted: "#6b6b7b" },
  css: String.raw`.kpis{display:grid;grid-template-columns:repeat(2,1fr);gap:12px}
.kpi .lb{font-size:12px;color:var(--muted);font-weight:600}
.kpi .v{font-size:24px;font-weight:800;margin-top:2px}
.kpi .d{font-size:12px;font-weight:700}
.up{color:#16a34a}.down{color:#dc2626}
.chart{width:100%;height:auto;display:block}
.bar{fill:var(--primary);opacity:.85}
.bar:hover{opacity:1}
.axis{fill:var(--muted);font-size:9px}`,
  body: String.raw`<div class="app wide">
<header class="top"><h1>Sales</h1><p id="range-label"></p></header>
<main>
<div class="chips" id="range"><button class="chip" data-d="7">7 days</button><button class="chip" data-d="14">14 days</button><button class="chip" data-d="30">30 days</button></div>
<div class="kpis" id="kpis"></div>
<div class="card"><b>Revenue per day</b><svg class="chart" id="chart" viewBox="0 0 300 180" preserveAspectRatio="xMidYMid meet"></svg></div>
<form class="card row" id="form"><input id="amt" type="number" min="0" step="1" placeholder="Add today's sales (₹)" required><button class="btn" type="submit">Add</button></form>
<button class="btn ghost" id="reset">Reset sample data</button>
</main></div>`,
  script: String.raw`const CURRENCY = "₹";
function sample() {
  const out = []; let seed = 7;
  const rnd = () => { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; };
  for (let i = 29; i >= 0; i--) { const d = new Date(); d.setDate(d.getDate() - i); out.push({ day: ymd(d), rev: Math.round(4000 + rnd() * 6000), orders: Math.round(12 + rnd() * 30) }); }
  return out;
}
let data = load("sales", null) || sample(), span = load("span", 14);
const fmt = n => CURRENCY + Math.round(n).toLocaleString("en-IN");
const sum = a => a.reduce((x, y) => x + y, 0);
function render() {
  save("sales", data); save("span", span);
  document.querySelectorAll("#range .chip").forEach(c => c.classList.toggle("on", Number(c.dataset.d) === span));
  const cur = data.slice(-span), prev = data.slice(-span * 2, -span);
  const rev = sum(cur.map(x => x.rev)), orders = sum(cur.map(x => x.orders));
  const pr = sum(prev.map(x => x.rev)), delta = pr ? ((rev - pr) / pr) * 100 : null;
  const dl = delta === null ? "" : '<div class="d ' + (delta >= 0 ? "up" : "down") + '">' + (delta >= 0 ? "▲ " : "▼ ") + Math.abs(delta).toFixed(1) + '% vs previous</div>';
  $("range-label").textContent = "Last " + span + " days";
  $("kpis").innerHTML = [["Revenue", fmt(rev), dl], ["Orders", String(orders), ""], ["Avg order", fmt(orders ? rev / orders : 0), ""], ["Best day", fmt(Math.max.apply(null, cur.map(x => x.rev).concat([0]))), ""]]
    .map(k => '<div class="card kpi"><div class="lb">' + k[0] + '</div><div class="v">' + k[1] + '</div>' + k[2] + '</div>').join("");
  const max = Math.max.apply(null, cur.map(x => x.rev).concat([1])), w = 300 / cur.length;
  $("chart").innerHTML = cur.map((x, i) => { const h = (x.rev / max) * 150; return '<rect class="bar" x="' + (i * w + 1) + '" y="' + (165 - h) + '" width="' + Math.max(1, w - 2) + '" height="' + h + '" rx="2"><title>' + esc(x.day) + ': ' + fmt(x.rev) + '</title></rect>'; }).join("") +
    '<text class="axis" x="0" y="178">' + esc(cur[0].day.slice(5)) + '</text><text class="axis" x="300" y="178" text-anchor="end">' + esc(cur[cur.length - 1].day.slice(5)) + '</text>';
}
$("range").onclick = e => { const b = e.target.closest("[data-d]"); if (b) { span = Number(b.dataset.d); render(); } };
$("form").onsubmit = e => { e.preventDefault(); const v = Number($("amt").value); if (!(v >= 0)) return; const t = ymd(), last = data[data.length - 1];
  if (last && last.day === t) { last.rev += v; last.orders += 1; } else data.push({ day: t, rev: v, orders: 1 });
  $("amt").value = ""; render(); };
$("reset").onclick = () => { if (!confirm("Replace all your sales with new sample data? This cannot be undone.")) return; data = sample(); render(); };
render();`,
});

export const quizHtml = galleryPage({
  title: "Quiz",
  theme: { primary: "#f97316", bg: "#fff8f1", text: "#1f1a17", muted: "#7a6a5e" },
  css: String.raw`.prog{height:8px;border-radius:99px;background:rgba(127,127,140,.2);overflow:hidden}
.prog i{display:block;height:100%;background:var(--primary);transition:width .2s}
.q{font-size:20px;font-weight:800;line-height:1.25}
.opt{display:block;width:100%;text-align:left;margin-top:10px;padding:14px;border-radius:12px;border:2px solid rgba(127,127,140,.25);background:var(--surface);color:var(--text);font:600 16px var(--font);cursor:pointer;min-height:48px}
.opt.right{border-color:#16a34a;background:#dcfce7;color:#14532d}
.opt.wrong{border-color:#dc2626;background:#fee2e2;color:#7f1d1d}
.score{font-size:56px;font-weight:800;text-align:center;color:var(--primary)}
.why{font-size:14px;color:var(--muted);margin-top:10px}`,
  body: String.raw`<div class="app">
<header class="top"><h1 id="ttl"></h1><p id="step"></p></header>
<main><div class="prog"><i id="bar"></i></div><div class="card" id="box"></div></main>
<div class="toast" id="toast"></div></div>`,
  script: String.raw`const CONFIG = {
  title: "Space Quiz",
  questions: [
    { q: "Which planet is known as the Red Planet?", o: ["Venus", "Mars", "Jupiter", "Mercury"], a: 1, why: "Iron oxide on its surface gives Mars a red tint." },
    { q: "How many planets are in our solar system?", o: ["7", "8", "9", "10"], a: 1, why: "Eight, since Pluto was reclassified in 2006." },
    { q: "What is the closest star to Earth?", o: ["Sirius", "Proxima Centauri", "The Sun", "Vega"], a: 2, why: "The Sun is about 150 million km away." },
    { q: "Which planet has the most prominent rings?", o: ["Saturn", "Neptune", "Mars", "Earth"], a: 0, why: "Saturn's rings are mostly water ice." },
    { q: "The Moon orbits the Earth roughly every...", o: ["7 days", "27 days", "90 days", "365 days"], a: 1, why: "About 27.3 days to complete one orbit." }
  ]
};
document.title = CONFIG.title; $("ttl").textContent = CONFIG.title;
let i = 0, score = 0, answered = false, best = load("best", 0);
function ask() {
  const Q = CONFIG.questions[i]; answered = false;
  $("step").textContent = "Question " + (i + 1) + " of " + CONFIG.questions.length + " · Best " + best;
  $("bar").style.width = (i / CONFIG.questions.length) * 100 + "%";
  $("box").innerHTML = '<div class="q">' + esc(Q.q) + '</div>' + Q.o.map((t, k) => '<button class="opt" data-k="' + k + '">' + esc(t) + '</button>').join("") + '<div id="fb"></div>';
}
function done() {
  best = Math.max(best, score); save("best", best);
  $("bar").style.width = "100%"; $("step").textContent = "Finished";
  const n = CONFIG.questions.length;
  $("box").innerHTML = '<div class="score">' + score + '/' + n + '</div><p style="text-align:center" class="muted">' + (score === n ? "Perfect!" : score >= n / 2 ? "Nice work." : "Keep practising.") + ' Best: ' + best + '</p><button class="btn" id="again" style="width:100%;margin-top:14px">Play again</button>';
  $("again").onclick = () => { i = 0; score = 0; ask(); };
}
$("box").onclick = e => {
  const b = e.target.closest(".opt"), nx = e.target.closest("#next");
  if (nx) { i++; return i < CONFIG.questions.length ? ask() : done(); }
  if (!b || answered) return; answered = true;
  const Q = CONFIG.questions[i], k = Number(b.dataset.k);
  if (k === Q.a) score++;
  document.querySelectorAll(".opt").forEach((el, n) => { if (n === Q.a) el.classList.add("right"); else if (n === k) el.classList.add("wrong"); });
  $("fb").innerHTML = '<div class="why">' + (k === Q.a ? "Correct. " : "Not quite. ") + esc(Q.why || "") + '</div><button class="btn" id="next" style="width:100%;margin-top:12px">' + (i + 1 < CONFIG.questions.length ? "Next" : "See score") + '</button>';
};
ask();`,
});

export const expenseTrackerHtml = galleryPage({
  title: "Budget & Expenses",
  theme: { primary: "#10b981", bg: "#f2fbf7", text: "#10201a", muted: "#5b7169" },
  css: String.raw`.hero{background:var(--primary);color:var(--on-primary,#fff)}
.hero .lb{opacity:.85;font-size:13px}
.hero .big{font-size:34px;font-weight:800;letter-spacing:-.02em}
.meter{height:10px;border-radius:99px;background:rgba(255,255,255,.3);overflow:hidden;margin-top:10px}
.meter i{display:block;height:100%;background:#fff}
.meter.over i{background:#fecaca}
.cat{display:flex;align-items:center;gap:8px;font-size:14px;margin:6px 0}
.cat .t{width:84px}.cat .b{flex:1;height:8px;border-radius:99px;background:rgba(127,127,140,.15);overflow:hidden}
.cat .b i{display:block;height:100%;background:var(--primary)}
.item{display:flex;align-items:center;gap:10px;padding:10px 0;border-bottom:1px solid rgba(127,127,140,.15)}
.item:last-child{border-bottom:0}`,
  body: String.raw`<div class="app">
<header class="top"><h1>Budget</h1><p id="month"></p></header>
<main>
<div class="card hero"><div class="lb">Spent this month</div><div class="big" id="spent"></div><div class="lb" id="left"></div><div class="meter" id="meter"><i id="fill"></i></div></div>
<form class="card" id="form" style="display:grid;gap:10px">
<div class="row"><input id="amt" type="number" min="0" step="0.01" inputmode="decimal" placeholder="Amount" required><select id="cat" style="max-width:130px"></select></div>
<input id="note" placeholder="Note (optional)" maxlength="40"><button class="btn" type="submit">Add expense</button></form>
<div class="card" id="cats"></div>
<div class="card" id="list"></div>
<label class="f card">Monthly budget<input id="budget" type="number" min="0" step="100" inputmode="numeric"></label>
</main><div class="toast" id="toast"></div></div>`,
  script: String.raw`const CUR = "₹", CATS = ["Food", "Travel", "Shopping", "Bills", "Fun", "Other"];
let items = load("exp", []), budget = load("budget", 20000);
$("cat").innerHTML = CATS.map(c => '<option>' + c + '</option>').join("");
$("budget").value = budget;
const ym = d => ymd(new Date(d)).slice(0, 7);
const money = n => CUR + Math.round(n).toLocaleString("en-IN");
function render() {
  save("exp", items); save("budget", budget);
  const now = ym(Date.now()), mine = items.filter(x => ym(x.at) === now);
  const spent = mine.reduce((a, x) => a + x.amt, 0);
  $("month").textContent = new Date().toLocaleString("en-IN", { month: "long", year: "numeric" });
  $("spent").textContent = money(spent);
  $("left").textContent = budget ? (spent > budget ? money(spent - budget) + " over budget" : money(budget - spent) + " left of " + money(budget)) : "Set a monthly budget below";
  $("meter").classList.toggle("over", budget > 0 && spent > budget);
  $("fill").style.width = budget ? Math.min(100, (spent / budget) * 100) + "%" : "0%";
  const by = {}; mine.forEach(x => { by[x.cat] = (by[x.cat] || 0) + x.amt; });
  const mx = Math.max.apply(null, Object.keys(by).map(k => by[k]).concat([1]));
  $("cats").innerHTML = '<b>By category</b>' + (Object.keys(by).length ? Object.keys(by).sort((a, b) => by[b] - by[a]).map(k => '<div class="cat"><span class="t">' + esc(k) + '</span><span class="b"><i style="width:' + (by[k] / mx) * 100 + '%"></i></span><span>' + money(by[k]) + '</span></div>').join("") : '<div class="empty">Nothing yet.</div>');
  $("list").innerHTML = '<b>Recent</b>' + (mine.length ? mine.slice().sort((a, b) => b.at - a.at).slice(0, 30).map(x => '<div class="item"><div class="grow"><b>' + esc(x.note || x.cat) + '</b><div class="muted" style="font-size:12px">' + esc(x.cat) + ' · ' + new Date(x.at).toLocaleDateString("en-IN") + '</div></div><b>' + money(x.amt) + '</b><button class="x" data-id="' + x.id + '" aria-label="Delete">×</button></div>').join("") : '<div class="empty">Add your first expense above.</div>');
}
$("form").onsubmit = e => { e.preventDefault(); const v = Number($("amt").value); if (!(v > 0)) return;
  items.push({ id: Date.now(), amt: v, cat: $("cat").value, note: $("note").value.trim(), at: Date.now() });
  $("amt").value = ""; $("note").value = ""; render(); toast("Added"); };
$("list").onclick = e => { const b = e.target.closest("[data-id]"); if (b) { items = items.filter(x => String(x.id) !== b.dataset.id); render(); } };
$("budget").onchange = () => { budget = Math.max(0, Number($("budget").value) || 0); render(); };
render();`,
});
