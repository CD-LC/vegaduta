// Gallery templates, part 1: landing page, portfolio, restaurant menu + QR,
// booking form. Each is a complete single-file offline HTML app in the Studio
// shape (theme block, localStorage, esc/load/save). Edit the CONFIG object at
// the top of each script to make it yours.

import { galleryPage } from "./shell";
import { QR_JS } from "./qr";

export const landingHtml = galleryPage({
  title: "Landing Page",
  theme: { primary: "#6d5dfc", bg: "#f6f6fb", text: "#17171f", muted: "#6b6b7b" },
  css: String.raw`.hero{padding:34px 20px 22px;text-align:center}
.hero h1{font-size:34px;font-weight:800;letter-spacing:-.03em;line-height:1.1}
.hero p{color:var(--muted);margin:12px auto 20px;max-width:34ch}
.feats{display:grid;gap:12px}
.feat .ico{font-size:26px}
.feat b{display:block;margin-top:6px}
.plans{display:grid;gap:12px}
.plan .price{font-size:30px;font-weight:800}
.plan.hot{box-shadow:0 0 0 2px var(--primary),0 8px 24px rgba(0,0,0,.08)}
.plan ul{list-style:none;margin:10px 0;display:grid;gap:4px;color:var(--muted);font-size:14px}
h2.sec{font-size:20px;padding:6px 4px 0}
footer{padding:20px;text-align:center;color:var(--muted);font-size:13px}`,
  body: String.raw`<div class="app">
<section class="hero"><h1 id="headline"></h1><p id="sub"></p><a class="btn" href="#contact" id="cta"></a></section>
<main>
<div class="feats" id="feats"></div>
<h2 class="sec">Pricing</h2>
<div class="plans" id="plans"></div>
<h2 class="sec" id="contact">Get in touch</h2>
<form class="card" id="form" style="display:grid;gap:10px">
<label class="f">Name<input id="name" required maxlength="60"></label>
<label class="f">Email<input id="email" type="email" required maxlength="80"></label>
<label class="f">Message<textarea id="msg" rows="3" maxlength="400"></textarea></label>
<button class="btn" type="submit">Send (demo)</button>
<p class="muted" id="thanks" style="display:none">Thanks! This is a demo page: your message was saved on this device only and was not sent to anyone.</p>
</form>
</main>
<footer id="foot"></footer>
<div class="toast" id="toast"></div>
</div>`,
  script: String.raw`const CONFIG = {
  brand: "Nimbus", headline: "Ship your idea this week", sub: "Nimbus turns a rough plan into a live product, without the busywork.", cta: "Start free",
  features: [["⚡", "Fast", "Set up in minutes, not months."], ["🔒", "Private", "Your data stays yours."], ["📱", "Anywhere", "Works on every phone and laptop."]],
  plans: [{ name: "Free", price: "₹0", perks: ["1 project", "Community help"] }, { name: "Pro", price: "₹499/mo", perks: ["Unlimited projects", "Priority support", "Custom domain"], hot: true }]
};
document.title = CONFIG.brand;
$("headline").textContent = CONFIG.headline; $("sub").textContent = CONFIG.sub; $("cta").textContent = CONFIG.cta;
$("foot").textContent = "© " + new Date().getFullYear() + " " + CONFIG.brand;
$("feats").innerHTML = CONFIG.features.map(f => '<div class="card feat"><div class="ico">' + esc(f[0]) + '</div><b>' + esc(f[1]) + '</b><span class="muted">' + esc(f[2]) + '</span></div>').join("");
$("plans").innerHTML = CONFIG.plans.map(p => '<div class="card plan' + (p.hot ? " hot" : "") + '"><b>' + esc(p.name) + '</b><div class="price">' + esc(p.price) + '</div><ul>' + p.perks.map(k => '<li>✓ ' + esc(k) + '</li>').join("") + '</ul><a class="btn' + (p.hot ? "" : " ghost") + '" href="#contact">Choose ' + esc(p.name) + '</a></div>').join("");
$("form").onsubmit = e => {
  e.preventDefault();
  const leads = load("leads", []);
  leads.push({ name: $("name").value.trim(), email: $("email").value.trim(), msg: $("msg").value.trim(), at: Date.now() });
  save("leads", leads);
  $("form").reset(); $("thanks").style.display = "block"; toast("Saved on this device (demo)");
};`,
});

