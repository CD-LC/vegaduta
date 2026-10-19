"use client";

import { useMemo, useState } from "react";
import {
  Check,
  ChevronDown,
  ChevronRight,
  Copy,
  Download,
  Layers,
  SlidersHorizontal,
  Wrench,
  Braces,
} from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import BrandText from "../BrandText";

/**
 * Request X-ray - exactly what one agent turn / workflow node sent to the model.
 *
 * Shared by the playground (per assistant turn) and the workflow run inspector
 * (per agent node). It renders the snapshots core's LlmMetricsAdvisor captured
 * as the LAST step before the provider adapter, so what is shown is the final
 * prompt: system prompt + directive/memory/skill blocks + history + the user
 * turn, the sampling options, and the tool definitions the model could call.
 *
 * The signature view is the composition bar: every message and the tool schema
 * as a proportional segment of the request, so "why is this call 30k tokens?"
 * is answered at a glance. Sizes are a chars/4 estimate, labelled as such -
 * real token counts live on the cost row.
 */

export interface LlmRequestMessage {
  role: "system" | "user" | "assistant" | "tool";
  text: string;
  chars: number;
  toolCalls?: string[];
  mediaCount?: number;
}

export interface LlmRequestTool {
  name: string;
  description?: string | null;
  inputSchema?: string | null;
}

export interface LlmRequestSnapshot {
  id: string;
  at: string;
  agentId?: string | null;
  sessionId?: string | null;
  provider?: string | null;
  model?: string | null;
  streaming?: boolean;
  options?: Record<string, unknown>;
  messages: LlmRequestMessage[];
  tools: LlmRequestTool[];
  context?: Record<string, string>;
  totalChars?: number;
  /** Real provider-reported prompt tokens; absent until the server ships it. */
  promptTokens?: number | null;
  /** Full tool-schema size in chars (the tools list itself may be truncated). */
  schemaChars?: number | null;
  truncated?: boolean;
}

type Tab = "messages" | "config" | "tools" | "raw";

const ROLE_COLOR: Record<string, string> = {
  system: "var(--cat-assistant)",
  user: "var(--cat-data)",
  assistant: "var(--state-warning)",
  tool: "var(--cat-creative)",
  tools: "var(--cat-automation)",
};

const est = (chars: number) => Math.max(1, Math.round(chars / 4));
const fmt = new Intl.NumberFormat();

/** The platform stacks several system messages (agent prompt, procedures, skills, memory/directives, plan). */
function messageTitle(
  m: LlmRequestMessage,
  systemIndex: number,
  systemCount: number,
  t: (k: string, v?: Record<string, string | number>) => string
) {
  if (m.role === "system") {
    if (systemCount > 1) return systemIndex === 0 ? t("systemPrompt") : t("systemBlock", { n: systemIndex + 1 });
    return t("systemPrompt");
  }
  if (m.role === "user") return t("userMessage");
  if (m.role === "assistant") return t("assistantMessage");
  return t("toolResult");
}

