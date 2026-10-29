"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useRouter } from "next/navigation";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Check, Sparkles, Rocket, ArrowRight, X } from "lucide-react";
import { Button } from "./ui/button";
import { useTranslations } from "../lib/i18n";

// Post-upgrade "celebration" overlay (#celebration). Shown once, right after a
// tenant's plan upgrade is CONFIRMED, as a full-screen modal with a tasteful
// confetti burst + the concrete benefits their new tier unlocks.
//
// TRIGGER CONTRACT — this component is intentionally decoupled from the
// Razorpay *handoff*. UpgradePrompt's checkout `handler` is explicitly a UX
// handoff, not confirmation (the /webhook/razorpay endpoint confirms the
// charge server-side "shortly", see that file's comment) — so the frontend has
// no confirmed-success signal at checkout close. Instead, whoever lands the
// user back on the console after a CONFIRMED upgrade should route to:
//     /admin/billing?upgraded=PREMIUM   (or ?upgraded=UNLIMITED)
// The billing page reads that param on mount, shows this once, then strips the
// param so a refresh never replays it. (When the payment flow gains a real
// post-confirmation return/redirect, point it at that URL — no change here.)
//
// Benefit copy is derived per-plan and kept EXACT to control_plane.plan_limits
// (V101__plan_limits.sql): FREE 2/5/3/1 no-BYOK/no-WA; PREMIUM 10/25/15/5 +BYOK
// +WhatsApp; UNLIMITED unlimited + white-label. Inline copy (no new i18n keys)
// follows the landing/Academy precedent for freshly-authored surfaces and keeps
// the fragile en.json untouched.

type PlanBenefits = {
  name: string;
  tagline: string;
  benefits: string[];
};

const PLAN_BENEFITS: Record<string, PlanBenefits> = {
  PREMIUM: {
    name: "Premium",
    tagline: "Your workspace just leveled up.",
    benefits: [
      "Up to 10 team members",
      "25 AI agents to deploy",
      "15 automated workflows",
      "5 knowledge bases",
      "Bring your own model keys (BYOK)",
      "WhatsApp channel for your agents",
    ],
  },
  UNLIMITED: {
    name: "Unlimited",
    tagline: "Everything, uncapped.",
    benefits: [
      "Unlimited team members",
      "Unlimited AI agents",
      "Unlimited automated workflows",
      "Unlimited knowledge bases",
      "Bring your own model keys (BYOK)",
      "WhatsApp channel for your agents",
      "White-label branding",
    ],
  },
};

// Confetti palette — brand indigo-violet lead + a few complementary accents so
// the burst reads celebratory without leaving the brand. Resolved to literals
// (canvas can't read CSS custom properties) but anchored on the app's violet.
const CONFETTI_COLORS = ["#8b83f8", "#a78bfa", "#6366f1", "#f0abfc", "#fde68a", "#5eead4"];

// Elements a keyboard user can land on while tabbing inside the card - same
// selector/trap pattern as UpgradePrompt.tsx, used to wrap Tab/Shift+Tab so
// focus can't escape into the page behind this (aria-modal) dialog.
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

type Particle = {
  x: number;
  y: number;
  vx: number;
  vy: number;
  size: number;
  rot: number;
  vrot: number;
  color: string;
  shape: "rect" | "circle";
};

/** Lightweight self-contained confetti burst — no dependency. Runs a single
 *  gravity-driven RAF pass, fades over its lifetime, then stops on its own. */
