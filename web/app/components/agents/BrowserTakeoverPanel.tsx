"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Hand, MonitorSmartphone } from "lucide-react";
import { api } from "../../lib/api";
import type { BrowserTakeoverBegin, BrowserTakeoverInput, BrowserTakeoverView } from "../../lib/api/browserTakeover";
import {
  DEFAULT_MAX_TYPE_CHARS,
  formatCountdown,
  isTakeoverOpen,
  keyToInput,
  pasteToInput,
  pointerToFramePoint,
  remainingSeconds,
  tokenRefreshDelayMs,
  wheelToInput,
} from "../../lib/browserTakeover";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { SseParser } from "../workflow/liveRunStream";
import { useBrowserTakeoverCapabilities } from "./useBrowserTakeoverCapabilities";

// Human takeover of the agent's browser: when the agent asks for help (a captcha, a 2FA prompt, a login) - or the
// person pauses it - this shows what the headless page shows, relays clicks / keys / scroll to it, and says
// plainly that the agent is paused. "Hand back to agent" returns control; the agent only learns that the step is
// done plus the page text read back after secrets are scrubbed. What is typed goes to the page and nowhere else:
// it is never stored, logged or put in the chat here.
//
// Self-hiding: while core's takeover flag is off (the default) the capability probe 404s, this renders null and
// nothing is polled, so a deployment that never enabled it looks exactly as before. Mounted next to
// PendingToolApprovals in the chat surfaces (ChatPanel, AgentPlayground).

const POLL_MS = 3_000;
const MAX_RECONNECTS = 3;

type StreamState = "idle" | "connecting" | "live" | "lost" | "failed";

type Props = {
  agentId: string | undefined;
  sessionId: string | null | undefined;
  /** True once this chat session has driven the browser - only then is "take over" offered unprompted. */
  usedBrowser?: boolean;
  /** Called after a successful hand-back with a ready-made "I'm done" message the chat can offer to send. */
  onHandedBack?: (message: string) => void;
  /** Overrides the label of the "pause the agent and take over" button (an embedding surface names it itself). */
  pauseLabel?: string;
  className?: string;
};

function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

const KIND_KEYS: Record<string, string> = {
  captcha: "kindCaptcha",
  two_factor: "kindTwoFactor",
  login: "kindLogin",
  other: "kindOther",
};

