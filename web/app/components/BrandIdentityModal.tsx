"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { X } from "lucide-react";
import { useTranslations } from "../lib/i18n";

// "After clicking the banner/logo, show only a single destination
// window/page...the target page must visually match the attached reference
// page pixel-for-pixel" - this is a 1:1 port of the literal HTML/CSS/JS the
// user's uploaded VegaDuta-Banner.html renders, NOT a hand-designed
// wordmark/tagline card (a prior version of this file was that, and got
// flagged as not matching). That banner file is a self-executing "bundled"
// artifact - a loader script that unpacks a JSON manifest of embedded
// resources at runtime - which earlier looked unreadable without a headless
// browser (see historical task #843/#921). It isn't: the manifest and the
// actual page template are both plain JSON/string literals sitting in
// <script type="__bundler/manifest"> and <script type="__bundler/template">
// tags in the file's own source, extractable with a text parse:
//   - __bundler/manifest held one real resource, "mime":"image/png", whose
//     base64 "data" field decodes to a 1024x1024 PNG - that PNG is
//     byte-for-byte (md5) identical to the /public/logo-mark.png already
//     wired into this app's AnimatedBrandMark (#728), so the actual mark
//     asset was already the exact right one.
//   - __bundler/template held the literal scene markup the banner renders:
//     a fixed 1200x760 dark panel with a canvas starfield, two breathing
//     radial-gradient halos, two rotating dashed/dotted "chakra" orbit
//     rings, the mark itself with a gold sheen swept across it via a CSS
//     mask clipped to the artwork's own alpha, a small "cognition glow" over
//     the temple, four animated speed-trail streaks, three circuit delivery
//     nodes each with a pulsing dot + expanding ripple ring, one traveling
//     data pulse walking a fixed path across those nodes, and a canvas of
//     drifting spark particles. No wordmark text, no tagline, no devanagari
//     - the reference is purely this animated visual.
// Every size/color/gradient-stop/animation name+duration+delay below is
// copied verbatim from that extracted template (see the @keyframes block in
// globals.css for the ten vg* animations this scene uses) - nothing here is
// redesigned or approximated. The three canvas/pulse effects are the
// template's own componentDidMount logic (starfield dot formula, spark
// spawn/physics, pulse path points) ported from vanilla DOM/canvas calls to
// React refs + useEffect, same math, same constants.
//
// Kept the same "single window" modal chrome as before (portal +
// AnimatePresence + Escape-to-close + backdrop) - that's necessary
// interactive wrapper, not scene content, and this file only ever renders
// once per app shell (see AppShell.js), so there is exactly one destination
// on click, no intermediate screens or duplicate windows.
interface BrandIdentityModalProps {
  open: boolean;
  onClose: () => void;
}

