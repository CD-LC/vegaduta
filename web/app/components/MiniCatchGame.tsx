"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "./ui/button";
import { useTranslations } from "../lib/i18n";

// Tiny offline mini-game for the MaintenanceScreen ("We'll be back" page).
// Deliberately featherweight: one <canvas>, requestAnimationFrame, zero
// assets, zero dependencies beyond React + the shared Button — it renders
// when the backend is DOWN, so like MaintenanceScreen itself it must not
// need anything from the network. Only ever mounted after the person taps
// "Play while you wait", so it adds nothing to the page's working set until
// asked for.
//
// The game: sparks fall, you slide the tray to catch them. Arrow keys / A-D,
// mouse, or touch. Speed ramps with score; three misses ends the run. Best
// score persists in localStorage so the wait feels less like a dead end.

const W = 320;
const H = 220;
const TRAY_W = 56;
const TRAY_H = 10;
const BEST_KEY = "vd-minigame-best";

type Spark = { x: number; y: number; v: number; r: number };

export default function MiniCatchGame() {
  const tHC = useTranslations("hcComponentsMiniCatchGame");
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [running, setRunning] = useState(true);
  const [score, setScore] = useState(0);
  const [best, setBest] = useState(0);
  const [over, setOver] = useState(false);
  // Restart is done by remounting the effect via this counter — keeps every
  // bit of game state inside the effect closure, nothing to reset by hand.
  const [round, setRound] = useState(0);

  useEffect(() => {
    try {
      setBest(Number(window.localStorage.getItem(BEST_KEY)) || 0);
    } catch {
      // Storage blocked (private mode) - best just stays session-local.
    }
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return undefined;
    const ctx = canvas.getContext("2d");
    if (!ctx) return undefined;

    // Crisp on retina without shipping a bigger backing store than needed.
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = W * dpr;
    canvas.height = H * dpr;
    ctx.scale(dpr, dpr);

    let trayX = W / 2;
    let targetX = W / 2;
    let sparks: Spark[] = [];
    let lives = 3;
    let points = 0;
    let spawnIn = 0;
    let raf = 0;
    let last = performance.now();
    let alive = true;
    let paused = false;

    // The active accent colour, read from computed styles once per round
    // instead of a hardcoded indigo literal, so the game follows whichever
    // accent the tenant/visitor has actually chosen instead of clashing with it.
    const accent = (getComputedStyle(canvas).getPropertyValue("--primary") || "").trim() || "#6366f1";

    const keys = { left: false, right: false };

    function spawn() {
      sparks.push({
        x: 14 + Math.random() * (W - 28),
        y: -8,
        v: 55 + Math.random() * 40 + Math.min(points * 2.2, 130),
        r: 4 + Math.random() * 2.5,
      });
    }

    function draw() {
      if (!ctx) return;
      ctx.clearRect(0, 0, W, H);

      // Sparks — warm dot with a soft halo. Fixed colors chosen to read on
      // both themes (the canvas backdrop is the card's own background).
      for (const s of sparks) {
        ctx.beginPath();
        ctx.arc(s.x, s.y, s.r + 3, 0, Math.PI * 2);
        ctx.globalAlpha = 0.25;
        ctx.fillStyle = accent;
        ctx.fill();
        ctx.globalAlpha = 1;
        ctx.beginPath();
        ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
        ctx.fillStyle = accent;
        ctx.fill();
      }

      // Tray
      ctx.beginPath();
      const rx = trayX - TRAY_W / 2;
      const ry = H - 18;
      ctx.roundRect(rx, ry, TRAY_W, TRAY_H, 5);
      ctx.fillStyle = accent;
      ctx.fill();

      // Lives — small dots top-right.
      for (let i = 0; i < 3; i++) {
        ctx.beginPath();
        ctx.arc(W - 12 - i * 12, 12, 3.5, 0, Math.PI * 2);
        ctx.globalAlpha = i < lives ? 1 : 0.35;
        ctx.fillStyle = i < lives ? accent : "rgb(148, 163, 184)";
        ctx.fill();
        ctx.globalAlpha = 1;
      }
    }

    function tick(now: number) {
      if (!alive) return;
      const dt = Math.min((now - last) / 1000, 0.05);
      last = now;

      if (!paused) {
        if (keys.left) targetX -= 240 * dt;
        if (keys.right) targetX += 240 * dt;
        targetX = Math.max(TRAY_W / 2, Math.min(W - TRAY_W / 2, targetX));
        // Eased follow so mouse/touch feels smooth rather than teleporty.
        trayX += (targetX - trayX) * Math.min(1, dt * 14);

        spawnIn -= dt;
        if (spawnIn <= 0) {
          spawn();
          spawnIn = Math.max(0.28, 0.9 - points * 0.012);
        }

        for (const s of sparks) s.y += s.v * dt;

        const kept: Spark[] = [];
        for (const s of sparks) {
          const caught = s.y >= H - 22 && s.y <= H - 6 && Math.abs(s.x - trayX) <= TRAY_W / 2 + s.r;
          if (caught) {
            points += 1;
            setScore(points);
          } else if (s.y > H + 8) {
            lives -= 1;
            if (lives <= 0) {
              alive = false;
              setOver(true);
              setBest((b) => {
                const nb = Math.max(b, points);
                try {
                  window.localStorage.setItem(BEST_KEY, String(nb));
                } catch {
                  // best stays session-local
                }
                return nb;
              });
            }
          } else {
            kept.push(s);
          }
        }
        sparks = kept;
      }

      draw();
      if (alive) raf = window.requestAnimationFrame(tick);
    }

    function onKey(e: KeyboardEvent, down: boolean) {
      if (e.key === "ArrowLeft" || e.key === "a" || e.key === "A") {
        keys.left = down;
        e.preventDefault();
      } else if (e.key === "ArrowRight" || e.key === "d" || e.key === "D") {
        keys.right = down;
        e.preventDefault();
      }
    }
    const onKeyDown = (e: KeyboardEvent) => onKey(e, true);
    const onKeyUp = (e: KeyboardEvent) => onKey(e, false);

    function pointTo(clientX: number) {
      const rect = canvas!.getBoundingClientRect();
      targetX = ((clientX - rect.left) / rect.width) * W;
    }
    const onMove = (e: MouseEvent) => pointTo(e.clientX);
    const onTouch = (e: TouchEvent) => {
      if (e.touches[0]) {
        pointTo(e.touches[0].clientX);
        e.preventDefault();
      }
    };
    // Don't burn frames (or battery) while the tab is hidden — the whole
    // point of this page is that someone may leave it open for a while.
    const onVis = () => {
      paused = document.hidden;
      last = performance.now();
    };

    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("keyup", onKeyUp);
    canvas.addEventListener("mousemove", onMove);
    canvas.addEventListener("touchmove", onTouch, { passive: false });
    canvas.addEventListener("touchstart", onTouch, { passive: false });
    document.addEventListener("visibilitychange", onVis);

    setScore(0);
    setOver(false);
    raf = window.requestAnimationFrame(tick);

    return () => {
      alive = false;
      window.cancelAnimationFrame(raf);
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("keyup", onKeyUp);
      canvas.removeEventListener("mousemove", onMove);
      canvas.removeEventListener("touchmove", onTouch);
      canvas.removeEventListener("touchstart", onTouch);
      document.removeEventListener("visibilitychange", onVis);
    };
  }, [round]);

  if (!running) return null;

  return (
    <div className="mt-5 flex w-full flex-col items-center">
      <div className="mb-2 flex w-full max-w-[320px] items-center justify-between text-xs text-muted-foreground">
        <span className="tabular-nums">
          Score <span className="font-semibold text-foreground">{score}</span>
        </span>
        <span className="tabular-nums">
          Best <span className="font-semibold text-foreground">{best}</span>
        </span>
      </div>
      <div className="relative w-full max-w-[320px]">
        <canvas
          ref={canvasRef}
          style={{ width: "100%", height: "auto", aspectRatio: `${W} / ${H}`, touchAction: "none" }}
          className="block rounded-xl border border-border/70 bg-secondary/20"
          aria-label={tHC("s5")}
        />
        {over && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded-xl bg-background/85 backdrop-blur-[2px]">
            <div className="text-sm font-semibold">{score >= best && score > 0 ? "New best!" : "Nice run!"}</div>
            <div className="text-xs text-muted-foreground">
              {tHC("s4")} {score} spark{score === 1 ? "" : "s"}
            </div>
            <Button size="sm" className="mt-1" onClick={() => setRound((r) => r + 1)}>
              {tHC("s3")}
            </Button>
          </div>
        )}
      </div>
      <p className="mt-2 text-[11px] text-muted-foreground">{tHC("s2")}</p>
      <button
        type="button"
        onClick={() => setRunning(false)}
        className="mt-1 border-0 bg-transparent p-0 text-[11px] text-muted-foreground underline-offset-2 hover:underline"
      >
        {tHC("s1")}
      </button>
    </div>
  );
}