export const portfolioHtml = galleryPage({
  title: "Portfolio",
  theme: { primary: "#111827", bg: "#fafaf9", surface: "#ffffff", text: "#111827", muted: "#6b7280", radius: "14px" },
  css: String.raw`.me{display:flex;gap:14px;align-items:center;padding:8px 4px}
.avatar{width:72px;height:72px;border-radius:50%;background:var(--primary);color:var(--on-primary,#fff);display:grid;place-items:center;font-size:28px;font-weight:800}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px}
.proj .thumb{height:110px;border-radius:calc(var(--radius) - 6px);display:grid;place-items:center;font-size:38px;margin-bottom:10px}
.tag{display:inline-block;font-size:11px;font-weight:700;padding:3px 8px;border-radius:999px;background:var(--bg);color:var(--muted);margin:6px 4px 0 0}
.links{display:flex;flex-wrap:wrap;gap:8px}`,
  body: String.raw`<div class="app wide">
<header class="top"><div class="me"><div class="avatar" id="av"></div><div><h1 id="who"></h1><p id="role"></p></div></div></header>
<main>
<div class="card" id="about"></div>
<div class="chips" id="chips"></div>
<div class="grid" id="grid"></div>
<div class="card"><b>Let us work together</b><div class="links" id="links" style="margin-top:10px"></div></div>
</main></div>`,
  script: String.raw`const CONFIG = {
  name: "Asha Verma", role: "Product designer", about: "I design calm, useful apps for small businesses. Ten years of shipping, two of them teaching.",
  contact: [["Email", "mailto:hello@example.com"], ["Phone", "tel:+910000000000"], ["Profile", "https://example.com/asha"]],
  projects: [
    { title: "Bakery orders app", cat: "Mobile", icon: "🧁", color: "#fde7d3", desc: "Menu, cart and WhatsApp checkout." },
    { title: "Clinic booking", cat: "Web", icon: "🩺", color: "#d9f2e6", desc: "Slots, reminders, walk-in queue." },
    { title: "Fitness streaks", cat: "Mobile", icon: "🔥", color: "#fbe0e0", desc: "Habit loops with weekly review." },
    { title: "Studio brand kit", cat: "Brand", icon: "🎨", color: "#e3e0fb", desc: "Logo, palette and type system." },
    { title: "Farm dashboard", cat: "Web", icon: "🌾", color: "#f2efc9", desc: "Yield and rainfall at a glance." }
  ]
};
document.title = CONFIG.name + " - Portfolio";
$("who").textContent = CONFIG.name; $("role").textContent = CONFIG.role; $("about").textContent = CONFIG.about;
$("av").textContent = CONFIG.name.trim().charAt(0).toUpperCase();
$("links").innerHTML = CONFIG.contact.map(c => '<a class="btn ghost" href="' + esc(c[1]) + '"' + (/^https?:/i.test(c[1]) ? ' target="_blank" rel="noopener noreferrer"' : '') + '>' + esc(c[0]) + '</a>').join("");
let cat = load("cat", "All");
const cats = ["All"].concat(Array.from(new Set(CONFIG.projects.map(p => p.cat))));
if (cats.indexOf(cat) < 0) cat = "All";
function render() {
  $("chips").innerHTML = cats.map(c => '<button class="chip' + (c === cat ? " on" : "") + '" data-c="' + esc(c) + '">' + esc(c) + '</button>').join("");
  const list = CONFIG.projects.filter(p => cat === "All" || p.cat === cat);
  $("grid").innerHTML = list.map(p => '<div class="card proj"><div class="thumb" style="background:' + esc(p.color) + '">' + esc(p.icon) + '</div><b>' + esc(p.title) + '</b><p class="muted" style="font-size:14px">' + esc(p.desc) + '</p><span class="tag">' + esc(p.cat) + '</span></div>').join("") || '<div class="empty">No projects here yet.</div>';
  save("cat", cat);
}
$("chips").onclick = e => { const b = e.target.closest("[data-c]"); if (b) { cat = b.dataset.c; render(); } };
render();`,
});

