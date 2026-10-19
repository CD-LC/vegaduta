"use client";

import { useEffect, useRef } from "react";

/**
 * The AXON Dashboard's "Cognition Core" hero visualization (design_handoff_
 * axon/design_files/AXON Dashboard.dc.html's `initCore()`), reimplemented as
 * a React canvas component per the handoff's own instruction ("all drawn
 * with <canvas>/SVG in the logic classes - reimplement with canvas/SVG...
 * in the target stack"). Same algorithm as the prototype, ported 1:1 rather
 * than reinterpreted: 12 nodes drifting around three concentric rings
 * (reasoning/tool/idle color-coded), a radar tick-sweep around the
 * perimeter, a pulsing center glow, and short-lived light pulses travelling
 * from the center out to a random node every ~200ms.
 *
 * `tokensPerSecond` is read only to size nothing visual (the big number is
 * rendered by the caller, on top of this canvas) - this component owns
 * purely the animation, no DOM text.
 *
 * Used to be hardcoded to the dark-mode palette regardless of the console's
 * own light/dark theme, on the theory that "the neon viz always reads"
 * against a fixed dark backdrop. Direct user report ("this section on theme
 * change is not changing") plus the fact that the caller's `.ax-core-panel`
 * background is now theme-reactive too (see globals.css) made that
 * inconsistent - a fixed-dark canvas over a now-light panel would look
 * broken, not deliberate. So every color here is read live from the same
 * `--hud-cyan`/`--hud-violet`/`--hud-warn`/`--ax-text` custom properties the
 * surrounding chrome uses, re-read every frame (cheap - 4 getComputedStyle
 * lookups) so a theme toggle repaints within one frame with no extra
 * listener wiring.
 */
