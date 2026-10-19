// VegaDūta brand banner for the Keycloak Account Console (2026-10-09).
//
// The admin and tenant consoles open an animated "brand identity" scene when
// the header emblem is clicked (web/app/platform-admin/components/BrandIdentityModal.tsx,
// web/app/components/BrandIdentityModal.js). This is the same scene ported to
// a dependency-free module, because the account console is Keycloak's compiled
// React app and a theme can only add stylesheets and scripts
// (theme.properties `scripts=`). Keep the scene in step with those two files.
//
// The header is rendered (and re-rendered) by React, so nothing here holds
// a reference to it: one delegated click/keydown listener on document opens
// the banner from the brand link. Styles live in css/account.css (.vdb-*).
// prefers-reduced-motion: no canvas/pulse loops and no CSS animation.

const MARK = new URL("../img/logo-mark-hd.png", import.meta.url).href;
const SCENE_W = 1200;
const SCENE_H = 760;

let open = null; // { root, close }

function el(tag, attrs = {}, style = "") {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  if (style) node.style.cssText = style;
  return node;
}

function abs(style) {
  return el("div", {}, "position:absolute;pointer-events:none;" + style);
}

function node(left, top, size, delay) {
  const wrap = abs(`left:${left}%;top:${top}%;`);
  const m = -size / 2;
  wrap.append(
    el("span", {}, `position:absolute;width:${size}px;height:${size}px;margin:${m}px 0 0 ${m}px;border-radius:50%;background:radial-gradient(circle,#8ff0f0,#4fe3e3);box-shadow:0 0 12px #4fe3e3;animation:vgNode 1.6s ease-in-out infinite ${delay}s`),
    el("span", {}, `position:absolute;width:${size}px;height:${size}px;margin:${m}px 0 0 ${m}px;border-radius:50%;border:2px solid #4fe3e3;animation:vgRipple 1.6s ease-out infinite ${delay}s`),
  );
  return wrap;
}