export default function RequestInspector({
  snapshots,
  defaultOpen = false,
}: {
  snapshots: LlmRequestSnapshot[];
  defaultOpen?: boolean;
}) {
  const t = useTranslations("requestInspector");
  const [idx, setIdx] = useState(0);
  const [tab, setTab] = useState<Tab>("messages");
  const [openMsgs, setOpenMsgs] = useState<Set<number>>(() => new Set());
  const [copied, setCopied] = useState<string | null>(null);
  const [open, setOpen] = useState(defaultOpen);

  const snap = snapshots[Math.min(idx, snapshots.length - 1)];

  const parts = useMemo(() => {
    if (!snap) return [];
    const sysCount = snap.messages.filter((m) => m.role === "system").length;
    let sysSeen = 0;
    const out = snap.messages.map((m, i) => {
      const title = messageTitle(m, m.role === "system" ? sysSeen++ : 0, sysCount, t);
      return { key: `m${i}`, i, role: m.role as string, title, chars: m.chars };
    });
    const listedToolChars = snap.tools.reduce(
      (s, x) => s + (x.name?.length ?? 0) + (x.description?.length ?? 0) + (x.inputSchema?.length ?? 0),
      0
    );
    const toolChars = Math.max(listedToolChars, snap.schemaChars ?? 0);
    if (toolChars > 0)
      out.push({
        key: "tools",
        i: -1,
        role: "tools",
        title: t("toolDefinitions", { n: snap.tools.length }),
        chars: toolChars,
      });
    return out;
  }, [snap, t]);

  const sections = useMemo(() => {
    if (!snap) return [];
    let lastUser = -1;
    snap.messages.forEach((m, i) => {
      if (m.role === "user") lastUser = i;
    });
    let system = 0;
    let history = 0;
    let user = 0;
    snap.messages.forEach((m, i) => {
      if (m.role === "system") system += m.chars;
      else if (i === lastUser) user += m.chars;
      else history += m.chars;
    });
    const tools = parts.find((p) => p.key === "tools")?.chars ?? 0;
    return [
      { key: "system", label: t("sectionSystem"), chars: system, color: ROLE_COLOR.system },
      { key: "tools", label: t("sectionTools"), chars: tools, color: ROLE_COLOR.tools },
      { key: "history", label: t("sectionHistory"), chars: history, color: ROLE_COLOR.assistant },
      { key: "user", label: t("sectionUser"), chars: user, color: ROLE_COLOR.user },
    ].filter((x) => x.chars > 0);
  }, [snap, parts, t]);

  if (!snap) return null;
  const total = parts.reduce((s, p) => s + p.chars, 0) || 1;
  const realTokens = typeof snap.promptTokens === "number" && snap.promptTokens > 0 ? snap.promptTokens : null;
  const shownTokens = realTokens ?? est(total);

  const copy = (id: string, text: string) => {
    try {
      void navigator.clipboard?.writeText(text).then(() => {
        setCopied(id);
        setTimeout(() => setCopied((c) => (c === id ? null : c)), 1500);
      });
    } catch {
      /* clipboard unavailable - nothing to do */
    }
  };
  const rawJson = JSON.stringify(snap, null, 2);
  const download = () => {
    const url = URL.createObjectURL(new Blob([rawJson], { type: "application/json" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = `llm-request-${snap.id.slice(0, 8)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };
  const toggleMsg = (i: number) =>
    setOpenMsgs((s) => {
      const n = new Set(s);
      if (n.has(i)) n.delete(i);
      else n.add(i);
      return n;
    });

  const optionEntries = Object.entries(snap.options ?? {});
  const tabs: { key: Tab; label: string; Icon: typeof Layers; badge?: number }[] = [
    { key: "messages", label: t("tabMessages"), Icon: Layers, badge: snap.messages.length },
    { key: "config", label: t("tabConfig"), Icon: SlidersHorizontal },
    { key: "tools", label: t("tabTools"), Icon: Wrench, badge: snap.tools.length },
    { key: "raw", label: t("tabRaw"), Icon: Braces },
  ];

  return (
    <div
      className="w-full max-w-[85%] rounded-lg border border-border bg-secondary/20 text-xs"
      data-testid="request-inspector"
    >
      {/* globals.css's `button {}` base (border/card bg/radius/shadow/text-sm)
          and tokens.css's 36px min-height tap-target rule restyle every
          bare <button> in this panel - the resets below strip that chrome so
          the disclosure header, underline tabs and bar segments keep their
          intended shapes. Copy/Download keep the chrome: they ARE buttons. */}
      <button
        type="button"
        className="flex w-full min-w-0 items-center gap-2 rounded-none border-0 bg-transparent px-2.5 py-2 text-left text-xs font-normal text-muted-foreground shadow-none hover:bg-transparent hover:text-foreground"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
      >
        {open ? <ChevronDown size={13} aria-hidden="true" /> : <ChevronRight size={13} aria-hidden="true" />}
        <span className="whitespace-nowrap font-medium text-foreground">
          <BrandText text={t("title")} />
        </span>
        <span className="min-w-0 truncate">
          {t(realTokens ? "summaryExact" : "summary", { calls: snapshots.length, tokens: fmt.format(shownTokens) })}
        </span>
      </button>

      {open && (
        <div className="space-y-2.5 border-t border-border p-2.5">
          {snapshots.length > 1 && (
            <div className="flex flex-wrap gap-1" role="tablist" aria-label={t("callPicker")}>
              {snapshots.map((s, i) => (
                <button
                  key={s.id}
                  type="button"
                  role="tab"
                  aria-selected={i === idx}
                  onClick={() => setIdx(i)}
                  className={`rounded-full border bg-transparent px-2 py-0.5 text-xs font-normal shadow-none ${i === idx ? "border-primary text-foreground" : "border-border text-muted-foreground"}`}
                >
                  {t("callN", { n: i + 1 })}
                  {s.tools.length === 0 && s.messages.length > 0 ? ` · ${t("noTools")}` : ""}
                </button>
              ))}
            </div>
          )}

          <div className="flex flex-wrap items-center gap-1.5">
            {snap.provider && <span className="rounded-full border border-border px-2 py-0.5">{snap.provider}</span>}
            {snap.model && (
              <span className="rounded-full border border-border px-2 py-0.5 font-mono">{snap.model}</span>
            )}
            {optionEntries.slice(0, 4).map(([k, v]) => (
              <span key={k} className="rounded-full bg-secondary px-2 py-0.5 text-muted-foreground">
                {k} <span className="text-foreground">{String(v)}</span>
              </span>
            ))}
            <span className="rounded-full bg-secondary px-2 py-0.5 text-muted-foreground">
              {snap.streaming ? t("streaming") : t("buffered")}
            </span>
          </div>

          {/* Composition bar: each segment is one message (or the tool schema), sized by characters. */}
          <div>
            <div
              className="flex h-2.5 w-full overflow-hidden rounded-full bg-secondary"
              role="img"
              aria-label={t("compositionLabel")}
            >
              {parts.map((p) => (
                <button
                  key={p.key}
                  type="button"
                  title={`${p.title} · ~${fmt.format(est(p.chars))} ${t("tokensApprox")}`}
                  style={{ width: `${Math.max(1.5, (p.chars / total) * 100)}%`, background: ROLE_COLOR[p.role] }}
                  className="h-full min-h-0! rounded-none border-y-0 border-l-0 border-r border-background/60 p-0 shadow-none last:border-r-0 active:scale-100"
                  onClick={() => {
                    if (p.i >= 0) {
                      setTab("messages");
                      setOpenMsgs((s) => new Set(s).add(p.i));
                    } else {
                      setTab("tools");
                    }
                  }}
                  aria-label={p.title}
                />
              ))}
            </div>
            <p className="mt-1 text-[11px] text-muted-foreground">
              {realTokens
                ? t("estimateNoteExact", { exact: fmt.format(realTokens), estimate: fmt.format(est(total)) })
                : t("estimateNote", { tokens: fmt.format(est(total)) })}
            </p>
            {sections.length > 0 && (
              <div className="mt-1.5">
                <div
                  className="flex h-2 w-full overflow-hidden rounded-full bg-secondary"
                  role="img"
                  aria-label={t("breakdownLabel")}
                >
                  {sections.map((x) => (
                    <div
                      key={x.key}
                      title={`${x.label} · ~${fmt.format(est(x.chars))} ${t("tokensApprox")}`}
                      style={{ width: `${Math.max(1.5, (x.chars / total) * 100)}%`, background: x.color }}
                      className="h-full"
                    />
                  ))}
                </div>
                <ul className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-muted-foreground">
                  {sections.map((x) => (
                    <li key={x.key} className="flex items-center gap-1">
                      <span
                        className="inline-block h-2 w-2 rounded-full"
                        style={{ background: x.color }}
                        aria-hidden="true"
                      />
                      {x.label} ~{fmt.format(est(x.chars))} ({Math.round((x.chars / total) * 100)}%)
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>

          <div className="flex gap-1 border-b border-border" role="tablist">
            {tabs.map(({ key, label, Icon, badge }) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={tab === key}
                onClick={() => setTab(key)}
                className={`-mb-px flex items-center gap-1 rounded-none border-x-0 border-t-0 border-b-2 bg-transparent px-2 py-1 text-xs font-normal shadow-none ${tab === key ? "border-primary text-foreground" : "border-transparent text-muted-foreground"}`}
              >
                <Icon size={12} aria-hidden="true" />
                {label}
                {badge ? <span className="text-xs text-muted-foreground">{badge}</span> : null}
              </button>
            ))}
          </div>

          {tab === "messages" && (
            <ol className="space-y-1.5">
              {parts
                .filter((p) => p.i >= 0)
                .map((p) => {
                  const m = snap.messages[p.i];
                  const isOpen = openMsgs.has(p.i);
                  return (
                    <li key={p.key} className="rounded-md border border-border bg-background/50">
                      <div className="flex items-center gap-1.5 px-2 py-1.5">
                        <button
                          type="button"
                          className="flex min-h-0! flex-1 items-center gap-1.5 rounded-none border-0 bg-transparent p-0 text-left text-xs font-normal shadow-none hover:bg-transparent"
                          onClick={() => toggleMsg(p.i)}
                          aria-expanded={isOpen}
                        >
                          <span
                            className="inline-block h-2 w-2 rounded-full"
                            style={{ background: ROLE_COLOR[m.role] }}
                            aria-hidden="true"
                          />
                          <span className="font-medium text-foreground">{p.title}</span>
                          <span className="text-muted-foreground">
                            {fmt.format(m.chars)} {t("chars")} · ~{fmt.format(est(m.chars))} {t("tokensApprox")}
                          </span>
                          {(m.mediaCount ?? 0) > 0 && (
                            <span className="text-muted-foreground">
                              · {t("attachments", { n: m.mediaCount ?? 0 })}
                            </span>
                          )}
                        </button>
                        <button
                          type="button"
                          className="inline-flex size-6 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none hover:text-foreground"
                          onClick={() => copy(`m${p.i}`, m.text)}
                          aria-label={t("copy")}
                        >
                          {copied === `m${p.i}` ? (
                            <Check size={12} aria-hidden="true" />
                          ) : (
                            <Copy size={12} aria-hidden="true" />
                          )}
                        </button>
                      </div>
                      {isOpen && (
                        <div className="border-t border-border px-2 py-1.5">
                          {m.text ? (
                            <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed text-foreground">
                              {m.text}
                            </pre>
                          ) : (
                            <p className="text-muted-foreground">{t("emptyText")}</p>
                          )}
                          {(m.toolCalls ?? []).map((c, k) => (
                            <pre
                              key={k}
                              className="mt-1 overflow-auto whitespace-pre-wrap break-words rounded bg-secondary/60 p-1.5 font-mono text-[11px]"
                            >
                              {c}
                            </pre>
                          ))}
                        </div>
                      )}
                    </li>
                  );
                })}
            </ol>
          )}

          {tab === "config" && (
            <div className="space-y-2">
              <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
                <dt className="text-muted-foreground">{t("provider")}</dt>
                <dd className="font-mono text-foreground">{snap.provider ?? "-"}</dd>
                <dt className="text-muted-foreground">{t("model")}</dt>
                <dd className="font-mono text-foreground">{snap.model ?? "-"}</dd>
                {optionEntries.map(([k, v]) => (
                  <div key={k} className="contents">
                    <dt className="text-muted-foreground">{k}</dt>
                    <dd className="font-mono text-foreground">{Array.isArray(v) ? v.join(", ") : String(v)}</dd>
                  </div>
                ))}
                {Object.entries(snap.context ?? {}).map(([k, v]) => (
                  <div key={`c-${k}`} className="contents">
                    <dt className="text-muted-foreground">{k}</dt>
                    <dd className="break-all font-mono text-foreground">{v}</dd>
                  </div>
                ))}
              </dl>
              {optionEntries.length === 0 && <p className="text-muted-foreground">{t("providerDefaults")}</p>}
            </div>
          )}

          {tab === "tools" &&
            (snap.tools.length === 0 ? (
              <p className="text-muted-foreground">{t("noToolsOffered")}</p>
            ) : (
              <ul className="space-y-1.5">
                {snap.tools.map((tool) => (
                  <li key={tool.name} className="rounded-md border border-border bg-background/50 p-2">
                    <div className="font-mono font-medium text-foreground">{tool.name}</div>
                    {tool.description && <p className="mt-0.5 text-muted-foreground">{tool.description}</p>}
                    {tool.inputSchema && (
                      <details className="mt-1">
                        <summary className="cursor-pointer text-muted-foreground">{t("schema")}</summary>
                        <pre className="mt-1 max-h-48 overflow-auto whitespace-pre-wrap break-words font-mono text-[11px]">
                          {tool.inputSchema}
                        </pre>
                      </details>
                    )}
                  </li>
                ))}
              </ul>
            ))}

          {tab === "raw" && (
            <div className="space-y-1.5">
              <div className="flex gap-1.5">
                <button
                  type="button"
                  className="flex items-center gap-1 rounded border border-border px-2 py-1"
                  onClick={() => copy("raw", rawJson)}
                >
                  {copied === "raw" ? <Check size={12} aria-hidden="true" /> : <Copy size={12} aria-hidden="true" />}{" "}
                  {t("copyJson")}
                </button>
                <button
                  type="button"
                  className="flex items-center gap-1 rounded border border-border px-2 py-1"
                  onClick={download}
                >
                  <Download size={12} aria-hidden="true" /> {t("download")}
                </button>
              </div>
              <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded bg-secondary/40 p-2 font-mono text-[11px]">
                {rawJson}
              </pre>
            </div>
          )}

          {snap.truncated && <p className="text-[11px] text-muted-foreground">{t("truncatedNote")}</p>}
          <p className="text-[11px] text-muted-foreground">{t("redactionNote")}</p>
        </div>
      )}
    </div>
  );
}
