"use client";

import { ArrowRight, Check, Copy, Download, Globe, Link2, Loader2, Plug, QrCode, Search, Wrench } from "lucide-react";
import type { RegistryHit, StudioPalette } from "../studioClient";
import { QR_QUIET, type QrPath } from "../../../lib/qr";
import type { MobileApkDownload } from "../../../lib/mobileApp";

// The bigger step bodies of the live builder (LiveBuilder owns all state): the
// tool-registry search and the "put it on a phone" share step, plus the small
// heading and text-field primitives every step uses.

export function Head({ title, body }: { title: string; body: string }) {
  return (
    <div>
      <h3 className="text-2xl font-semibold tracking-tight">{title}</h3>
      <p className="mt-1 text-sm text-zinc-400">{body}</p>
    </div>
  );
}

export function Field({
  label,
  value,
  onChange,
  max,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  max: number;
}) {
  return (
    <label className="block text-sm">
      <span className="mb-1 block text-[11px] uppercase tracking-wider text-zinc-400">{label}</span>
      <input
        value={value}
        maxLength={max}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-xl border border-white/15 bg-black/30 px-3 py-2.5 outline-none focus:border-violet-400"
      />
    </label>
  );
}

export type McpSearch = { loading: boolean; hits: RegistryHit[]; unavailable: boolean };

