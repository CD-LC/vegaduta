"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import dynamic from "next/dynamic";
import Image from "next/image";
import { motion } from "motion/react";
import { RefreshCw, Loader2, ShieldCheck, PlugZap, Lock, Database, Clock, Gamepad2 } from "lucide-react";
import { Button } from "./ui/button";
import { Card } from "./ui/card";
import { Badge } from "./ui/badge";
import { cn } from "../lib/utils";
import { useReducedMotionSafe } from "../lib/useReducedMotionSafe";
import { useTranslations } from "../lib/i18n";

// Full-screen "We'll be back" takeover, shown by AppShell when the platform
// core is unreachable (see SystemHealthProvider). Deliberately self-contained
// and dependency-light — it renders when the backend is DOWN, so it must not
// need any live data. Theme-aware via tokens; the health provider keeps polling
// underneath, so this clears itself automatically the moment core recovers.
//
// Redesign (2026-07-27, following a live incident where core briefly went
// unreachable behind the reverse proxy): visual refresh only — the countdown/
// retry mechanics below are untouched and load-bearing. When it reaches zero
// it fires the same onRetry() the button does, so the progress bar the person
// is watching maps to a REAL reconnect attempt, not a fake animation. Cadence
// matches SystemHealthProvider's own POLL_MS so the two stay in step.
const RETRY_SECONDS = 20;
// How long the "Checking…" state stays visible after a probe fires. Purely
// presentational — long enough to read, short enough not to feel stuck.
const CHECKING_MS = 1200;

// Grounded reassurance points only — no fabricated multi-service status
// dashboard. Every claim here matches what SecuritySection on the landing
// page already states as a real platform property.
// The mini-game chunk is only fetched when the person actually taps "Play
// while you wait" — MaintenanceScreen itself ships in the main bundle (it
// must render when the backend is down), so the game must not add to that
// weight. ssr:false also keeps it out of every server render.
const MiniCatchGame = dynamic(() => import("./MiniCatchGame"), { ssr: false });

const REASSURANCES = [
  { icon: Lock, text: "Encrypted at rest — nothing here was exposed" },
  { icon: Database, text: "Your data and workspace are untouched" },
  { icon: Clock, text: "This is a connectivity blip, not data loss" },
];

