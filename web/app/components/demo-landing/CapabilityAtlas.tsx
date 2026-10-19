"use client";

// CapabilityAtlas (2026-09-17) — every shipped capability on one screen,
// searchable, in the slot PlatformLedger held (band 3, SectionReach
// "ledger" — same position, same job: "what has this platform actually got",
// so the reach counter stays comparable as the breadth band's reach).
//
// Direct instruction: "ensure that you are adding about all the
// functionalities we have provided, like using plugins what can they do, RL
// agent learning, quality, guardrails, every other thing we have
// implemented". The nine-tile ledger covered nine; the page had no place at
// all for plugins, RL learning, the quality judges, on-device AI, share
// links, templates, Academy, budgets or the mobile app. Listing 29 things as
// prose would put the page back at 46 screens, so this is an ATLAS: compact
// tiles, a search box, category chips, one detail card. Nothing is hidden,
// nothing is long.
//
// Honesty ledger — status per tile, re-verify before editing:
//   live      shipped and reachable from a free account today
//   optin     shipped, but does nothing until an admin/owner switches it on
//             (RL learning: Thompson bandit over MODEL_ROUTE, no seeded arms
//             until `rl.policy.model-route-arms` is set + policy enabled;
//             episodes recorded for Playground turns; shared graph memory:
//             per-agent `shareMemoryWithTenant` flag)
//   beta      exists and is downloadable, but is a debug-signed build with
//             no store listing (Android app); Chrome extension is submitted
//             to the Web Store, VS Code / JetBrains plugins ship from /plugins
//   design    documented design only, not built (none today; BLE "touch" is
//             beta: Web Bluetooth reads work for on-device models in
//             Chromium over HTTPS, the server-side relay is not built)
// Live camera "Watch/Live" vision modes are committed but NOT deployed at
// write time, so the vision tile claims photo/document reading only.
// Counts (9 channels, 22 languages, 48 tools, 17 one-click MCP, 14 providers,
// 28 on-device models, 6 guardrail types, 6 judges, 14 node types, 605 API
// endpoints, 135 lessons, 18,000+ registry entries) are the same code-counted
// figures PlatformLedger and WhatsUnderneath carried.
//
// 2026-10-03: 29 -> 36 tiles. appstudio / sales / versions / teams / sso came
// over from the September ShippedBand; lab and skills are new. SSO & SCIM is
// "optin": deployed, off until a workspace admin configures an IdP, and not
// yet validated against a customer identity provider. Counts refreshed from
// lib/platform-facts.ts (35 node types, 50+ tools, 280+ lessons); the
// workflows tile no longer says "durable engine" (Temporal is opt-in).
//
// Copy lives in `landing.atlas_*` (en + hi/ta/te/kn/fr/zh/pt/ar). Search
// matches the ACTIVE locale's strings, so a Hindi reader searches in Hindi.

import { useMemo, useState, type ReactElement } from "react";
import Link from "next/link";
import {
  ArrowRight, Search, X, Eye, Ear, Mic, FileText, Cpu, Laptop, Brain, TrendingUp,
  Wrench, Plug, Hammer, Image as ImageIcon, MessageCircle, Link2, Globe, ShieldCheck,
  UserCheck, Gauge, ScrollText, Wallet, GitBranch, Sparkles, LayoutTemplate, GraduationCap,
  Code2, Puzzle, Smartphone, Lock, Bluetooth, AppWindow, Megaphone, History, FlaskConical,
  BookOpen, Users, KeyRound, type LucideIcon,
} from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { FREE_MODE } from "../../lib/freeMode";
import { academyHref } from "../../lib/academyUrl";
import { sendLandingEvent } from "./landingBeacon";
import { BILLING_ENABLED } from "../../lib/billing-flag";

type Status = "live" | "optin" | "beta" | "design";
type Cat = "senses" | "brain" | "hands" | "reach" | "governor" | "build" | "dev" | "next";

interface Tile { id: string; cat: Cat; status: Status; icon: LucideIcon; href?: string; external?: boolean; anchor?: boolean }

const CATS: Cat[] = ["senses", "brain", "hands", "reach", "governor", "build", "dev", "next"];