export default function BrowserTakeoverPanel({ agentId, sessionId, usedBrowser = false, onHandedBack, pauseLabel, className }: Props) {
  const t = useTranslations("browserTakeover");
  const caps = useBrowserTakeoverCapabilities();
  const enabled = !!caps && !!agentId && !!sessionId;

  const [view, setView] = useState<BrowserTakeoverView | null>(null);
  const [session, setSession] = useState<BrowserTakeoverBegin | null>(null);
  const [busy, setBusy] = useState<"begin" | "handBack" | "pause" | null>(null);
  const [error, setError] = useState(false);
  const [handedBack, setHandedBack] = useState(false);
  const [stream, setStream] = useState<StreamState>("idle");
  const [limits, setLimits] = useState<{ idle: number; max: number; at: number } | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const tokenRef = useRef<string | null>(null);
  const frameSizeRef = useRef<{ w: number; h: number } | null>(null);
  const queueRef = useRef<Promise<void>>(Promise.resolve());
  const scopeRef = useRef<string>("");
  const maxTypeRef = useRef(DEFAULT_MAX_TYPE_CHARS);
  // Bumped by every action that changes the takeover here (take control, hand back), so a status poll that was
  // already in flight when it happened cannot overwrite the newer state with a stale one.
  const epochRef = useRef(0);
  scopeRef.current = `${agentId ?? ""}|${sessionId ?? ""}`;

  const takeoverId = view?.takeoverId ?? null;
  const inControl = !!session && view?.state === "USER_CONTROL" && session.view.takeoverId === takeoverId;

  // ---- poll where this session's takeover stands ----------------------------------------------------------
  useEffect(() => {
    if (!enabled || !agentId || !sessionId) return undefined;
    let stopped = false;
    const scope = `${agentId}|${sessionId}`;
    const read = async () => {
      if (typeof document !== "undefined" && document.hidden) return;
      const epoch = epochRef.current;
      try {
        const res = await api.getBrowserTakeoverStatus(agentId, sessionId);
        if (stopped || scopeRef.current !== scope || epochRef.current !== epoch) return;
        setView(res.takeover ?? null);
      } catch {
        /* a failed read changes nothing; the next tick asks again */
      }
    };
    void read();
    const timer = setInterval(() => void read(), POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [enabled, agentId, sessionId]);

  // A new chat session / agent drops everything held for the old one.
  useEffect(() => {
    setView(null);
    setSession(null);
    setHandedBack(false);
    setError(false);
    tokenRef.current = null;
  }, [agentId, sessionId]);

  // The server ended our turn at the wheel (idle, hard maximum, session closed): stop streaming.
  useEffect(() => {
    if (session && view?.state !== "USER_CONTROL") {
      setSession(null);
      tokenRef.current = null;
    }
  }, [view?.state, session]);

  // ---- countdown ----------------------------------------------------------------------------------------
  useEffect(() => {
    if (!inControl) return undefined;
    const timer = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(timer);
  }, [inControl]);

  // ---- token renewal ------------------------------------------------------------------------------------
  const sessionTokenTtl = session?.tokenExpiresInSeconds ?? 0;
  useEffect(() => {
    if (!inControl || !takeoverId) return undefined;
    const timer = setInterval(() => {
      void api
        .refreshBrowserTakeoverToken(takeoverId)
        .then((fresh) => {
          tokenRef.current = fresh.token;
        })
        .catch(() => {
          /* a failed renewal surfaces as a 403 on the next input, which retries once */
        });
    }, tokenRefreshDelayMs(sessionTokenTtl));
    return () => clearInterval(timer);
  }, [inControl, takeoverId, sessionTokenTtl]);

  // ---- live view ----------------------------------------------------------------------------------------
  useEffect(() => {
    if (!inControl || !takeoverId || !session) return undefined;
    const ctrl = new AbortController();
    let cancelled = false;
    let decoding = false;
    const id = takeoverId;

    const draw = async (b64: string, width: number, height: number) => {
      const canvas = canvasRef.current;
      if (!canvas || typeof createImageBitmap !== "function") return;
      if (decoding) return; // newest frame wins; never queue a backlog
      decoding = true;
      try {
        const bitmap = await createImageBitmap(new Blob([base64ToBytes(b64) as BlobPart], { type: "image/jpeg" }));
        if (cancelled) {
          bitmap.close?.();
          return;
        }
        if (canvas.width !== width || canvas.height !== height) {
          canvas.width = width;
          canvas.height = height;
        }
        canvas.getContext("2d")?.drawImage(bitmap, 0, 0, width, height);
        bitmap.close?.();
      } catch {
        /* a frame that cannot be decoded is skipped */
      } finally {
        decoding = false;
      }
    };

    const connect = async (): Promise<"ended" | "dropped" | "failed"> => {
      const token = tokenRef.current;
      if (!token) return "failed";
      const res = await api.openBrowserTakeoverStream(id, token, ctrl.signal);
      if (!res.ok || !res.body) return res.status === 403 || res.status === 404 || res.status === 409 ? "failed" : "dropped";
      setStream("live");
      const parser = new SseParser();
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) return "dropped";
          for (const frame of parser.push(decoder.decode(value, { stream: true }))) {
            let data: Record<string, unknown> | null = null;
            try {
              data = JSON.parse(frame.data) as Record<string, unknown>;
            } catch {
              data = null;
            }
            if (!data) continue;
            if (frame.event === "end") return "ended";
            if (frame.event === "frame" && typeof data.data === "string") {
              const w = Number(data.width);
              const h = Number(data.height);
              if (w > 0 && h > 0) {
                frameSizeRef.current = { w, h };
                setLimits({
                  idle: Number(data.idleRemainingSeconds) || 0,
                  max: Number(data.maxRemainingSeconds) || 0,
                  at: Date.now(),
                });
                void draw(data.data, w, h);
              }
            }
          }
        }
      } finally {
        try {
          await reader.cancel();
        } catch {
          /* already closed */
        }
      }
    };

    void (async () => {
      setStream("connecting");
      for (let attempt = 0; attempt <= MAX_RECONNECTS && !cancelled; attempt++) {
        try {
          const outcome = await connect();
          if (cancelled) return;
          if (outcome === "ended") {
            setStream("idle");
            return;
          }
          if (outcome === "failed") break;
          setStream("lost");
        } catch {
          if (cancelled) return;
          setStream("lost");
        }
        await new Promise((resolve) => setTimeout(resolve, 1_000 * (attempt + 1)));
      }
      if (!cancelled) setStream("failed");
    })();

    return () => {
      cancelled = true;
      ctrl.abort();
    };
    // The stream is bound to the takeover episode, not to the (renewed) token.
  }, [inControl, takeoverId, session]);

  // ---- input relay --------------------------------------------------------------------------------------
  const send = useCallback(
    (event: BrowserTakeoverInput) => {
      const id = takeoverId;
      if (!id) return;
      // Strictly ordered: keystrokes must reach the page in the order they were typed.
      queueRef.current = queueRef.current.then(async () => {
        const attempt = async () => {
          const token = tokenRef.current;
          if (!token) return;
          await api.sendBrowserTakeoverInput(id, token, event);
        };
        try {
          await attempt();
        } catch (err) {
          const status = (err as { status?: number } | null)?.status;
          if (status === 403) {
            try {
              const fresh = await api.refreshBrowserTakeoverToken(id);
              tokenRef.current = fresh.token;
              await attempt();
            } catch {
              /* the next input tries again */
            }
          }
          // 429 (too fast) and everything else: drop this event; the person simply types again.
        }
      });
    },
    [takeoverId],
  );

  // Wheel needs a NON-passive listener to stop the chat behind from scrolling while the page does.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!inControl || !canvas) return undefined;
    let lastSent = 0;
    const onWheel = (e: WheelEvent) => {
      const size = frameSizeRef.current;
      if (!size) return;
      e.preventDefault();
      const nowMs = Date.now();
      if (nowMs - lastSent < 100) return;
      const point = pointerToFramePoint(e.clientX, e.clientY, canvas.getBoundingClientRect(), size.w, size.h);
      const input = point ? wheelToInput(e, point) : null;
      if (input) {
        lastSent = nowMs;
        send(input);
      }
    };
    canvas.addEventListener("wheel", onWheel, { passive: false });
    return () => canvas.removeEventListener("wheel", onWheel);
  }, [inControl, send]);

  // ---- actions ------------------------------------------------------------------------------------------
  async function startControl(id: string) {
    epochRef.current += 1;
    const begun = await api.beginBrowserTakeover(id);
    epochRef.current += 1;
    tokenRef.current = begun.token;
    maxTypeRef.current = begun.maxTypeChars || DEFAULT_MAX_TYPE_CHARS;
    setHandedBack(false);
    setLimits({ idle: begun.view.idleRemainingSeconds, max: begun.view.maxRemainingSeconds, at: Date.now() });
    setNow(Date.now());
    setView(begun.view);
    setSession(begun);
  }

  async function takeControl() {
    if (busy || !takeoverId) return;
    setBusy("begin");
    setError(false);
    try {
      await startControl(takeoverId);
    } catch {
      setError(true);
    } finally {
      setBusy(null);
    }
  }

  async function pauseAndTake() {
    if (busy || !agentId || !sessionId) return;
    setBusy("pause");
    setError(false);
    try {
      const paused = await api.pauseBrowserSession(agentId, sessionId);
      setView(paused);
      await startControl(paused.takeoverId);
    } catch {
      setError(true);
    } finally {
      setBusy(null);
    }
  }

  async function handBack() {
    if (busy || !takeoverId) return;
    setBusy("handBack");
    setError(false);
    try {
      epochRef.current += 1;
      await api.handBackBrowserTakeover(takeoverId);
      epochRef.current += 1;
      setSession(null);
      setView(null);
      tokenRef.current = null;
      setHandedBack(true);
      onHandedBack?.(t("continueMessage"));
    } catch {
      setError(true);
    } finally {
      setBusy(null);
    }
  }

  if (!enabled) return null;

  const wrapper = className ?? "flex w-full max-w-[85%] flex-col gap-2";

  // ---- nothing open: offer to take over (only once the browser has been used), or confirm a hand-back ----
  if (!isTakeoverOpen(view)) {
    if (handedBack) {
      return (
        <div className={wrapper} data-testid="browser-takeover">
          <p className="rounded-lg border border-border bg-card px-3 py-2 text-xs text-muted-foreground" role="status">
            {t("handedBack")}
          </p>
        </div>
      );
    }
    if (!usedBrowser) return null;
    return (
      <div className={wrapper} data-testid="browser-takeover">
        <div>
          <Button type="button" size="sm" variant="outline" onClick={() => void pauseAndTake()} disabled={busy !== null}>
            <Hand aria-hidden="true" />
            {busy === "pause" ? t("pausing") : (pauseLabel ?? t("pauseButton"))}
          </Button>
        </div>
        {error && (
          <p className="text-xs text-destructive" role="alert">
            {t("failed")}
          </p>
        )}
      </div>
    );
  }

  const kindKey = view.kind ? KIND_KEYS[view.kind] : undefined;
  const remaining = limits
    ? remainingSeconds(limits.idle - (now - limits.at) / 1000, limits.max - (now - limits.at) / 1000)
    : 0;
  const waitLeft = Math.max(0, view.waitRemainingSeconds);

  // ---- the agent asked (or the person paused) and they have not taken over yet ----
  if (!inControl) {
    return (
      <div className={wrapper} data-testid="browser-takeover">
        <div className="rounded-lg border border-border bg-card px-3 py-2.5 text-sm text-card-foreground" role="region" aria-label={t("agentNeedsYou")}>
          <div className="flex items-center gap-2">
            <MonitorSmartphone size={14} className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span className="min-w-0 truncate font-medium text-foreground">
              {view.requestedBy === "user" ? t("youPaused") : t("agentNeedsYou")}
              {kindKey ? ` - ${t(kindKey)}` : ""}
            </span>
          </div>
          {view.reason && <p className="mt-1 text-sm text-foreground">{view.reason}</p>}
          {view.host && <p className="mt-0.5 text-xs text-muted-foreground">{t("siteLine", { host: view.host })}</p>}
          {view.state === "PAUSED_FOR_USER" && waitLeft > 0 && (
            <p className="mt-0.5 text-xs text-muted-foreground">{t("waitingExpires", { time: formatCountdown(waitLeft) })}</p>
          )}
          <div className="mt-2 flex flex-wrap gap-2">
            <Button type="button" size="sm" onClick={() => void takeControl()} disabled={busy !== null || view.state === "RESUMING"}>
              {t("takeControl")}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => void handBack()} disabled={busy !== null}>
              {busy === "handBack" ? t("handingBack") : t("notNow")}
            </Button>
          </div>
          {error && (
            <p className="mt-1 text-xs text-destructive" role="alert">
              {t("failed")}
            </p>
          )}
        </div>
      </div>
    );
  }

  // ---- the person is driving ----
  return (
    <div className={wrapper} data-testid="browser-takeover">
      <div className="overflow-hidden rounded-lg border-2 border-primary bg-card text-card-foreground" role="region" aria-label={t("youAreInControl")}>
        <div className="flex flex-wrap items-center justify-between gap-2 bg-primary px-3 py-2 text-primary-foreground">
          <div className="min-w-0">
            <p className="text-sm font-semibold" role="status">
              {t("youAreInControl")}
            </p>
            <p className="text-xs opacity-90">{t("returnsIn", { time: formatCountdown(remaining) })}</p>
          </div>
          <Button type="button" size="sm" variant="secondary" onClick={() => void handBack()} disabled={busy !== null}>
            {busy === "handBack" ? t("handingBack") : t("handBack")}
          </Button>
        </div>
        {view.reason && <p className="px-3 pt-2 text-sm text-foreground">{view.reason}</p>}
        <div className="p-2">
          <canvas
            ref={canvasRef}
            data-testid="browser-takeover-canvas"
            tabIndex={0}
            // eslint-disable-next-line jsx-a11y/no-interactive-element-to-noninteractive-role -- a remote-control surface: role="application" passes keys and pointer events through to the remote browser
            role="application"
            aria-label={t("liveViewLabel")}
            className="block h-auto w-full cursor-pointer rounded border border-border bg-muted outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
            width={1024}
            height={640}
            onPointerDown={(e) => {
              e.currentTarget.focus();
              const size = frameSizeRef.current;
              if (!size) return;
              const point = pointerToFramePoint(e.clientX, e.clientY, e.currentTarget.getBoundingClientRect(), size.w, size.h);
              if (point) send({ type: "click", x: point.x, y: point.y, frameWidth: size.w, frameHeight: size.h });
            }}
            onKeyDown={(e) => {
              const input = keyToInput(e.nativeEvent);
              if (input) {
                e.preventDefault();
                send(input);
              }
            }}
            onPaste={(e) => {
              const input = pasteToInput(e.clipboardData.getData("text"), maxTypeRef.current);
              if (input) {
                e.preventDefault();
                send(input);
              }
            }}
          />
          <p className="mt-1.5 text-xs text-muted-foreground" aria-live="polite">
            {stream === "connecting" && t("connecting")}
            {stream === "lost" && t("streamLost")}
            {stream === "failed" && t("streamFailed")}
            {(stream === "live" || stream === "idle") && t("typeHint")}
          </p>
        </div>
        {error && (
          <p className="px-3 pb-2 text-xs text-destructive" role="alert">
            {t("failed")}
          </p>
        )}
      </div>
    </div>
  );
}