// `hex` is whatever getComputedStyle handed back (possibly "" for an unset
// custom property - the String()/regex guard below is the real validator);
// both in and out are the bare "r,g,b" triple string the rgba() template
// literals below interpolate.
function hexToRgbTriple(hex: string, fallback: string): string {
  const m = String(hex || "").trim().match(/^#([0-9a-f]{6})$/i);
  if (!m) return fallback;
  const n = parseInt(m[1], 16);
  return `${(n >> 16) & 255},${(n >> 8) & 255},${n & 255}`;
}

export default function CognitionCore() {
  const canvasRef = useRef(null);
  const rafRef = useRef(null);

  useEffect(() => {
    const cv = canvasRef.current;
    if (!cv) return undefined;

    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    function fit() {
      const r = cv.getBoundingClientRect();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      cv.width = Math.max(1, r.width * dpr);
      cv.height = Math.max(1, r.height * dpr);
      const ctx = cv.getContext("2d");
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      return { ctx, w: r.width, h: r.height };
    }

    function readTheme() {
      const cs = getComputedStyle(document.documentElement);
      return {
        cyan: hexToRgbTriple(cs.getPropertyValue("--hud-cyan"), "79,227,227"),
        violet: hexToRgbTriple(cs.getPropertyValue("--hud-violet"), "155,125,255"),
        amber: hexToRgbTriple(cs.getPropertyValue("--hud-warn"), "245,181,68"),
        text: hexToRgbTriple(cs.getPropertyValue("--ax-text"), "230,236,244"),
      };
    }

    const N = 12;
    const rings = [0.2, 0.31, 0.42];
    const nodes = Array.from({ length: N }, (_, i) => ({
      r: rings[i % rings.length] + (Math.random() * 0.04 - 0.02),
      a: (i / N) * 6.28 + Math.random(),
      sp: (0.0012 + Math.random() * 0.0018) * (i % 2 ? 1 : -1),
      sz: 2.6 + Math.random() * 2.4,
      ph: Math.random() * 6.28,
      kind: i % 4 === 0 ? "reason" : i % 5 === 0 ? "tool" : "idle",
    }));

    // One travelling light pulse (spawned by the interval below, consumed
    // and filtered out by draw() once t reaches 1): `i` indexes the target
    // node in `pos`, `t` is 0..1 progress, `sp` its per-frame speed, `dir`
    // whether it travels outward (1) or inward (-1), and `c` the "r,g,b"
    // triple (from readTheme(), frozen at spawn time by design - a pulse
    // keeps the palette it was born under until it dies ~1s later).
    interface Pulse {
      i: number;
      t: number;
      sp: number;
      dir: 1 | -1;
      c: string;
    }

    let pulses: Pulse[] = [];
    const spawner = setInterval(() => {
      const i = Math.floor(Math.random() * N);
      const t = readTheme();
      pulses.push({
        i,
        t: 0,
        sp: 0.02 + Math.random() * 0.02,
        dir: Math.random() > 0.5 ? 1 : -1,
        c: Math.random() > 0.5 ? t.cyan : t.violet,
      });
    }, 200);

    let s = 0;
    let cancelled = false;

    function draw() {
      if (cancelled) return;
      const { ctx, w, h } = fit();
      const theme = readTheme();
      ctx.clearRect(0, 0, w, h);
      const cx = w / 2;
      const cy = h / 2;
      const R = Math.min(w, h);
      if (!reduceMotion) s += 0.012;
      const tickR = R * 0.475;

      for (let k = 0; k < 72; k++) {
        const a = (k / 72) * 6.28;
        const lit = Math.abs((((s % 6.28) - a + 9.42) % 6.28) - 3.14) > 3.14 - 0.28;
        const len = k % 6 === 0 ? 9 : 5;
        ctx.strokeStyle = lit ? `rgba(${theme.cyan},0.85)` : `rgba(${theme.cyan},0.14)`;
        ctx.lineWidth = lit ? 1.6 : 1;
        ctx.beginPath();
        ctx.moveTo(cx + Math.cos(a) * tickR, cy + Math.sin(a) * tickR);
        ctx.lineTo(cx + Math.cos(a) * (tickR - len), cy + Math.sin(a) * (tickR - len));
        ctx.stroke();
      }

      for (const r of rings) {
        ctx.strokeStyle = `rgba(${theme.violet},0.09)`;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.arc(cx, cy, R * r, 0, 7);
        ctx.stroke();
      }

      const pos = nodes.map((n) => {
        if (!reduceMotion) n.a += n.sp;
        return { x: cx + Math.cos(n.a) * R * n.r, y: cy + Math.sin(n.a) * R * n.r, n };
      });

      pos.forEach((p) => {
        ctx.strokeStyle = `rgba(${theme.cyan},0.06)`;
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.lineTo(p.x, p.y);
        ctx.stroke();
      });

      const cr = 26 + Math.sin(s * 2.4) * 4;
      const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, cr * 3);
      g.addColorStop(0, `rgba(${theme.violet},0.5)`);
      g.addColorStop(0.5, `rgba(${theme.cyan},0.14)`);
      g.addColorStop(1, `rgba(${theme.cyan},0)`);
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(cx, cy, cr * 3, 0, 7);
      ctx.fill();

      if (!reduceMotion) {
        pulses.forEach((p) => {
          p.t += p.sp;
          const tg = pos[p.i];
          const tt = p.dir > 0 ? p.t : 1 - p.t;
          const x = cx + (tg.x - cx) * tt;
          const y = cy + (tg.y - cy) * tt;
          ctx.shadowBlur = 10;
          ctx.shadowColor = `rgba(${p.c},0.9)`;
          ctx.fillStyle = `rgba(${p.c},0.95)`;
          ctx.beginPath();
          ctx.arc(x, y, 2, 0, 7);
          ctx.fill();
          ctx.shadowBlur = 0;
        });
        pulses = pulses.filter((p) => p.t < 1);
      }

      pos.forEach((p) => {
        const n = p.n;
        const pulse = 0.55 + Math.sin(s * 2 + n.ph) * 0.45;
        const col = n.kind === "reason" ? theme.violet : n.kind === "tool" ? theme.amber : theme.cyan;
        ctx.shadowBlur = 12 * pulse;
        ctx.shadowColor = `rgba(${col},0.9)`;
        ctx.fillStyle = `rgba(${col},${0.5 + pulse * 0.4})`;
        ctx.beginPath();
        ctx.arc(p.x, p.y, n.sz, 0, 7);
        ctx.fill();
        ctx.shadowBlur = 0;
      });

      ctx.fillStyle = `rgba(${theme.text},0.95)`;
      ctx.shadowBlur = 16;
      ctx.shadowColor = `rgba(${theme.violet},1)`;
      ctx.beginPath();
      ctx.arc(cx, cy, 5, 0, 7);
      ctx.fill();
      ctx.shadowBlur = 0;

      rafRef.current = requestAnimationFrame(draw);
    }

    draw();

    return () => {
      cancelled = true;
      clearInterval(spawner);
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
    };
  }, []);

  return <canvas ref={canvasRef} className="ax-core-canvas" aria-hidden="true" />;
}