function buildScene() {
  const scene = el("div", { class: "vdb-scene" });
  const stars = el("canvas", { width: SCENE_W, height: SCENE_H }, "position:absolute;inset:0;opacity:.55");
  scene.append(stars);
  scene.append(
    abs("left:50%;top:46%;width:660px;height:660px;border-radius:50%;background:radial-gradient(circle,rgba(245,166,35,.14),rgba(124,92,255,.10) 42%,transparent 66%);filter:blur(3px);animation:vgHalo 6s ease-in-out infinite"),
    abs("left:50%;top:46%;width:520px;height:520px;border-radius:50%;background:radial-gradient(circle,rgba(79,227,227,.10),transparent 62%);filter:blur(2px);animation:vgHalo2 7.5s ease-in-out infinite"),
    abs("left:50%;top:46%;width:640px;height:640px;margin:-320px 0 0 -320px;border-radius:50%;border:1.5px dashed rgba(245,181,68,.20);animation:vgSpin 50s linear infinite"),
    abs("left:50%;top:46%;width:560px;height:560px;margin:-280px 0 0 -280px;border-radius:50%;border:1px dotted rgba(124,92,255,.22);animation:vgSpinR 38s linear infinite"),
  );

  const reveal = el("div", { class: "vdb-reveal" }, "position:absolute;left:50%;top:46%;width:640px;height:480px;margin:-240px 0 0 -320px;z-index:2");
  const hover = el("div", {}, "width:100%;height:100%;animation:vgHover 6.8s ease-in-out infinite");
  const frame = el("div", {}, "position:relative;width:100%;height:100%");
  frame.append(el("img", { src: MARK, alt: "" }, "width:100%;height:100%;object-fit:contain;display:block"));

  const sheenMask = abs(`inset:0;overflow:hidden;-webkit-mask:url("${MARK}") center/contain no-repeat;mask:url("${MARK}") center/contain no-repeat`);
  sheenMask.append(el("div", {}, "position:absolute;top:0;bottom:0;width:26%;background:linear-gradient(90deg,transparent,rgba(255,247,224,.7),transparent);animation:vgSheen 5.5s ease-in-out infinite 1s;mix-blend-mode:screen"));
  frame.append(sheenMask);
  frame.append(abs("left:60.5%;top:37%;width:78px;height:78px;margin:-39px 0 0 -39px;border-radius:50%;background:radial-gradient(circle,rgba(255,214,138,.6),rgba(245,166,35,0) 70%);animation:vgEye 2.6s ease-in-out infinite;mix-blend-mode:screen"));

  const trails = abs("left:1%;top:43%;width:34%;height:26%");
  for (const [l, t, w, h, g, d] of [
    [34, 4, 44, 4, "transparent,#c9a2ff,#7c5cff", 0],
    [6, 30, 66, 5, "transparent,#8fb4ff,#4f6fff", 0.18],
    [18, 58, 54, 4, "transparent,#7f9bff,#4fe3e3", 0.36],
    [38, 84, 32, 3, "transparent,#6ad0ff", 0.54],
  ]) {
    trails.append(el("span", {}, `position:absolute;left:${l}%;top:${t}%;width:${w}%;height:${h}px;border-radius:3px;background:linear-gradient(90deg,${g});animation:vgTrail 1.7s ease-in-out infinite ${d}s`));
  }
  frame.append(trails, node(88.5, 53.5, 15, 0), node(90, 66, 14, 0.4), node(82, 80, 13, 0.8));
  const pulse = el("span", {}, "position:absolute;width:8px;height:8px;margin:-4px 0 0 -4px;border-radius:50%;background:#fff;box-shadow:0 0 12px #8ff0f0,0 0 22px #4fe3e3;pointer-events:none;opacity:0");
  frame.append(pulse);
  hover.append(frame);
  reveal.append(hover);
  scene.append(reveal);

  const sparks = el("canvas", { width: SCENE_W, height: SCENE_H }, "position:absolute;inset:0;z-index:3;pointer-events:none");
  scene.append(sparks);

  const caption = el("p", { class: "vdb-caption" });
  caption.innerHTML = 'Vega<span>Dūta</span>';
  scene.append(caption);
  return { scene, stars, sparks, pulse };
}

function animate({ stars, sparks, pulse }, reduce) {
  const ids = [];
  const loop = (fn) => { const tick = () => { if (fn() !== false) ids.push(requestAnimationFrame(tick)); }; tick(); };

  const sc = stars.getContext("2d");
  const dots = Array.from({ length: 150 }, () => ({
    x: Math.random() * SCENE_W, y: Math.random() * SCENE_H, r: Math.random() * 1.4 + 0.2,
    a: Math.random() * 0.8 + 0.2, tw: Math.random() * 6.28, sp: 0.4 + Math.random() * 0.9,
  }));
  let t = 0;
  loop(() => {
    t += 0.016;
    sc.clearRect(0, 0, SCENE_W, SCENE_H);
    for (const d of dots) {
      const k = reduce ? 1 : 0.5 + 0.5 * Math.sin(t * d.sp + d.tw);
      sc.beginPath();
      sc.arc(d.x, d.y, d.r, 0, 7);
      sc.fillStyle = `rgba(${d.tw > 4 ? "245,181,68" : "127,240,240"},${d.a * k})`;
      sc.fill();
    }
    return !reduce;
  });
  if (reduce) return ids;

  const pc = sparks.getContext("2d");
  const parts = [];
  let f = 0;
  loop(() => {
    if (++f % 6 === 0) parts.push({ x: 340 + Math.random() * 120, y: 380 + Math.random() * 90, vx: -1.4 - Math.random() * 1.2, vy: -0.3 + Math.random() * 0.6, life: 1, r: 0.8 + Math.random() * 1.6, c: Math.random() > 0.5 ? "245,181,68" : "127,240,240" });
    pc.clearRect(0, 0, SCENE_W, SCENE_H);
    for (let i = parts.length - 1; i >= 0; i--) {
      const p = parts[i];
      p.x += p.vx; p.y += p.vy; p.life -= 0.012;
      if (p.life <= 0) { parts.splice(i, 1); continue; }
      pc.beginPath();
      pc.arc(p.x, p.y, p.r, 0, 7);
      pc.fillStyle = `rgba(${p.c},${p.life * 0.8})`;
      pc.shadowBlur = 6;
      pc.shadowColor = `rgba(${p.c},${p.life})`;
      pc.fill();
      pc.shadowBlur = 0;
    }
  });

  const pts = [[62, 46], [66, 55], [74, 60], [82, 66], [82, 80], [88.5, 53.5], [90, 66]];
  let seg = 0, tt = 0;
  loop(() => {
    const a = pts[seg], b = pts[(seg + 1) % pts.length];
    pulse.style.left = a[0] + (b[0] - a[0]) * tt + "%";
    pulse.style.top = a[1] + (b[1] - a[1]) * tt + "%";
    pulse.style.opacity = tt < 0.1 || tt > 0.9 ? "0.3" : "1";
    tt += 0.03;
    if (tt >= 1) { tt = 0; seg = (seg + 1) % pts.length; }
  });
  return ids;
}

