"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useState } from "react";
import { Activity, ArrowLeft, CheckCircle2, AlertTriangle, XCircle, HelpCircle, RefreshCw } from "lucide-react";

import { API_BASE } from "../lib/api";
import { Button } from "../components/ui/button";
import PublicAuthCtas from "../components/PublicAuthCtas";
import { componentLabel, describeBanner, statusIconKind, statusLabel, type ComponentStatus } from "./statusModel";
import BrandName from "../components/BrandName";
import { useTranslations } from "../lib/i18n";

// Public system-status client (Tier-4 public surface). Polls
// GET {API_BASE}/api/public/status every 30s - a permitAll endpoint that
// deliberately answers only component names + status codes (UP / DOWN /
// DEGRADED / UNKNOWN / NOT_CONFIGURED); see PublicStatusController's
// javadoc for why nothing more detailed is ever exposed unauthenticated.
// A failed fetch is rendered honestly as "status unreachable", never a
// fabricated all-green, and a component the server could not read is never
// hidden under an "all systems operational" banner (see statusModel.ts).

type PublicStatus = { status: string; checkedAt: string; components: ComponentStatus[] };

type FetchState = { kind: "loading" } | { kind: "ok"; data: PublicStatus } | { kind: "unreachable" };

const POLL_MS = 30_000;

function statusIcon(status: string) {
  switch (statusIconKind(status)) {
    case "up":
      return <CheckCircle2 className="size-4 shrink-0 text-emerald-700 dark:text-emerald-400" aria-hidden />;
    case "down":
      return <XCircle className="size-4 shrink-0 text-red-700 dark:text-red-400" aria-hidden />;
    case "off":
      return <HelpCircle className="size-4 shrink-0 text-muted-foreground" aria-hidden />;
    default:
      return <AlertTriangle className="size-4 shrink-0 text-amber-700 dark:text-amber-400" aria-hidden />;
  }
}

export default function StatusPageClient() {
    const tHC = useTranslations("hcStatusStatusPageClient");
  const [state, setState] = useState<FetchState>({ kind: "loading" });
  const [lastPoll, setLastPoll] = useState<Date | null>(null);

  useEffect(() => {
    let cancelled = false;
    const poll = async () => {
      try {
        const res = await fetch(`${API_BASE}/api/public/status`, { cache: "no-store" });
        if (cancelled) return;
        setLastPoll(new Date());
        if (!res.ok) {
          setState({ kind: "unreachable" });
          return;
        }
        setState({ kind: "ok", data: (await res.json()) as PublicStatus });
      } catch {
        if (!cancelled) {
          setLastPoll(new Date());
          setState({ kind: "unreachable" });
        }
      }
    };
    void poll();
    const timer = setInterval(() => void poll(), POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  const overall = state.kind === "ok" ? state.data.status : state.kind === "unreachable" ? "UNREACHABLE" : "CHECKING";
  const banner = describeBanner(overall, state.kind === "ok" ? state.data.components : []);

  return (
    <div className="vd-pub relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

      <nav className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-y-3 px-5 py-5 sm:justify-between sm:px-8">
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight">
          <Image
            src="/logo-mark.png"
            alt=""
            height={32}
            width={44}
            className="h-8 w-auto object-contain"
            style={{ height: 32, width: "auto" }}
            draggable={false}
            priority
          />
          <span>
            <BrandName />
          </span>
        </Link>
        <div className="flex flex-wrap items-center justify-center gap-2 sm:gap-3">
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/">
              <ArrowLeft aria-hidden="true" className="size-3.5" /> Home
            </Link>
          </Button>
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/docs">Docs</Link>
          </Button>
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/api-reference">API</Link>
          </Button>
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/changelog">Changelog</Link>
          </Button>
          <PublicAuthCtas>
            <Button asChild variant="outline" size="sm">
              <Link href="/signup">{tHC("s12")}</Link>
            </Button>
          </PublicAuthCtas>
        </div>
      </nav>

      <header className="mx-auto max-w-3xl px-6 pb-8 pt-10 text-center">
        <span className="font-mono text-xs uppercase tracking-[0.18em] text-primary">{tHC("s11")}</span>
        <h1 className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{tHC("s10")}</h1>
        <p className="mx-auto mt-4 max-w-xl leading-relaxed text-muted-foreground">
          {tHC("s9")}
        </p>
      </header>

      <main className="mx-auto max-w-3xl px-6 pb-20 sm:px-8">
        {/* --------------------------------------------------------- Banner */}
        <div
          className={`flex flex-wrap items-center justify-between gap-3 rounded-2xl border px-4 py-5 sm:px-6 ${
            banner.tone === "ok"
              ? "border-emerald-500/30 bg-emerald-500/[0.07]"
              : banner.tone === "bad"
                ? "border-red-500/30 bg-red-500/[0.07]"
                : "border-amber-500/30 bg-amber-500/[0.07]"
          }`}
        >
          <div className="flex min-w-0 items-center gap-3">
            <Activity className="size-5 shrink-0" aria-hidden />
            <div>
              <p className="text-lg font-semibold">{banner.title}</p>
              <p className="text-xs text-muted-foreground">
                {state.kind === "ok"
                  ? `Last checked ${new Date(state.data.checkedAt).toLocaleTimeString()}`
                  : state.kind === "unreachable"
                    ? "Could not reach the status endpoint just now"
                    : "Reading live probes…"}
              </p>
            </div>
          </div>
          <span className="flex items-center gap-1.5 font-mono text-xs text-muted-foreground">
            <RefreshCw className="size-3" aria-hidden /> 30s
          </span>
        </div>

        {/* ------------------------------------------------------ Components */}
        <section className="mt-6 overflow-hidden rounded-2xl border border-border/60">
          {state.kind === "ok" ? (
            <ul className="divide-y divide-border/60">
              {state.data.components.map((c) => (
                <li
                  key={c.name}
                  className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-4 py-3.5 sm:px-5"
                >
                  <span className="text-sm">{componentLabel(c.name)}</span>
                  <span className="flex items-center gap-2 text-sm">
                    {statusIcon(c.status)}
                    <span className="text-muted-foreground">{statusLabel(c.status)}</span>
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="px-5 py-8 text-center text-sm text-muted-foreground">
              {state.kind === "loading"
                ? "Reading live probes…"
                : "The status endpoint did not answer. That is itself the current answer."}
            </p>
          )}
        </section>

        <section className="mt-8 rounded-2xl border border-border/60 px-6 py-6">
          <h2 className="text-sm font-semibold">{tHC("s8")}</h2>
          <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
            {tHC("s7")}{" "}
            <code className="font-mono text-xs">describeCluster()</code>{tHC("s6")} <em>{tHC("s5")}</em> {tHC("s4")}{" "}
            <em>Unknown</em> {tHC("s3")} <code className="font-mono text-xs">{tHC("s2")}</code>.
          </p>
          {lastPoll && (
            <p className="mt-3 font-mono text-xs text-muted-foreground">
              {tHC("s1")} {new Date(lastPoll.getTime() + POLL_MS).toLocaleTimeString()}
            </p>
          )}
        </section>
      </main>
    </div>
  );
}