const TILES: Tile[] = [
  { id: "vision", cat: "senses", status: "live", icon: Eye, href: "/platform" },
  { id: "hearing", cat: "senses", status: "live", icon: Ear, href: "/platform" },
  { id: "voice", cat: "senses", status: "live", icon: Mic, href: "/platform" },
  { id: "docs", cat: "senses", status: "live", icon: FileText, href: "/platform" },
  { id: "models", cat: "brain", status: "live", icon: Cpu, href: "/integrations" },
  { id: "edge", cat: "brain", status: "live", icon: Laptop, href: "/platform" },
  { id: "memory", cat: "brain", status: "optin", icon: Brain, href: "/platform" },
  { id: "learning", cat: "brain", status: "optin", icon: TrendingUp, href: "/platform" },
  { id: "skills", cat: "brain", status: "live", icon: BookOpen, href: "/platform" },
  { id: "teams", cat: "brain", status: "live", icon: Users, href: "/platform" },
  { id: "tools", cat: "hands", status: "live", icon: Wrench, href: "/integrations" },
  { id: "mcp", cat: "hands", status: "live", icon: Plug, href: "/integrations" },
  { id: "selftools", cat: "hands", status: "live", icon: Hammer, href: "/platform" },
  { id: "media", cat: "hands", status: "live", icon: ImageIcon, href: "#live-studio", anchor: true },
  { id: "channels", cat: "reach", status: "live", icon: MessageCircle, href: "#channels", anchor: true },
  { id: "share", cat: "reach", status: "live", icon: Link2, href: "#live-studio", anchor: true },
  { id: "a2a", cat: "reach", status: "live", icon: Globe, href: "/docs" },
  { id: "guardrails", cat: "governor", status: "live", icon: ShieldCheck, href: "/platform" },
  { id: "approvals", cat: "governor", status: "live", icon: UserCheck, href: "/platform" },
  { id: "quality", cat: "governor", status: "live", icon: Gauge, href: "/platform" },
  { id: "audit", cat: "governor", status: "live", icon: ScrollText, href: "/platform" },
  // Billing off (lib/billing-flag.ts): budgets are a platform control, not a price page.
  { id: "budgets", cat: "governor", status: "live", icon: Wallet, href: BILLING_ENABLED ? "/billing" : "/platform" },
  { id: "lab", cat: "governor", status: "live", icon: FlaskConical, href: "/platform" },
  { id: "versions", cat: "governor", status: "live", icon: History, href: "/platform" },
  { id: "workflows", cat: "build", status: "live", icon: GitBranch, href: "/templates" },
  { id: "magic", cat: "build", status: "live", icon: Sparkles, href: "#live-studio", anchor: true },
  { id: "templates", cat: "build", status: "live", icon: LayoutTemplate, href: "/templates" },
  { id: "appstudio", cat: "build", status: "live", icon: AppWindow, href: "/platform" },
  { id: "sales", cat: "build", status: "live", icon: Megaphone, href: "/platform" },
  { id: "academy", cat: "build", status: "live", icon: GraduationCap, external: true },
  { id: "codeintel", cat: "dev", status: "live", icon: Code2, href: "/integrations" },
  { id: "plugins", cat: "dev", status: "live", icon: Puzzle, href: "/plugins" },
  { id: "mobile", cat: "dev", status: "beta", icon: Smartphone, href: "/docs" },
  { id: "security", cat: "dev", status: "live", icon: Lock, href: "#security", anchor: true },
  { id: "sso", cat: "dev", status: "optin", icon: KeyRound, href: "/enterprise" },
  { id: "touch", cat: "next", status: "beta", icon: Bluetooth, href: "/platform" },
];

// Status colours (QA-05, 2026-09-24): brand orange (--mkt-warm) is reserved
// for the "Dūta" wordmark, so "beta" no longer borrows it. Beta is a HOLLOW
// accent ring (solid 1.5px --accent-400 border, no fill) so it still reads
// apart from "live" (solid green), "opt-in" (solid cyan) and "design"
// (dashed grey ring) without adding a warm hue to the page.
const STATUS_STYLE: Record<Status, { color: string; dotStyle?: "dashed" | "solid" }> = {
  live: { color: "var(--mkt-positive)" },
  optin: { color: "var(--mkt-flow)" },
  beta: { color: "var(--accent-400, var(--mkt-accent))", dotStyle: "solid" },
  design: { color: "var(--mkt-ink-3)", dotStyle: "dashed" },
};