function ConfettiCanvas() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return undefined;
    const ctx = canvas.getContext("2d");
    if (!ctx) return undefined;

    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    let width = window.innerWidth;
    let height = window.innerHeight;
    const resize = () => {
      width = window.innerWidth;
      height = window.innerHeight;
      canvas.width = width * dpr;
      canvas.height = height * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    window.addEventListener("resize", resize);

    // Two origins near the top so the burst spans the width, cannon-style.
    const origins = [
      { x: width * 0.28, y: height * 0.18 },
      { x: width * 0.72, y: height * 0.18 },
    ];
    const particles: Particle[] = [];
    const perOrigin = 70;
    for (const origin of origins) {
      for (let i = 0; i < perOrigin; i += 1) {
        const angle = -Math.PI / 2 + (Math.random() - 0.5) * 1.6;
        const speed = 6 + Math.random() * 8;
        particles.push({
          x: origin.x,
          y: origin.y,
          vx: Math.cos(angle) * speed + (Math.random() - 0.5) * 2,
          vy: Math.sin(angle) * speed,
          size: 5 + Math.random() * 6,
          rot: Math.random() * Math.PI,
          vrot: (Math.random() - 0.5) * 0.3,
          color: CONFETTI_COLORS[Math.floor(Math.random() * CONFETTI_COLORS.length)],
          shape: Math.random() > 0.5 ? "rect" : "circle",
        });
      }
    }

    const gravity = 0.16;
    const drag = 0.992;
    const start = performance.now();
    const lifespan = 2600; // ms — fade to nothing, then stop the loop.
    let raf = 0;

    const tick = (now: number) => {
      const elapsed = now - start;
      const alpha = Math.max(0, 1 - elapsed / lifespan);
      ctx.clearRect(0, 0, width, height);
      for (const p of particles) {
        p.vx *= drag;
        p.vy = p.vy * drag + gravity;
        p.x += p.vx;
        p.y += p.vy;
        p.rot += p.vrot;
        ctx.save();
        ctx.globalAlpha = alpha;
        ctx.translate(p.x, p.y);
        ctx.rotate(p.rot);
        ctx.fillStyle = p.color;
        if (p.shape === "rect") {
          ctx.fillRect(-p.size / 2, -p.size / 2, p.size, p.size * 0.6);
        } else {
          ctx.beginPath();
          ctx.arc(0, 0, p.size / 2, 0, Math.PI * 2);
          ctx.fill();
        }
        ctx.restore();
      }
      if (elapsed < lifespan) {
        raf = requestAnimationFrame(tick);
      } else {
        ctx.clearRect(0, 0, width, height);
      }
    };
    raf = requestAnimationFrame(tick);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
    };
  }, []);

  return <canvas ref={canvasRef} aria-hidden className="pointer-events-none fixed inset-0 z-[1] h-full w-full" />;
}

/**
 * @param open  whether the celebration is showing
 * @param plan  the plan just upgraded to (e.g. "PREMIUM" / "UNLIMITED";
 *   case-insensitive). Unknown/FREE values render nothing.
 * @param onDismiss  called on the close button, backdrop click, Escape, or
 *   after the "Go to dashboard" hand-off — the parent uses this to clear the
 *   `?upgraded` param so the celebration shows exactly once.
 */