// Colour literals in the scene below (#4fe3e3 cyan nodes, #c9a2ff/#7c5cff
// and #8fb4ff/#4f6fff trail gradients, #8ff0f0 glow) are the banner
// artwork's own palette, matched to the embedded PNG: they must stay fixed
// in light and dark mode and under every data-accent, so they are left as
// brand-asset literals rather than mapped to theme tokens (A13; this file
// is exempt from the design-tokens/no-hex-literal lint rule by name).
export default function BrandIdentityModal({ open, onClose }: BrandIdentityModalProps) {
  const t = useTranslations("brandIdentityModal");
  const [mounted, setMounted] = useState(false);
  const starsRef = useRef<HTMLCanvasElement | null>(null);
  const sparksRef = useRef<HTMLCanvasElement | null>(null);
  const pulseRef = useRef<HTMLSpanElement | null>(null);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const closeBtnRef = useRef<HTMLButtonElement | null>(null);

  // This scene runs ~15 infinite CSS animations plus three rAF loops for as
  // long as it's open - respect prefers-reduced-motion by not starting the
  // rAF loops at all and by not setting the CSS `animation` property on the
  // decorative elements below (motionOk gates each one individually).
  const [reducedMotion, setReducedMotion] = useState(false);
  useEffect(() => {
    setMounted(true);
    if (typeof window === "undefined" || !window.matchMedia) return undefined;
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    setReducedMotion(mq.matches);
    const onChange = (e: MediaQueryListEvent) => setReducedMotion(e.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);
  const motionOk = !reducedMotion;

  // Escape-to-close, initial focus, a Tab trap confined to the scene, and
  // focus restore to the opener on close - keyed on `open` (like the rAF
  // effect below) since this component stays mounted for the app's whole
  // lifetime and only toggles `open`, so a mount-once effect would never
  // refire on a second open.
  useEffect(() => {
    if (!open) return undefined;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeBtnRef.current?.focus();
    const FOCUSABLE =
      'a[href],button:not([disabled]),select:not([disabled]),input:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        onClose();
        return;
      }
      if (e.key !== "Tab") return;
      const root = dialogRef.current;
      if (!root) return;
      const items = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      if (!(active instanceof Node) || !root.contains(active)) {
        e.preventDefault();
        first.focus();
      } else if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      try {
        opener?.focus();
      } catch {
        // The opener may be gone; focus simply falls to the page.
      }
    };
  }, [open, onClose]);

  // Ported 1:1 from the extracted __bundler/template's componentDidMount -
  // see this file's header comment. Runs fresh on every open (mount), tears
  // down every rAF loop on close (unmount) via the same cancelAnimationFrame
  // sweep the original's componentWillUnmount did.
  useEffect(() => {
    if (!open || reducedMotion) return undefined;
    const rafIds: number[] = [];

    const starsCanvas = starsRef.current;
    if (starsCanvas) {
      const ctx = starsCanvas.getContext("2d");
      const W = starsCanvas.width;
      const H = starsCanvas.height;
      const dots = Array.from({ length: 54 }, () => ({
        x: Math.random() * W,
        y: Math.random() * H,
        r: Math.random() * 1.4 + 0.3,
        a: Math.random() * 0.5 + 0.1,
        tw: Math.random() * 6.28,
        sp: 0.4 + Math.random() * 0.9,
      }));
      let elapsed = 0;
      const draw = () => {
        elapsed += 0.016;
        ctx.clearRect(0, 0, W, H);
        for (const d of dots) {
          const tw = 0.5 + 0.5 * Math.sin(elapsed * d.sp + d.tw);
          ctx.beginPath();
          ctx.arc(d.x, d.y, d.r, 0, 7);
          ctx.fillStyle = "rgba(" + (d.tw > 4 ? "245,181,68" : "127,240,240") + "," + d.a * tw + ")";
          ctx.fill();
        }
        rafIds.push(requestAnimationFrame(draw));
      };
      draw();
    }

    const sparksCanvas = sparksRef.current;
    if (sparksCanvas) {
      const ctx = sparksCanvas.getContext("2d");
      const W = sparksCanvas.width;
      const H = sparksCanvas.height;
      interface Spark {
        x: number;
        y: number;
        vx: number;
        vy: number;
        life: number;
        r: number;
        c: string;
      }
      const parts: Spark[] = [];
      const spawn = () => {
        parts.push({
          x: 340 + Math.random() * 120,
          y: 380 + Math.random() * 90,
          vx: -1.4 - Math.random() * 1.2,
          vy: -0.3 + Math.random() * 0.6,
          life: 1,
          r: 0.8 + Math.random() * 1.6,
          c: Math.random() > 0.5 ? "245,181,68" : "127,240,240",
        });
      };
      let f = 0;
      const draw = () => {
        f++;
        if (f % 6 === 0) spawn();
        ctx.clearRect(0, 0, W, H);
        for (let i = parts.length - 1; i >= 0; i--) {
          const p = parts[i];
          p.x += p.vx;
          p.y += p.vy;
          p.life -= 0.012;
          if (p.life <= 0) {
            parts.splice(i, 1);
            continue;
          }
          ctx.beginPath();
          ctx.arc(p.x, p.y, p.r, 0, 7);
          ctx.fillStyle = "rgba(" + p.c + "," + p.life * 0.8 + ")";
          ctx.shadowBlur = 6;
          ctx.shadowColor = "rgba(" + p.c + "," + p.life + ")";
          ctx.fill();
          ctx.shadowBlur = 0;
        }
        rafIds.push(requestAnimationFrame(draw));
      };
      draw();
    }

    const pulse = pulseRef.current;
    if (pulse) {
      const pts = [
        [62, 46],
        [66, 55],
        [74, 60],
        [82, 66],
        [82, 80],
        [88.5, 53.5],
        [90, 66],
      ];
      let seg = 0;
      let tt = 0;
      const step = () => {
        const a = pts[seg];
        const b = pts[(seg + 1) % pts.length];
        pulse.style.left = a[0] + (b[0] - a[0]) * tt + "%";
        pulse.style.top = a[1] + (b[1] - a[1]) * tt + "%";
        pulse.style.opacity = tt < 0.1 || tt > 0.9 ? "0.3" : "1";
        tt += 0.03;
        if (tt >= 1) {
          tt = 0;
          seg = (seg + 1) % pts.length;
        }
        rafIds.push(requestAnimationFrame(step));
      };
      step();
    }

    return () => rafIds.forEach((id) => cancelAnimationFrame(id));
  }, [open, reducedMotion]);

  // .brand-scene is a hard-coded 1200x760 (globals.css - a deliberate 1:1
  // port of the reference banner's own fixed-pixel scene, see that rule's
  // comment) that cannot fit a phone viewport or a short desktop window;
  // .brand-modal-backdrop's overflow:auto turns that into a scrollbar rather
  // than a fit. Scale the whole scene down (CSS transform, so every child -
  // including the fixed-1200x760 canvases - shrinks together) to the largest
  // size that fits within the viewport's `p-6` (24px) gutter, floored so it
  // never becomes illegibly small; overflow:auto remains as a fallback below
  // that floor.
  const [fitScale, setFitScale] = useState(1);
  useEffect(() => {
    if (!open || typeof window === "undefined") return undefined;
    function update() {
      const s = Math.min(1, (window.innerWidth - 48) / 1200, (window.innerHeight - 48) / 760);
      setFitScale(Math.max(0.28, s));
    }
    update();
    window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, [open]);

  if (!mounted) return null;

  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div
          className="brand-modal-backdrop"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.2 }}
          onClick={onClose}
          role="dialog"
          aria-modal="true"
          aria-label={t("dialogLabel")}
        >
          <div
            style={{
              width: 1200 * fitScale,
              height: 760 * fitScale,
              overflow: "hidden",
              borderRadius: 26,
              flexShrink: 0,
            }}
          >
            <motion.div
              ref={dialogRef}
              className="brand-scene"
              style={{ transformOrigin: "top left" }}
              initial={{ opacity: 0, scale: 0.92 * fitScale, y: 16 }}
              animate={{ opacity: 1, scale: fitScale, y: 0 }}
              exit={{ opacity: 0, scale: 0.95 * fitScale, y: 10 }}
              transition={{ duration: 0.3, ease: [0.16, 1, 0.3, 1] }}
              onClick={(e) => e.stopPropagation()}
            >
              <button
                ref={closeBtnRef}
                type="button"
                className="brand-modal-close"
                onClick={onClose}
                aria-label={t("closeLabel")}
              >
                <X aria-hidden="true" size={18} strokeWidth={2.5} />
              </button>

              <canvas
                ref={starsRef}
                width={1200}
                height={760}
                style={{ position: "absolute", inset: 0, opacity: 0.55 }}
              />

              <div
                style={{
                  position: "absolute",
                  left: "50%",
                  top: "46%",
                  width: 660,
                  height: 660,
                  borderRadius: "50%",
                  background:
                    "radial-gradient(circle, rgba(245,166,35,0.14), rgba(124,92,255,0.10) 42%, transparent 66%)",
                  filter: "blur(3px)",
                  animation: motionOk ? "vgHalo 6s ease-in-out infinite" : "none",
                  pointerEvents: "none",
                }}
              />
              <div
                style={{
                  position: "absolute",
                  left: "50%",
                  top: "46%",
                  width: 520,
                  height: 520,
                  borderRadius: "50%",
                  background: "radial-gradient(circle, rgba(79,227,227,0.10), transparent 62%)",
                  filter: "blur(2px)",
                  animation: motionOk ? "vgHalo2 7.5s ease-in-out infinite" : "none",
                  pointerEvents: "none",
                }}
              />

              <div
                style={{
                  position: "absolute",
                  left: "50%",
                  top: "46%",
                  width: 640,
                  height: 640,
                  margin: "-320px 0 0 -320px",
                  borderRadius: "50%",
                  border: "1.5px dashed rgba(245,181,68,0.20)",
                  animation: motionOk ? "vgSpin 50s linear infinite" : "none",
                  pointerEvents: "none",
                }}
              />
              <div
                style={{
                  position: "absolute",
                  left: "50%",
                  top: "46%",
                  width: 560,
                  height: 560,
                  margin: "-280px 0 0 -280px",
                  borderRadius: "50%",
                  border: "1px dotted rgba(124,92,255,0.22)",
                  animation: motionOk ? "vgSpinR 38s linear infinite" : "none",
                  pointerEvents: "none",
                }}
              />

              <div
                style={{
                  position: "absolute",
                  left: "50%",
                  top: "46%",
                  width: 640,
                  height: 480,
                  margin: "-240px 0 0 -320px",
                  zIndex: 2,
                  opacity: motionOk ? 0 : 1,
                  animation: motionOk ? "vgReveal .9s ease-out .1s forwards" : "none",
                }}
              >
                <div
                  style={{
                    width: "100%",
                    height: "100%",
                    animation: motionOk ? "vgHover 6.8s ease-in-out infinite" : "none",
                  }}
                >
                  <div style={{ position: "relative", width: "100%", height: "100%" }}>
                    <img
                      src="/logo-mark.png"
                      alt={t("logoAlt")}
                      style={{ width: "100%", height: "100%", objectFit: "contain", display: "block" }}
                    />

                    <div
                      style={{
                        position: "absolute",
                        inset: 0,
                        overflow: "hidden",
                        pointerEvents: "none",
                        WebkitMask: 'url("/logo-mark.png") center / contain no-repeat',
                        mask: 'url("/logo-mark.png") center / contain no-repeat',
                      }}
                    >
                      <div
                        style={{
                          position: "absolute",
                          top: 0,
                          bottom: 0,
                          width: "26%",
                          background: "linear-gradient(90deg, transparent, rgba(255,247,224,0.7), transparent)",
                          animation: motionOk ? "vgSheen 5.5s ease-in-out infinite 1s" : "none",
                          mixBlendMode: "screen",
                        }}
                      />
                    </div>

                    <div
                      style={{
                        position: "absolute",
                        left: "60.5%",
                        top: "37%",
                        width: 78,
                        height: 78,
                        margin: "-39px 0 0 -39px",
                        borderRadius: "50%",
                        background: "radial-gradient(circle, rgba(255,214,138,0.6), rgba(245,166,35,0) 70%)",
                        animation: motionOk ? "vgEye 2.6s ease-in-out infinite" : "none",
                        pointerEvents: "none",
                        mixBlendMode: "screen",
                      }}
                    />

                    <div
                      style={{
                        position: "absolute",
                        left: "1%",
                        top: "43%",
                        width: "34%",
                        height: "26%",
                        pointerEvents: "none",
                      }}
                    >
                      <span
                        style={{
                          position: "absolute",
                          left: "34%",
                          top: "4%",
                          width: "44%",
                          height: 4,
                          borderRadius: 3,
                          background: "linear-gradient(90deg,transparent,#c9a2ff,#7c5cff)",
                          animation: motionOk ? "vgTrail 1.7s ease-in-out infinite" : "none",
                        }}
                      />
                      <span
                        style={{
                          position: "absolute",
                          left: "6%",
                          top: "30%",
                          width: "66%",
                          height: 5,
                          borderRadius: 3,
                          background: "linear-gradient(90deg,transparent,#8fb4ff,#4f6fff)",
                          animation: motionOk ? "vgTrail 1.7s ease-in-out infinite 0.18s" : "none",
                        }}
                      />
                      <span
                        style={{
                          position: "absolute",
                          left: "18%",
                          top: "58%",
                          width: "54%",
                          height: 4,
                          borderRadius: 3,
                          background: "linear-gradient(90deg,transparent,#7f9bff,#4fe3e3)",
                          animation: motionOk ? "vgTrail 1.7s ease-in-out infinite 0.36s" : "none",
                        }}
                      />
                      <span
                        style={{
                          position: "absolute",
                          left: "38%",
                          top: "84%",
                          width: "32%",
                          height: 3,
                          borderRadius: 3,
                          background: "linear-gradient(90deg,transparent,#6ad0ff)",
                          animation: motionOk ? "vgTrail 1.7s ease-in-out infinite 0.54s" : "none",
                        }}
                      />
                    </div>

                    <div style={{ position: "absolute", left: "88.5%", top: "53.5%", pointerEvents: "none" }}>
                      <span
                        style={{
                          position: "absolute",
                          width: 15,
                          height: 15,
                          margin: "-7.5px 0 0 -7.5px",
                          borderRadius: "50%",
                          background: "radial-gradient(circle,#8ff0f0,#4fe3e3)",
                          boxShadow: "0 0 12px #4fe3e3",
                          animation: motionOk ? "vgNode 1.6s ease-in-out infinite" : "none",
                        }}
                      />
                      <span
                        style={{
                          position: "absolute",
                          width: 15,
                          height: 15,
                          margin: "-7.5px 0 0 -7.5px",
                          borderRadius: "50%",
                          border: "2px solid #4fe3e3",
                          animation: motionOk ? "vgRipple 1.6s ease-out infinite" : "none",
                        }}
                      />
                    </div>
                    <div style={{ position: "absolute", left: "90%", top: "66%", pointerEvents: "none" }}>
                      <span
                        style={{
                          position: "absolute",
                          width: 14,
                          height: 14,
                          margin: "-7px 0 0 -7px",
                          borderRadius: "50%",
                          background: "radial-gradient(circle,#8ff0f0,#4fe3e3)",
                          boxShadow: "0 0 12px #4fe3e3",
                          animation: motionOk ? "vgNode 1.6s ease-in-out infinite 0.4s" : "none",
                        }}
                      />
                      <span
                        style={{
                          position: "absolute",
                          width: 14,
                          height: 14,
                          margin: "-7px 0 0 -7px",
                          borderRadius: "50%",
                          border: "2px solid #4fe3e3",
                          animation: motionOk ? "vgRipple 1.6s ease-out infinite 0.4s" : "none",
                        }}
                      />
                    </div>
                    <div style={{ position: "absolute", left: "82%", top: "80%", pointerEvents: "none" }}>
                      <span
                        style={{
                          position: "absolute",
                          width: 13,
                          height: 13,
                          margin: "-6.5px 0 0 -6.5px",
                          borderRadius: "50%",
                          background: "radial-gradient(circle,#8ff0f0,#4fe3e3)",
                          boxShadow: "0 0 12px #4fe3e3",
                          animation: motionOk ? "vgNode 1.6s ease-in-out infinite 0.8s" : "none",
                        }}
                      />
                      <span
                        style={{
                          position: "absolute",
                          width: 13,
                          height: 13,
                          margin: "-6.5px 0 0 -6.5px",
                          borderRadius: "50%",
                          border: "2px solid #4fe3e3",
                          animation: motionOk ? "vgRipple 1.6s ease-out infinite 0.8s" : "none",
                        }}
                      />
                    </div>

                    <span
                      ref={pulseRef}
                      style={{
                        position: "absolute",
                        width: 8,
                        height: 8,
                        margin: "-4px 0 0 -4px",
                        borderRadius: "50%",
                        background: "#fff",
                        boxShadow: "0 0 12px #8ff0f0,0 0 22px #4fe3e3",
                        pointerEvents: "none",
                      }}
                    />
                  </div>
                </div>
              </div>

              <canvas
                ref={sparksRef}
                width={1200}
                height={760}
                style={{ position: "absolute", inset: 0, zIndex: 3, pointerEvents: "none" }}
              />
            </motion.div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body
  );
}