export const menuQrHtml = galleryPage({
  title: "Restaurant Menu",
  theme: { primary: "#c2410c", bg: "#fffaf5", text: "#231815", muted: "#7a6a5e" },
  css: String.raw`.cats{position:sticky;top:0;background:var(--bg);padding:8px 16px;z-index:5}
.dish{display:flex;gap:12px;align-items:flex-start}
.dish .em{font-size:30px}
.dish .nm{font-weight:700}
.dish .pr{font-weight:800;color:var(--primary)}
.veg{display:inline-block;width:10px;height:10px;border:1.5px solid #16a34a;border-radius:2px;margin-right:6px;position:relative}
.veg.n{border-color:#b91c1c}
.veg:after{content:"";position:absolute;inset:2px;border-radius:50%;background:#16a34a}
.veg.n:after{background:#b91c1c}
.qrbox{text-align:center;display:grid;gap:10px;justify-items:center}
.qrbox svg{border-radius:10px;border:1px solid rgba(127,127,140,.25);max-width:100%;height:auto}
.bar{position:fixed;bottom:0;left:0;right:0;padding:10px 16px calc(10px + env(safe-area-inset-bottom));background:var(--surface);box-shadow:0 -4px 20px rgba(0,0,0,.08);display:none}
.bar.show{display:block}
.bar .btn{width:100%;max-width:520px;margin:0 auto}
.qty{display:flex;align-items:center;gap:6px}
.qty button{width:34px;height:34px;border-radius:50%;border:0;background:var(--primary);color:var(--on-primary,#fff);font-size:18px;cursor:pointer}
@media print{.cats,.bar,#order,.noprint{display:none!important}body{background:#fff}}`,
  body: String.raw`<div class="app">
<header class="top"><h1 id="shop"></h1><p id="tag"></p></header>
<div class="cats"><div class="chips" id="chips"></div></div>
<main id="list"></main>
<main id="qrsec" class="noprint"><div class="card qrbox"><b>Table QR code</b><p class="muted" style="font-size:13px">Paste the link to this menu, print the code, put it on every table.</p>
<input id="url" placeholder="Paste your menu link here" maxlength="78"><div id="qr"></div><button class="btn" id="print">Print QR</button></div></main>
<div class="bar" id="bar"><button class="btn" id="order"></button></div>
<div class="toast" id="toast"></div>
</div>`,
  script: String.raw`${QR_JS}
const CONFIG = {
  name: "Spice Route Kitchen", tagline: "Fresh, made to order", currency: "₹", whatsapp: "910000000000",
  menu: [
    { cat: "Starters", items: [{ n: "Paneer tikka", p: 220, v: 1, e: "🧀" }, { n: "Chicken 65", p: 260, v: 0, e: "🍗" }, { n: "Crispy corn", p: 180, v: 1, e: "🌽" }] },
    { cat: "Mains", items: [{ n: "Butter chicken", p: 340, v: 0, e: "🍛" }, { n: "Dal makhani", p: 260, v: 1, e: "🥘" }, { n: "Veg biryani", p: 240, v: 1, e: "🍚" }] },
    { cat: "Desserts", items: [{ n: "Gulab jamun", p: 120, v: 1, e: "🍮" }, { n: "Kulfi", p: 110, v: 1, e: "🍦" }] },
    { cat: "Drinks", items: [{ n: "Masala chai", p: 50, v: 1, e: "☕" }, { n: "Fresh lime soda", p: 80, v: 1, e: "🍋" }] }
  ]
};
document.title = CONFIG.name;
$("shop").textContent = CONFIG.name; $("tag").textContent = CONFIG.tagline;
let cat = CONFIG.menu[0].cat, cart = load("cart", {});
const price = n => CONFIG.currency + n;
function count() { return Object.keys(cart).reduce((a, k) => a + cart[k].q, 0); }
function total() { return Object.keys(cart).reduce((a, k) => a + cart[k].q * cart[k].p, 0); }
function render() {
  $("chips").innerHTML = CONFIG.menu.map(c => '<button class="chip' + (c.cat === cat ? " on" : "") + '" data-c="' + esc(c.cat) + '">' + esc(c.cat) + '</button>').join("");
  const sec = CONFIG.menu.find(c => c.cat === cat);
  $("list").innerHTML = sec.items.map(i => {
    const q = cart[i.n] ? cart[i.n].q : 0;
    return '<div class="card dish"><div class="em">' + esc(i.e) + '</div><div class="grow"><div class="nm"><span class="veg' + (i.v ? "" : " n") + '"></span>' + esc(i.n) + '</div><div class="pr">' + price(i.p) + '</div></div><div class="qty">' +
      (q ? '<button data-m="' + esc(i.n) + '" aria-label="Less">−</button><b>' + q + '</b>' : '') + '<button data-a="' + esc(i.n) + '" aria-label="Add">+</button></div></div>';
  }).join("");
  $("bar").classList.toggle("show", count() > 0);
  $("order").textContent = "Order " + count() + " item" + (count() === 1 ? "" : "s") + " · " + price(total());
  save("cart", cart);
}
function find(n) { for (const c of CONFIG.menu) for (const i of c.items) if (i.n === n) return i; return null; }
$("chips").onclick = e => { const b = e.target.closest("[data-c]"); if (b) { cat = b.dataset.c; render(); } };
$("list").onclick = e => {
  const b = e.target.closest("button"); if (!b) return;
  if (b.dataset.a) { const i = find(b.dataset.a); cart[i.n] = { p: i.p, q: (cart[i.n] ? cart[i.n].q : 0) + 1 }; }
  if (b.dataset.m && cart[b.dataset.m]) { cart[b.dataset.m].q--; if (cart[b.dataset.m].q <= 0) delete cart[b.dataset.m]; }
  render();
};
$("order").onclick = () => {
  const lines = Object.keys(cart).map(k => cart[k].q + " x " + k);
  const text = "Order for " + CONFIG.name + ":\n" + lines.join("\n") + "\nTotal: " + price(total());
  window.open("https://wa.me/" + CONFIG.whatsapp + "?text=" + encodeURIComponent(text), "_blank");
};
function drawQr() {
  const u = $("url").value.trim();
  $("qr").innerHTML = u ? (qrSvg(u, 200) || '<p class="muted">Link too long for a QR code (max 78 characters).</p>') : '<p class="muted">Enter the link above to create a code.</p>';
  save("qrUrl", u);
}
$("url").value = load("qrUrl", "");
$("url").oninput = drawQr;
$("print").onclick = () => window.print();
drawQr(); render();`,
});