export default function UpgradeCelebration({
  open,
  plan,
  onDismiss,
}: {
  open: boolean;
  plan: string;
  onDismiss: () => void;
}) {
    const tHC = useTranslations("hcComponentsUpgradeCelebration");
  const router = useRouter();
  const prefersReducedMotion = useReducedMotion();
  const [mounted, setMounted] = useState(false);
  const cardRef = useRef<HTMLDivElement | null>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);

  // Persist the last non-empty plan so benefit copy stays intact through the
  // exit animation (parent clears `plan` the instant it dismisses).
  const [displayPlan, setDisplayPlan] = useState(plan);
  useEffect(() => {
    if (open && plan) setDisplayPlan(plan);
  }, [open, plan]);

  useEffect(() => setMounted(true), []);

  const info = useMemo(() => {
    const key = (displayPlan || "").toUpperCase();
    return PLAN_BENEFITS[key] || null;
  }, [displayPlan]);

  // Escape-to-close, plus a Tab/Shift+Tab trap that keeps focus cycling
  // within the card's own focusable elements while it's open (same pattern
  // as UpgradePrompt.tsx/LanguageChoicePrompt.tsx) so Tab can't escape into
  // the blurred page behind this modal.
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onDismiss();
        return;
      }
      if (e.key === "Tab" && cardRef.current) {
        const focusable = cardRef.current.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR);
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onDismiss]);

  // Move focus into the dialog on open; restore it to the trigger on close.
  useEffect(() => {
    if (!open || !mounted) return undefined;
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    cardRef.current?.focus();
    return () => {
      previousFocusRef.current?.focus?.();
    };
  }, [open, mounted]);

  if (!mounted) return null;

  const goToDashboard = () => {
    onDismiss();
    router.push("/");
  };

  return createPortal(
    <AnimatePresence>
      {open && info && (
        <motion.div
          key="upgrade-celebration"
          className="fixed inset-0 z-[1000] flex items-start justify-center overflow-y-auto p-4"
          style={{ background: "rgba(4,5,12,0.72)", backdropFilter: "blur(6px)" }}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: prefersReducedMotion ? 0 : 0.25 }}
          onClick={onDismiss}
        >
          {!prefersReducedMotion && <ConfettiCanvas />}

          <motion.div
            ref={cardRef}
            tabIndex={-1}
            className="relative z-[2] my-auto w-full max-w-md rounded-2xl border border-primary/40 bg-card p-7 text-card-foreground shadow-2xl outline-none sm:p-8"
            style={{ boxShadow: "0 0 0 1px var(--primary), 0 24px 70px -20px rgba(0,0,0,0.7)" }}
            initial={prefersReducedMotion ? { opacity: 0 } : { opacity: 0, scale: 0.9, y: 16 }}
            animate={prefersReducedMotion ? { opacity: 1 } : { opacity: 1, scale: 1, y: 0 }}
            exit={prefersReducedMotion ? { opacity: 0 } : { opacity: 0, scale: 0.95, y: 8 }}
            transition={{ duration: prefersReducedMotion ? 0 : 0.35, ease: [0.16, 1, 0.3, 1] }}
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label={`You're now on the ${info.name} plan`}
          >
            <Button
              type="button"
              variant="ghost"
              size="icon"
              onClick={onDismiss}
              aria-label="Dismiss"
              className="absolute right-3 top-3 text-muted-foreground"
            >
              <X aria-hidden="true" size={16} />
            </Button>

            {/* Emblem */}
            <motion.div
              className="mx-auto flex size-16 items-center justify-center rounded-full bg-primary/15 text-primary ring-1 ring-primary/40"
              initial={prefersReducedMotion ? undefined : { scale: 0.4, opacity: 0 }}
              animate={prefersReducedMotion ? undefined : { scale: 1, opacity: 1 }}
              transition={{ delay: 0.08, type: "spring", stiffness: 260, damping: 18 }}
            >
              <Rocket aria-hidden="true" size={30} strokeWidth={1.75} />
            </motion.div>

            <p className="mt-5 flex items-center justify-center gap-1.5 text-center text-xs font-medium uppercase tracking-[0.18em] text-primary">
              <Sparkles aria-hidden="true" size={13} /> {tHC("hcComponentsUpgradeCelebration.s5")}
            </p>
            <h2 className="mt-2 text-balance text-center text-2xl font-semibold tracking-tight sm:text-[1.7rem]">
              {tHC("hcComponentsUpgradeCelebration.s4")} {info.name}!
            </h2>
            <p className="mx-auto mt-2 max-w-sm text-center text-sm leading-relaxed text-muted-foreground">
              {info.tagline} {tHC("hcComponentsUpgradeCelebration.s3")}
            </p>

            <ul className="mt-6 flex list-none flex-col gap-2.5 p-0">
              {info.benefits.map((benefit, i) => (
                <motion.li
                  key={benefit}
                  className="flex items-center gap-3 rounded-lg border border-border/70 bg-secondary/30 px-3.5 py-2.5 text-sm"
                  initial={prefersReducedMotion ? undefined : { opacity: 0, x: -8 }}
                  animate={prefersReducedMotion ? undefined : { opacity: 1, x: 0 }}
                  transition={{ delay: 0.15 + i * 0.06, duration: 0.3 }}
                >
                  <span className="flex size-5 flex-shrink-0 items-center justify-center rounded-full bg-primary/20 text-primary">
                    <Check aria-hidden="true" size={13} strokeWidth={3} />
                  </span>
                  <span>{benefit}</span>
                </motion.li>
              ))}
            </ul>

            <div className="mt-7 flex flex-col gap-2.5">
              <Button type="button" className="w-full" onClick={goToDashboard}>
                {tHC("hcComponentsUpgradeCelebration.s2")} <ArrowRight aria-hidden="true" size={15} />
              </Button>
              <Button type="button" variant="ghost" className="w-full text-muted-foreground" onClick={onDismiss}>
                {tHC("hcComponentsUpgradeCelebration.s1")}
              </Button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body
  );
}