function openBanner(trigger) {
  if (open) return;
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const root = el("div", { class: "vdb-backdrop", role: "dialog", "aria-modal": "true", "aria-label": "VegaDūta brand identity" });
  const fit = el("div", { class: "vdb-fit" });
  const parts = buildScene();
  const closeBtn = el("button", { type: "button", class: "vdb-close", "aria-label": "Close" });
  closeBtn.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12"/></svg>';
  fit.append(parts.scene);
  root.append(fit, closeBtn);

  const scale = () => {
    const s = Math.min(1, (innerWidth - 32) / SCENE_W, (innerHeight - 32) / SCENE_H);
    fit.style.width = SCENE_W * s + "px";
    fit.style.height = SCENE_H * s + "px";
    parts.scene.style.transform = `scale(${s})`;
    parts.scene.style.setProperty("--vdb-s", String(s)); // vdbPop's start frame
  };
  scale();
  addEventListener("resize", scale);

  const ids = animate(parts, reduce);
  const prevOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";

  const onKey = (e) => {
    if (e.key === "Escape") { e.preventDefault(); close(); }
    else if (e.key === "Tab") { e.preventDefault(); closeBtn.focus(); } // single focusable control
  };
  const close = () => {
    ids.forEach(cancelAnimationFrame);
    removeEventListener("resize", scale);
    document.removeEventListener("keydown", onKey, true);
    document.body.style.overflow = prevOverflow;
    root.classList.add("vdb-closing");
    setTimeout(() => root.remove(), reduce ? 0 : 180);
    open = null;
    if (trigger && trigger.isConnected) trigger.focus();
  };
  root.addEventListener("click", (e) => { if (e.target === root) close(); });
  closeBtn.addEventListener("click", close);
  document.addEventListener("keydown", onKey, true);

  document.body.append(root);
  closeBtn.focus();
  open = { root, close };
}

// The brand link from theme.properties' logo/logoUrl. Plain click on the
// emblem opens the banner (same as the admin console); modified clicks
// (new tab etc.) still follow logoUrl.
// KC 25's account header is PF5's (deprecated) PageHeader, not Masthead.
const BRAND = ".pf-v5-c-page__header-brand-link";

document.addEventListener("click", (e) => {
  const brand = e.target instanceof Element && e.target.closest(BRAND);
  if (!brand || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  e.preventDefault();
  openBanner(brand);
}, true);

// Label the link for assistive tech once React has rendered it.
const label = () => {
  const brand = document.querySelector(BRAND);
  if (brand && !brand.hasAttribute("data-vdb")) {
    brand.setAttribute("data-vdb", "");
    brand.setAttribute("aria-label", "VegaDūta - open brand banner");
    brand.setAttribute("aria-haspopup", "dialog");
    brand.setAttribute("title", "VegaDūta");
  }
};
new MutationObserver(label).observe(document.documentElement, { childList: true, subtree: true });
label();