export const bookingHtml = galleryPage({
  title: "Book an Appointment",
  theme: { primary: "#0ea5e9", bg: "#f1f8fc", text: "#0f1c24", muted: "#5a6d78" },
  css: String.raw`.slots{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.slot{border:1.5px solid rgba(127,127,140,.3);background:var(--bg);color:var(--text);border-radius:10px;min-height:44px;font:600 14px var(--font);cursor:pointer}
.slot.on{background:var(--primary);border-color:var(--primary);color:var(--on-primary,#fff)}
.slot:disabled{opacity:.4;text-decoration:line-through;cursor:not-allowed}
.bk{display:flex;align-items:center;gap:10px}
.bk .when{font-weight:700}`,
  body: String.raw`<div class="app">
<header class="top"><h1 id="biz"></h1><p>Pick a service, a day and a time.</p><p class="muted" style="font-size:12px;margin-top:6px">Demo: bookings are kept on this device only - nothing is sent to the business.</p></header>
<main>
<form class="card" id="form" style="display:grid;gap:12px">
<label class="f">Service<select id="svc"></select></label>
<label class="f">Date<input id="date" type="date" required></label>
<div><div class="muted" style="font-size:13px;font-weight:600;margin-bottom:6px">Time</div><div class="slots" id="slots"></div></div>
<label class="f">Your name<input id="name" required maxlength="60"></label>
<label class="f">Phone<input id="phone" type="tel" inputmode="tel" required maxlength="20"></label>
<button class="btn" type="submit" id="go">Confirm booking</button>
</form>
<h2 style="font-size:18px;padding:4px">Your bookings</h2>
<div id="list"></div>
</main><div class="toast" id="toast"></div></div>`,
  script: String.raw`const CONFIG = {
  name: "Glow Salon", services: [["Haircut", 45], ["Hair colour", 120], ["Facial", 60]],
  hours: ["10:00", "11:00", "12:00", "14:00", "15:00", "16:00", "17:00", "18:00", "19:00"], closedDays: [0]
};
document.title = "Book - " + CONFIG.name; $("biz").textContent = CONFIG.name;
let bookings = load("bookings", []), pick = "";
$("svc").innerHTML = CONFIG.services.map(s => '<option>' + esc(s[0]) + ' (' + s[1] + ' min)</option>').join("");
const today = ymd();
$("date").min = today; $("date").value = today;
function taken(d, t) { return bookings.some(b => b.date === d && b.time === t); }
function slots() {
  const d = $("date").value, day = d ? new Date(d + "T00:00").getDay() : 0;
  const closed = CONFIG.closedDays.indexOf(day) >= 0;
  const past = t => d === today && new Date(d + "T" + t) < new Date();
  if (pick && (closed || taken(d, pick) || past(pick))) pick = "";
  $("slots").innerHTML = closed ? '<div class="empty" style="grid-column:1/-1">Closed that day.</div>' :
    CONFIG.hours.map(t => '<button type="button" class="slot' + (pick === t ? " on" : "") + '" data-t="' + t + '"' + (taken(d, t) || past(t) ? " disabled" : "") + '>' + t + '</button>').join("");
  $("go").disabled = !pick;
}
function list() {
  const up = bookings.slice().sort((a, b) => (a.date + a.time).localeCompare(b.date + b.time));
  $("list").innerHTML = up.length ? up.map(b => '<div class="card bk" style="margin-bottom:8px"><div class="grow"><div class="when">' + esc(b.date) + ' · ' + esc(b.time) + '</div><div class="muted" style="font-size:13px">' + esc(b.svc) + ' — ' + esc(b.name) + '</div></div><button class="x" data-id="' + b.id + '" aria-label="Cancel booking">×</button></div>').join("") : '<div class="empty">No bookings yet.</div>';
}
$("slots").onclick = e => { const b = e.target.closest("[data-t]"); if (b && !b.disabled) { pick = b.dataset.t; slots(); } };
$("date").onchange = slots;
$("form").onsubmit = e => {
  e.preventDefault(); if (!pick) return;
  const d = $("date").value;
  if (taken(d, pick)) { toast("That slot was just taken"); return slots(); }
  bookings.push({ id: Date.now(), date: d, time: pick, svc: $("svc").value, name: $("name").value.trim(), phone: $("phone").value.trim() });
  save("bookings", bookings); pick = ""; slots(); list(); toast("Booked!");
};
$("list").onclick = e => { const b = e.target.closest("[data-id]"); if (!b) return; bookings = bookings.filter(x => String(x.id) !== b.dataset.id); save("bookings", bookings); slots(); list(); };
slots(); list();`,
});