function StatusDot({ status }: { status: Status }): ReactElement {
  const s = STATUS_STYLE[status];
  return (
    <span
      className="inline-block size-2 shrink-0 rounded-full"
      style={s.dotStyle ? { border: `1.5px ${s.dotStyle} ${s.color}` } : { background: s.color }}
      aria-hidden="true"
    />
  );
}

export default function CapabilityAtlas({ signupHref }: { signupHref: string }) {
  const t = useTranslations("landing");
  const [q, setQ] = useState("");
  const [cat, setCat] = useState<Cat | "all">("all");
  const [open, setOpen] = useState<string>(TILES[0].id);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return TILES.filter((tile) => {
      if (cat !== "all" && tile.cat !== cat) return false;
      if (!needle) return true;
      const hay = `${t(`atlas_${tile.id}_title`)} ${t(`atlas_${tile.id}_body`)} ${t(`atlas_${tile.id}_where`)} ${t(`atlas_cat_${tile.cat}`)}`.toLowerCase();
      return hay.includes(needle);
    });
  }, [q, cat, t]);

  const active = TILES.find((x) => x.id === open) ?? TILES[0];
  const ActiveIcon = active.icon;
  const activeHref = active.external ? academyHref() : active.href;

  return (
    <section id="platform-ledger" className="mkt-band mkt-band--ruled scroll-mt-16" data-testid="capability-atlas">
      <div className="mkt-band__inner">
        <div className="mkt-head mkt-reveal">
          <p className="mkt-eyebrow">{t("atlas_eyebrow")}</p>
          <h2 className="mkt-h2">{t("atlas_title")}</h2>
          <p className="mkt-section-sub mkt-ink-2">{t("atlas_sub")}</p>
        </div>

        {/* Controls: search + category chips */}
        <div className="mt-8 flex flex-col gap-3 md:flex-row md:items-center">
          <label className="relative block w-full md:max-w-xs">
            <span className="sr-only">{t("atlas_searchLabel")}</span>
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2" style={{ color: "var(--mkt-ink-3)" }} aria-hidden="true" />
            <input
              type="search"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={t("atlas_searchPlaceholder")}
              className="mkt-input w-full"
              style={{ paddingInlineStart: "2.4rem", height: "2.75rem" }}
              data-testid="atlas-search"
            />
          </label>
          <div className="flex flex-wrap gap-1.5" role="group" aria-label={t("atlas_catLabel")}>
            {(["all", ...CATS] as const).map((c) => {
              const on = c === cat;
              return (
                <button
                  key={c}
                  type="button"
                  aria-pressed={on}
                  onClick={() => setCat(c)}
                  className="mkt-caption rounded-full border-0 bg-transparent px-3 py-1.5 font-medium shadow-none transition-colors"
                  style={on
                    ? { background: "var(--mkt-accent)", color: "var(--accent-fg, #fff)" }
                    : { background: "var(--mkt-surface)", color: "var(--mkt-ink-2)", border: "1px solid var(--mkt-line)" }}
                >
                  {c === "all" ? t("atlas_catAll") : t(`atlas_cat_${c}`)}
                </button>
              );
            })}
          </div>
        </div>

        {/* Legend */}
        <p className="mkt-caption mkt-ink-3 mt-3 flex flex-wrap gap-x-4 gap-y-1">
          {(["live", "optin", "beta", "design"] as Status[]).map((s) => (
            <span key={s} className="inline-flex items-center gap-1.5"><StatusDot status={s} />{t(`atlas_status_${s}`)}</span>
          ))}
          <span className="ms-auto">{t("atlas_count", { n: filtered.length, total: TILES.length })}</span>
        </p>

        {/* Tile grid */}
        <ul className="mt-4 grid gap-2 sm:grid-cols-2 lg:grid-cols-4" aria-label={t("atlas_title")}>
          {filtered.map((tile) => {
            const on = tile.id === open;
            const Icon = tile.icon;
            return (
              <li key={tile.id}>
                <button
                  type="button"
                  onClick={() => { setOpen(tile.id); sendLandingEvent("atlas_tile_click"); }}
                  aria-expanded={on}
                  aria-controls="atlas-detail"
                  className="mkt-card mkt-card--interactive flex w-full items-start gap-3 border-0 p-3 text-start shadow-none transition-colors"
                  style={on ? { borderColor: "var(--mkt-accent)", boxShadow: "var(--mkt-shadow-glow)" } : undefined}
                  data-testid={`atlas-tile-${tile.id}`}
                >
                  <span
                    className="flex size-9 shrink-0 items-center justify-center rounded-lg"
                    style={{ background: "color-mix(in oklab, var(--mkt-accent) 12%, transparent)", color: "var(--mkt-accent)" }}
                  >
                    <Icon className="size-4" aria-hidden="true" />
                  </span>
                  <span className="min-w-0">
                    <span className="mkt-small flex items-center gap-1.5 font-semibold" style={{ color: "var(--mkt-ink)" }}>
                      <span className="truncate">{t(`atlas_${tile.id}_title`)}</span>
                      <StatusDot status={tile.status} />
                      <span className="sr-only">{t(`atlas_status_${tile.status}`)}</span>
                    </span>
                    <span className="mkt-caption mkt-ink-3 mt-0.5 line-clamp-2 block">{t(`atlas_${tile.id}_body`)}</span>
                  </span>
                </button>
              </li>
            );
          })}
          {filtered.length === 0 && (
            <li className="mkt-small mkt-ink-3 col-span-full py-6 text-center">{t("atlas_empty")}</li>
          )}
        </ul>

        {/* Detail card */}
        <div id="atlas-detail" className="mkt-card mkt-card--raised mt-4 p-5" aria-live="polite">
          <div className="flex flex-wrap items-start gap-4">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-xl" style={{ background: "var(--mkt-accent)", color: "var(--accent-fg, #fff)" }}>
              <ActiveIcon className="size-5" aria-hidden="true" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="mkt-ui-label mkt-ink-3">{t(`atlas_cat_${active.cat}`)}</p>
              <h3 className="mkt-h3 mt-0.5 flex flex-wrap items-center gap-2">
                {t(`atlas_${active.id}_title`)}
                <span className="mkt-caption inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 font-medium" style={{ background: "var(--mkt-surface-sunken)", color: STATUS_STYLE[active.status].color }}>
                  <StatusDot status={active.status} />{t(`atlas_status_${active.status}`)}
                </span>
              </h3>
              <p className="mkt-body mkt-ink-2 mt-2">{t(`atlas_${active.id}_body`)}</p>
              <p className="mkt-caption mkt-ink-3 mt-2"><span className="mkt-ui-label">{t("atlas_whereLabel")}</span> {t(`atlas_${active.id}_where`)}</p>
            </div>
            {activeHref && (
              active.external ? (
                <a href={activeHref} target="_blank" rel="noopener noreferrer" onClick={() => sendLandingEvent("ledger_academy_click")} className="mkt-btn mkt-btn-secondary shrink-0">
                  {t("atlas_more")} <ArrowRight className="size-4" aria-hidden="true" />
                </a>
              ) : (
                <Link href={activeHref} className="mkt-btn mkt-btn-secondary shrink-0">
                  {t("atlas_more")} <ArrowRight className="size-4" aria-hidden="true" />
                </Link>
              )
            )}
          </div>
        </div>

        {/* The ask — same beacon as the ledger it replaces (same slot, same intent) */}
        <div className="mt-8 flex flex-col items-center gap-3 sm:flex-row sm:justify-center">
          <Link href={signupHref} onClick={() => sendLandingEvent("ledger_signup_click")} className="mkt-btn mkt-btn-primary mkt-btn-lg w-full sm:w-auto">
            {t("atlas_ctaSignup")} <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
          <Link href="/try" className="mkt-btn mkt-btn-secondary mkt-btn-lg w-full sm:w-auto">
            {t("atlas_ctaTry")}
          </Link>
        </div>
        <p className="mkt-caption mkt-ink-3 mt-3 text-center">{t(FREE_MODE ? "atlasNoteFreeMode" : "atlas_note")}</p>
        {q && (
          <button type="button" onClick={() => setQ("")} className="sr-only focus:not-sr-only focus:absolute focus:z-10" aria-label={t("atlas_clear")}><X className="size-3" /></button>
        )}
      </div>
    </section>
  );
}
