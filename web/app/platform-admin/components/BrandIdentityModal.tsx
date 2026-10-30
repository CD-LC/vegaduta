"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { X } from "lucide-react";
import { useDialogFocus } from "../lib/useDialogFocus";
import { useTranslations } from "../../lib/i18n";

// Ported from web/app/components/BrandIdentityModal.js - see that file's
// header comment for the full story on how this was extracted verbatim from
// the user's uploaded VegaDuta-Banner.html (a self-executing "bundled"
// artifact whose __bundler/manifest and __bundler/template <script> tags
// hold, respectively, the real embedded PNG - already byte-identical to
// /public/logo-mark.png in both apps - and the literal 1200x760 animated
// scene markup this component reproduces). Same "single window" modal
// chrome, same @keyframes (see globals.css), same canvas/pulse logic - kept
// identical across both apps deliberately, since there's no shared
// component library between web/ and web-admin/ (two separate Next.js
// apps).
// Colour literals in the scene below (#4fe3e3 cyan nodes, #c9a2ff/#7c5cff
// and #8fb4ff/#4f6fff trail gradients, #8ff0f0 glow) are the banner
// artwork's own palette, matched to the embedded PNG: they must stay fixed
// in light and dark mode and under every data-accent, so they are left as
// brand-asset literals rather than mapped to theme tokens (A13; this file
// is exempt from the design-tokens/no-hex-literal lint rule by name).
export default function BrandIdentityModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const tHC = useTranslations("hcPlatformadminComponentsBrandIdentityModal");
  const [mounted, setMounted] = useState(false);
  const starsRef = useRef(null);
  const sparksRef = useRef(null);
  const pulseRef = useRef(null);
  const dialogRef = useRef(null);
  useDialogFocus(open, dialogRef);

  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (!open) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  useEffect(() => {
    if (!open) return undefined;
    const rafIds: number[] = [];
    // Respect prefers-reduced-motion for the JS-driven canvas/pulse loops -
    // the CSS keyframe animations elsewhere in this scene are handled by the
    // @media (prefers-reduced-motion: reduce) override in globals.css, but
    // these three requestAnimationFrame loops run continuously regardless of
    // that setting unless explicitly gated here too.
    const reduceMotion =
      typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

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
      let t = 0;
      const draw = () => {
        t += 0.016;
        ctx.clearRect(0, 0, W, H);
        for (const d of dots) {
          const tw = 0.5 + 0.5 * Math.sin(t * d.sp + d.tw);
          ctx.beginPath();
          ctx.arc(d.x, d.y, d.r, 0, 7);
          ctx.fillStyle = "rgba(" + (d.tw > 4 ? "245,181,68" : "127,240,240") + "," + d.a * tw + ")";
          ctx.fill();
        }
        if (!reduceMotion) rafIds.push(requestAnimationFrame(draw));
      };
      draw();
    }

    const sparksCanvas = sparksRef.current;
    if (sparksCanvas && !reduceMotion) {
      const ctx = sparksCanvas.getContext("2d");
      const W = sparksCanvas.width;
      const H = sparksCanvas.height;
      const parts: { x: number; y: number; vx: number; vy: number; life: number; r: number; c: string }[] = [];
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
    if (pulse && !reduceMotion) {
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
          aria-label={tHC("s1")}
        >
          <motion.div
            ref={dialogRef}
            className="brand-scene outline-none"
            initial={{ opacity: 0, scale: 0.92, y: 16 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.95, y: 10 }}
            transition={{ duration: 0.3, ease: [0.16, 1, 0.3, 1] }}
            onClick={(e) => e.stopPropagation()}
            tabIndex={-1}
          >
            <button type="button" className="brand-modal-close" onClick={onClose} aria-label="Close">
              <X size={18} strokeWidth={2.5} />
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
                animation: "vgHalo 6s ease-in-out infinite",
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
                animation: "vgHalo2 7.5s ease-in-out infinite",
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
                animation: "vgSpin 50s linear infinite",
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
                animation: "vgSpinR 38s linear infinite",
                pointerEvents: "none",
              }}
            />

            <div
              className="vg-reveal"
              style={{
                position: "absolute",
                left: "50%",
                top: "46%",
                width: 640,
                height: 480,
                margin: "-240px 0 0 -320px",
                zIndex: 2,
                opacity: 0,
                animation: "vgReveal .9s ease-out .1s forwards",
              }}
            >
              <div style={{ width: "100%", height: "100%", animation: "vgHover 6.8s ease-in-out infinite" }}>
                <div style={{ position: "relative", width: "100%", height: "100%" }}>
                  <img
                    src="/logo-mark.png"
                    alt="VegaDūta"
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
                        animation: "vgSheen 5.5s ease-in-out infinite 1s",
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
                      animation: "vgEye 2.6s ease-in-out infinite",
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
                        animation: "vgTrail 1.7s ease-in-out infinite",
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
                        animation: "vgTrail 1.7s ease-in-out infinite 0.18s",
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
                        animation: "vgTrail 1.7s ease-in-out infinite 0.36s",
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
                        animation: "vgTrail 1.7s ease-in-out infinite 0.54s",
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
                        animation: "vgNode 1.6s ease-in-out infinite",
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
                        animation: "vgRipple 1.6s ease-out infinite",
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
                        animation: "vgNode 1.6s ease-in-out infinite 0.4s",
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
                        animation: "vgRipple 1.6s ease-out infinite 0.4s",
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
                        animation: "vgNode 1.6s ease-in-out infinite 0.8s",
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
                        animation: "vgRipple 1.6s ease-out infinite 0.8s",
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
        </motion.div>
      )}
    </AnimatePresence>,
    document.body
  );
}