/** Step 2: search the platform's real palette (and the live MCP registry) and attach tools. */
export function RegistryStep({
  query,
  onQuery,
  mcp,
  palette,
  tools,
  picked,
  onToggle,
}: {
  query: string;
  onQuery: (q: string) => void;
  mcp: McpSearch;
  palette: StudioPalette | null;
  tools: StudioPalette["tools"];
  picked: string[];
  onToggle: (name: string) => void;
}) {
  return (
    <div className="space-y-4">
      <Head
        title="Open the tool registry"
        body="These are the tools already switched on in the platform. Search, then attach the ones this agent needs. Try “web”."
      />
      <div className="flex items-center gap-2 rounded-xl border border-white/15 bg-black/30 px-3 py-2.5">
        <Search aria-hidden="true" className="h-4 w-4 text-zinc-400 shrink-0" />
        <input
          value={query}
          onChange={(e) => onQuery(e.target.value)}
          placeholder="Search tools…  e.g. web, code, page"
          aria-label="Search tools"
          className="min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-zinc-400"
        />
        {mcp.loading && <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin text-zinc-400 shrink-0" />}
      </div>
      {!palette && (
        <div className="flex items-center gap-2 text-sm text-zinc-400">
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin shrink-0" /> Loading the registry…
        </div>
      )}
      <div className="grid gap-2.5 sm:grid-cols-2">
        {tools.map((t) => {
          const on = picked.includes(t.name);
          return (
            <button
              key={t.name}
              type="button"
              disabled={!t.available}
              onClick={() => onToggle(t.name)}
              aria-pressed={on}
              className={`rounded-2xl border p-3.5 text-left transition-colors ${on ? "border-emerald-400 bg-emerald-500/15" : t.available ? "border-white/10 bg-white/5 hover:border-violet-400/60" : "border-white/5 bg-white/[0.02] opacity-50"}`}
            >
              <div className="flex items-center gap-2 text-sm font-medium">
                {t.name === "web_search" ? (
                  <Globe aria-hidden="true" className="h-4 w-4 text-violet-300 shrink-0" />
                ) : (
                  <Wrench aria-hidden="true" className="h-4 w-4 text-violet-300 shrink-0" />
                )}{" "}
                {t.label}
                <span
                  className={`ml-auto rounded-full px-2 py-0.5 text-[10px] ${on ? "bg-emerald-500/30 text-emerald-100" : "bg-white/10 text-zinc-300"}`}
                >
                  {!t.available ? "not here" : on ? "attached ✓" : "attach"}
                </span>
              </div>
              <div className="mt-1 line-clamp-2 text-[11px] text-zinc-400">{t.description}</div>
            </button>
          );
        })}
        {palette && tools.length === 0 && (
          <p className="text-sm text-zinc-400">No built-in tool matches - the MCP registry below may.</p>
        )}
      </div>
      {(mcp.hits.length > 0 || mcp.unavailable) && (
        <div>
          <div className="mb-1.5 flex items-center gap-1.5 text-[11px] uppercase tracking-wider text-zinc-400">
            <Plug aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> MCP registry · live search
          </div>
          {mcp.unavailable ? (
            <p className="text-xs text-zinc-400">The registry did not answer just now.</p>
          ) : (
            <ul className="space-y-1.5">
              {mcp.hits.map((h) => (
                <li key={h.name} className="rounded-xl border border-sky-400/20 bg-sky-500/5 px-3 py-2 text-xs">
                  <div className="font-medium text-sky-100">{h.title || h.name}</div>
                  {h.description && <div className="line-clamp-1 text-zinc-400">{h.description}</div>}
                </li>
              ))}
              <li className="text-[11px] text-zinc-400">
                Connecting an MCP server to an agent is a workspace feature - here you can see what exists.
              </li>
            </ul>
          )}
        </div>
      )}
      <p className="text-xs text-zinc-400">
        {picked.length === 0
          ? "Nothing attached yet - with no tools it would be a plain chatbot."
          : `${picked.length} tool${picked.length === 1 ? "" : "s"} attached.`}
      </p>
    </div>
  );
}

/** Step 5: mint the share link, show its QR code, and point at signing up. */
export function ShipStep({
  shareUrl,
  busy,
  hasAgent,
  qr,
  copied,
  signupHref,
  apk,
  onMakeShare,
  onCopy,
}: {
  shareUrl: string | null;
  busy: boolean;
  hasAgent: boolean;
  qr: QrPath | null;
  copied: boolean;
  signupHref: string;
  apk: MobileApkDownload | null;
  onMakeShare: () => void;
  onCopy: () => void;
}) {
  return (
    <div className="space-y-4">
      <Head
        title="Put it in a pocket"
        body="Make the link, scan the code, and the agent you just built answers on your phone. Up to 5 people can chat with it; it goes away with this sandbox."
      />
      {!shareUrl ? (
        <button
          type="button"
          onClick={onMakeShare}
          disabled={busy || !hasAgent}
          className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-sky-600 to-cyan-600 px-4 py-2.5 text-sm font-medium shadow-lg shadow-sky-500/30 disabled:opacity-50"
        >
          {busy ? (
            <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin shrink-0" />
          ) : (
            <QrCode aria-hidden="true" className="h-4 w-4 shrink-0" />
          )}{" "}
          Make my QR code
        </button>
      ) : (
        <div className="flex flex-col items-center gap-3 sm:flex-row sm:items-start">
          <div className="rounded-2xl bg-white p-2">
            {qr && (
              <svg
                viewBox={`0 0 ${qr.size + QR_QUIET * 2} ${qr.size + QR_QUIET * 2}`}
                className="h-44 w-44"
                role="img"
                aria-label="QR code for your agent"
              >
                <path d={qr.d} fill="#09090b" />
              </svg>
            )}
          </div>
          <div className="min-w-0 flex-1 space-y-2 text-sm">
            <div className="flex items-center gap-2 rounded-xl border border-white/15 bg-black/30 p-1.5">
              <Link2 aria-hidden="true" className="ml-1.5 h-4 w-4 shrink-0 text-zinc-400" />
              <input
                readOnly
                value={shareUrl}
                onFocus={(e) => e.currentTarget.select()}
                aria-label="Share link"
                className="min-w-0 flex-1 bg-transparent px-1 text-xs outline-none"
              />
              <button
                type="button"
                onClick={onCopy}
                className="inline-flex items-center gap-1 rounded-lg bg-white/10 px-2.5 py-1.5 text-xs"
              >
                {copied ? (
                  <Check aria-hidden="true" className="h-3 w-3 shrink-0" />
                ) : (
                  <Copy aria-hidden="true" className="h-3 w-3 shrink-0" />
                )}{" "}
                {copied ? "Copied" : "Copy"}
              </button>
            </div>
            <p className="text-xs text-zinc-400">Scan with your phone camera.</p>
          </div>
        </div>
      )}
      <div className="rounded-2xl border border-violet-400/30 bg-violet-500/10 p-4">
        <div className="text-sm font-semibold">Keep everything you just built</div>
        <p className="mt-1 text-xs text-zinc-300">
          A free account copies this agent into your own workspace - then add schedules, WhatsApp and your documents.
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          <a
            href={signupHref}
            className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-4 py-2 text-sm font-medium"
          >
            Create my workspace <ArrowRight aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
          </a>
          {apk && (
            <a
              href={apk.href}
              download
              className="inline-flex items-center gap-1.5 rounded-xl border border-white/15 px-3 py-2 text-sm"
            >
              <Download aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Android app
              {apk.sizeMb ? ` · ${apk.sizeMb} MB` : ""}
            </a>
          )}
        </div>
      </div>
    </div>
  );
}