// `maintenance` (2026-10): the same takeover doubles as the PLANNED
// maintenance screen (web-admin /maintenance -> /api/public/status or a 503
// {error:"maintenance"}). When set, the headline/body/badge say "scheduled
// maintenance" and show the operator's message and expected end instead of
// the "connectivity blip" copy; the retry loop re-polls the public status.
export default function MaintenanceScreen({
  onRetry,
  maintenance,
}: {
  onRetry?: () => void;
  maintenance?: { message: string | null; endsAt: string | null };
}) {
  const tHC = useTranslations("hcComponentsMaintenanceScreen");
  const t = useTranslations("platformStatus");
  const reduceMotion = useReducedMotionSafe();
  const endsAtText = (() => {
    if (!maintenance?.endsAt) return null;
    const d = new Date(maintenance.endsAt);
    return Number.isNaN(d.getTime()) ? null : d.toLocaleString();
  })();
  const [showGame, setShowGame] = useState(false);
  const [remaining, setRemaining] = useState(RETRY_SECONDS);
  const [attempt, setAttempt] = useState(1);
  const [checking, setChecking] = useState(false);
  // onRetry identity can change between renders; keep the interval closure
  // pointed at the latest one without restarting the timer every render.
  const retryRef = useRef(onRetry);
  retryRef.current = onRetry;

  const fire = useCallback(() => {
    setChecking(true);
    setAttempt((a) => a + 1);
    setRemaining(RETRY_SECONDS);
    retryRef.current?.();
    const t = window.setTimeout(() => setChecking(false), CHECKING_MS);
    return () => window.clearTimeout(t);
  }, []);

  useEffect(() => {
    const id = window.setInterval(() => {
      setRemaining((r) => {
        if (r <= 1) {
          fire();
          return RETRY_SECONDS;
        }
        return r - 1;
      });
    }, 1000);
    return () => window.clearInterval(id);
  }, [fire]);

  const progress = ((RETRY_SECONDS - remaining) / RETRY_SECONDS) * 100;

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background px-6 py-16 text-foreground">
      {/* Layered ambient wash — two soft brand blooms plus a faint dot grid for
          texture, matching the landing page's radial-gradient accent language
          rather than a flat single glow. Kept very low-opacity so it reads as
          depth, not decoration. */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0"
        style={{
          backgroundImage:
            "radial-gradient(52rem 28rem at 18% -8%, color-mix(in srgb, var(--primary) 16%, transparent), transparent 65%)," +
            "radial-gradient(46rem 26rem at 85% 105%, color-mix(in srgb, var(--primary) 10%, transparent), transparent 60%)," +
            "radial-gradient(circle, color-mix(in srgb, var(--foreground) 6%, transparent) 1px, transparent 1px)",
          backgroundSize: "auto, auto, 22px 22px",
        }}
      />

      <motion.div
        initial={reduceMotion ? false : { opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
        className="relative w-full max-w-lg"
      >
        <Card className="gap-0 overflow-hidden border-border/70 p-0 shadow-xl">
          {/* Top status rail — the "live" edge of the card. */}
          <div className="flex items-center justify-between gap-3 border-b border-border bg-secondary/30 px-5 py-3">
            <Badge variant="outline" className="gap-2 font-medium">
              <span className="relative flex size-2">
                {!reduceMotion && (
                  <span
                    className="absolute inline-flex size-full animate-ping rounded-full opacity-70"
                    style={{ backgroundColor: "var(--state-warning)" }}
                  />
                )}
                <span
                  className="relative inline-flex size-2 rounded-full"
                  style={{ backgroundColor: "var(--state-warning)" }}
                />
              </span>
              {maintenance ? t("maintenanceBadge") : "Service interruption"}
            </Badge>
            <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
              <ShieldCheck className="size-3.5" style={{ color: "var(--state-success)" }} aria-hidden="true" />
              {tHC("s7")}
            </span>
          </div>

          <div className="flex flex-col items-center px-7 pb-7 pt-9 text-center">
            <div className="mb-5 flex items-center gap-2.5">
              <Image
                src="/logo-mark.png"
                alt=""
                height={36}
                width={49}
                className="h-9 w-auto object-contain"
                draggable={false}
              />
              <span className="brand-name text-lg font-semibold tracking-tight opacity-90">
                Vega<span className="text-primary">Dūta</span>
              </span>
            </div>

            <h1 className="text-balance text-2xl font-semibold tracking-tight sm:text-[1.75rem]">
              {maintenance ? t("maintenanceTitle") : <>{tHC("s6")}</>}
            </h1>
            {maintenance ? (
              <>
                <p className="mt-2.5 max-w-sm whitespace-pre-line text-pretty text-sm leading-relaxed text-muted-foreground">
                  {maintenance.message || t("maintenanceDefaultMessage")}
                </p>
                {endsAtText && (
                  <p className="mt-2 text-xs font-medium text-foreground">
                    {t("maintenanceEndsAt", { time: endsAtText })}
                  </p>
                )}
              </>
            ) : (
              <p className="mt-2.5 max-w-sm text-pretty text-sm leading-relaxed text-muted-foreground">{tHC("s5")}</p>
            )}

            {/* Reassurance row — grounded, real platform properties, not a
                fabricated status dashboard. */}
            <div className={`mt-6 grid w-full gap-2 ${maintenance ? "sm:grid-cols-2" : "sm:grid-cols-3"}`}>
              {REASSURANCES.filter((r) => !maintenance || r.icon !== Clock).map(({ icon: Icon, text }) => (
                <div
                  key={text}
                  className="flex flex-col items-center gap-1.5 rounded-xl border border-border/60 bg-secondary/20 px-3 py-2.5 text-center"
                >
                  <Icon className="size-3.5 text-primary" aria-hidden="true" />
                  <span className="text-[11px] leading-tight text-muted-foreground">{text}</span>
                </div>
              ))}
            </div>

            {/* Reconnect progress — the bar fills toward the next real probe.
                Only the STATE label (checking vs waiting) is announced; it's
                wrapped in its own live region so an update fires solely on a
                real transition. The countdown/attempt number re-renders every
                second and would otherwise queue a screen-reader announcement
                on every tick, so it sits outside that region (aria-hidden). */}
            <div className="mt-7 w-full">
              <div className="mb-2 flex items-center justify-between text-xs">
                <span
                  role="status"
                  aria-live="polite"
                  className="inline-flex items-center gap-1.5 font-medium text-foreground"
                >
                  {checking ? (
                    <>
                      <Loader2 className="size-3.5 animate-spin text-primary" aria-hidden="true" />
                      {tHC("s4")}
                    </>
                  ) : (
                    <>
                      <PlugZap className="size-3.5 text-primary" aria-hidden="true" />
                      {tHC("s3")}
                    </>
                  )}
                </span>
                <span className="tabular-nums text-muted-foreground" aria-hidden="true">
                  {checking ? `Attempt ${attempt}` : `Retrying in ${remaining}s`}
                </span>
              </div>

              <div className="h-1.5 w-full overflow-hidden rounded-full bg-secondary">
                <motion.div
                  className="h-full rounded-full bg-gradient-to-r from-primary/70 via-primary to-primary/70"
                  initial={false}
                  animate={{ width: `${checking ? 100 : progress}%` }}
                  transition={reduceMotion ? { duration: 0 } : { duration: 0.9, ease: "linear" }}
                />
              </div>
            </div>

            <Button variant="outline" size="sm" className="mt-6" onClick={fire} disabled={checking}>
              <RefreshCw className={cn("size-4", checking && "animate-spin")} aria-hidden="true" />
              {checking ? "Checking…" : "Retry now"}
            </Button>

            {/* Offline mini-game — fully client-side, so it works exactly
                when this screen shows (backend down). Behind a toggle so the
                page stays calm by default and the game chunk is only fetched
                on demand. */}
            {showGame ? (
              <MiniCatchGame />
            ) : (
              <button
                type="button"
                onClick={() => setShowGame(true)}
                className="mt-5 inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
              >
                <Gamepad2 className="size-3.5" aria-hidden="true" />
                {tHC("s2")}
              </button>
            )}
          </div>
        </Card>

        <p className="mt-4 text-center text-xs text-muted-foreground">{tHC("s1")}</p>
      </motion.div>
    </div>
  );
}
