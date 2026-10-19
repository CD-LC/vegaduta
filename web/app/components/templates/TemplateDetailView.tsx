"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { LucideIcon } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  Download,
  Cpu,
  ShieldCheck,
  Wrench,
  BookOpen,
  RefreshCw,
  Lock,
  Settings2,
} from "lucide-react";
import { api } from "../../lib/api";
import type { CommunityTemplateDetail } from "../../lib/api";
import { auth } from "../../lib/auth";
import { toast } from "../../lib/toastBus";
import { Card, CardContent } from "../ui/card";
import { Button } from "../ui/button";
import { Skeleton } from "../ui/skeleton";
import CategoryChip from "./CategoryChip";
import TemplateRequirementsPanel from "./TemplateRequirementsPanel";
import { categoryMeta, kindMeta } from "./categories";

// Read-only inspection of an approved template before import. Shows the
// design (system prompt / model / tools / guardrails, or workflow steps) so a
// visitor can judge it, plus one Import button. Seeded from the server fetch
// (crawlable) and self-heals on the client if the server couldn't reach the
// not-yet-live backend.
export default function TemplateDetailView({ id, initial }: { id: string; initial: CommunityTemplateDetail | null }) {
  const router = useRouter();
  const [detail, setDetail] = useState<CommunityTemplateDetail | null>(initial);
  const [status, setStatus] = useState<"loading" | "ready" | "error">(initial ? "ready" : "loading");
  const [error, setError] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const skipNextFetch = useRef<boolean>(!!initial);

  const load = useCallback(async () => {
    setStatus("loading");
    setError(null);
    try {
      const res = await api.getGalleryTemplate(id);
      setDetail(res);
      setStatus("ready");
    } catch (e: any) {
      setError(e?.message || "Couldn't load this template.");
      setStatus("error");
    }
  }, [id]);

  useEffect(() => {
    if (skipNextFetch.current) {
      skipNextFetch.current = false;
      return;
    }
    void load();
  }, [load]);

  const handleImport = useCallback(async () => {
    setImporting(true);
    try {
      // Public page: no Keycloak lifecycle ran, so establish/confirm a session
      // before an authenticated write. No session -> send them through login,
      // returning to this page so they can import once signed in.
      const kc = await auth.init();
      if (!kc) {
        await auth.login({ redirectUri: `${window.location.origin}/templates/${encodeURIComponent(id)}` });
        return;
      }
      const res = await api.importTemplate(id);
      toast.success("Template imported - a private copy was added to your workspace.");
      router.push(res?.kind === "WORKFLOW" ? "/workflows" : "/agents");
    } catch (e: any) {
      toast.error(e?.message || "Import failed. Please try again.");
    } finally {
      setImporting(false);
    }
  }, [id, router]);

  const backLink = (
    <Link
      href="/templates"
      className="inline-flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
    >
      <ArrowLeft size={15} strokeWidth={2} aria-hidden="true" /> All templates
    </Link>
  );

  if (status === "loading") {
    return (
      <div className="space-y-6">
        {backLink}
        <div className="flex items-center gap-4">
          <Skeleton className="size-14 rounded-2xl" />
          <div className="space-y-2">
            <Skeleton className="h-5 w-64" />
            <Skeleton className="h-3 w-40" />
          </div>
        </div>
        <Skeleton className="h-40 w-full rounded-xl" />
      </div>
    );
  }

  if (status === "error" || !detail) {
    return (
      <div className="space-y-6">
        {backLink}
        <Card elevation="base" className="items-center gap-3 py-12 text-center">
          <CardContent className="flex flex-col items-center gap-3">
            <span className="cat-chip cat-chip-lg" style={{ ["--cat" as string]: "var(--state-error)" }}>
              <AlertCircle size={18} strokeWidth={2} aria-hidden="true" />
            </span>
            <p className="text-sm font-medium text-foreground">This template couldn&apos;t be loaded</p>
            <p className="max-w-sm text-sm text-muted-foreground">
              {error || "It may have been withdrawn, or the service is temporarily unavailable."}
            </p>
            <Button variant="outline" size="sm" onClick={load}>
              <RefreshCw size={14} strokeWidth={2} aria-hidden="true" /> Try again
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  const cat = categoryMeta(detail.category);
  const kind = kindMeta(detail.kind);
  const KindIcon = kind.icon;
  const count = typeof detail.importCount === "number" ? detail.importCount : 0;

  return (
    <div className="space-y-8">
      {backLink}

      {/* ---- Header -------------------------------------------------- */}
      <div className="flex flex-col gap-5 border-b border-border pb-6 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex items-start gap-4">
          <CategoryChip categoryId={detail.category} size="xl" />
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span className="cat-tint font-medium" style={{ ["--cat" as string]: cat.cat }}>
                {cat.label}
              </span>
              <span aria-hidden="true">·</span>
              <span className="inline-flex items-center gap-1">
                <KindIcon size={13} strokeWidth={2} aria-hidden="true" />
                {kind.label.replace(/s$/, "")}
              </span>
            </div>
            <h1 className="text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">{detail.title}</h1>
            <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">{detail.summary}</p>
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1 pt-1 text-xs text-muted-foreground">
              <span className="inline-flex items-center gap-1.5">
                <Download size={13} strokeWidth={2} aria-hidden="true" />
                {count > 0 ? `${count.toLocaleString()} ${count === 1 ? "import" : "imports"}` : "No imports yet"}
              </span>
            </div>
            {detail.tags && detail.tags.length > 0 && (
              <div className="flex flex-wrap gap-1.5 pt-1">
                {detail.tags.map((tag) => (
                  <Link
                    key={tag}
                    href={`/templates?tag=${encodeURIComponent(tag)}`}
                    className="rounded-full border border-border/70 px-2 py-0.5 text-[0.625rem] font-medium text-muted-foreground transition-colors hover:text-foreground"
                  >
                    {tag}
                  </Link>
                ))}
              </div>
            )}
          </div>
        </div>

        <div className="shrink-0">
          <Button size="lg" onClick={handleImport} disabled={importing}>
            <Download size={16} strokeWidth={2} aria-hidden="true" />
            {importing ? "Importing…" : "Import template"}
          </Button>
        </div>
      </div>

      {/* ---- Read-only design --------------------------------------- */}
      <div className="space-y-6">
        <p className="text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">
          What you&apos;ll import
        </p>

        <TemplateRequirementsPanel requirements={detail.requirements} />

        {detail.kind === "AGENT" ? <AgentDesign detail={detail} /> : <WorkflowDesign detail={detail} />}

        <Card elevation="sunken" className="gap-2 py-5">
          <CardContent className="flex flex-col gap-1">
            <p className="flex items-center gap-2 text-sm font-medium text-foreground">
              <Lock size={14} strokeWidth={2} aria-hidden="true" /> Importing creates your own copy
            </p>
            <p className="text-sm text-muted-foreground">
              This is a read-only preview. Import creates a private, editable copy in your workspace - the original
              author is never given access to your data or changes.
            </p>
            <div className="pt-2">
              <Button onClick={handleImport} disabled={importing}>
                <Download size={16} strokeWidth={2} aria-hidden="true" />
                {importing ? "Importing…" : "Import template"}
                {!importing && <ArrowRight size={15} strokeWidth={2} aria-hidden="true" />}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function DesignSection({
  icon: Icon,
  title,
  children,
  empty,
}: {
  icon: LucideIcon;
  title: string;
  children?: ReactNode;
  empty?: boolean;
}) {
  return (
    <Card elevation="base" className="gap-3 py-5">
      <CardContent className="space-y-2.5">
        <p className="flex items-center gap-2 text-sm font-medium text-foreground">
          <Icon size={15} strokeWidth={2} aria-hidden />
          {title}
        </p>
        {empty ? <p className="text-sm text-muted-foreground">Not specified.</p> : children}
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// `config` readers.
//
// The detail endpoints return the sanitised snapshot as a plain JSON object
// under `config`. Its INTERNAL key names are not part of the confirmed
// contract (only the envelope is), so everything below probes defensively:
// each reader tries the plausible names, and anything unrecognised falls into
// the "Also included" list rather than being silently dropped. If/when the
// snapshot's internal shape is confirmed, these candidate lists collapse to
// one name each and nothing else has to change.
// ---------------------------------------------------------------------------
type Cfg = Record<string, unknown>;

function asCfg(v: unknown): Cfg {
  return v && typeof v === "object" && !Array.isArray(v) ? (v as Cfg) : {};
}

// First non-empty string found under any of `keys`.
function readString(cfg: Cfg, keys: string[]): string | null {
  for (const k of keys) {
    const v = cfg[k];
    if (typeof v === "string" && v.trim()) return v;
    if (typeof v === "number" || typeof v === "boolean") return String(v);
  }
  return null;
}

// First array found under any of `keys`, flattened to display strings. Handles
// both ["tool_a"] and [{ name: "tool_a" }] shapes.
function readList(cfg: Cfg, keys: string[]): string[] {
  for (const k of keys) {
    const v = cfg[k];
    if (!Array.isArray(v) || v.length === 0) continue;
    const out = v
      .map((item) => {
        if (typeof item === "string") return item;
        if (typeof item === "number" || typeof item === "boolean") return String(item);
        const o = asCfg(item);
        const label = readString(o, ["name", "label", "title", "id", "type"]);
        return label;
      })
      .filter((x): x is string => !!x);
    if (out.length > 0) return out;
  }
  return [];
}

function readNodes(cfg: Cfg): { id: string; label: string; type: string | null }[] {
  for (const k of ["nodes", "steps", "states"]) {
    const v = cfg[k];
    if (!Array.isArray(v) || v.length === 0) continue;
    return v.map((raw, i) => {
      if (typeof raw === "string") return { id: raw, label: raw, type: null };
      const o = asCfg(raw);
      const id = readString(o, ["id", "key", "name"]) || String(i);
      return {
        id,
        label: readString(o, ["label", "name", "title", "id"]) || id,
        type: readString(o, ["type", "kind", "action"]),
      };
    });
  }
  return [];
}

function readEdges(cfg: Cfg): { from: string; to: string; label: string | null }[] {
  for (const k of ["edges", "transitions", "links"]) {
    const v = cfg[k];
    if (!Array.isArray(v) || v.length === 0) continue;
    const out = v
      .map((raw) => {
        const o = asCfg(raw);
        const from = readString(o, ["from", "source", "fromNode"]);
        const to = readString(o, ["to", "target", "toNode"]);
        if (!from || !to) return null;
        return { from, to, label: readString(o, ["label", "condition", "when"]) };
      })
      .filter((x): x is { from: string; to: string; label: string | null } => !!x);
    if (out.length > 0) return out;
  }
  return [];
}

// Keys already surfaced by a dedicated section, so "Also included" doesn't
// repeat them.
function otherKeys(cfg: Cfg, consumed: string[]): string[] {
  const used = new Set(consumed);
  return Object.keys(cfg).filter((k) => {
    if (used.has(k)) return false;
    const v = cfg[k];
    if (v === null || v === undefined) return false;
    if (Array.isArray(v)) return v.length > 0;
    return true;
  });
}

const AGENT_KEYS = [
  "systemPrompt",
  "system_prompt",
  "prompt",
  "instructions",
  "providerName",
  "provider",
  "model",
  "modelName",
  "tools",
  "allowedToolNames",
  "toolNames",
  "mcpTools",
  "guardrails",
  "guardrailSettings",
  "knowledgeCollections",
  "knowledgeHints",
  "knowledge",
  "collections",
];

function AgentDesign({ detail }: { detail: CommunityTemplateDetail }) {
  const cfg = asCfg(detail.config);
  const systemPrompt = readString(cfg, ["systemPrompt", "system_prompt", "prompt", "instructions"]);
  const provider = readString(cfg, ["providerName", "provider"]);
  const model = readString(cfg, ["model", "modelName"]);
  const tools = readList(cfg, ["tools", "allowedToolNames", "toolNames", "mcpTools"]);
  const guardrails = readList(cfg, ["guardrails", "guardrailSettings"]);
  const knowledge = readList(cfg, ["knowledgeCollections", "knowledgeHints", "knowledge", "collections"]);
  const extras = otherKeys(cfg, AGENT_KEYS);

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <div className="lg:col-span-2">
        <DesignSection icon={BookOpen} title="System prompt" empty={!systemPrompt}>
          <pre className="elev-sunken max-h-72 overflow-auto whitespace-pre-wrap rounded-lg p-3 text-xs leading-relaxed text-foreground">
            {systemPrompt}
          </pre>
        </DesignSection>
      </div>

      <DesignSection icon={Cpu} title="Model" empty={!model && !provider}>
        <p className="text-sm text-muted-foreground">{[provider, model].filter(Boolean).join(" · ")}</p>
      </DesignSection>

      <DesignSection icon={Wrench} title="Tools" empty={tools.length === 0}>
        <TagList items={tools} cat="var(--cat-automation)" />
      </DesignSection>

      <DesignSection icon={ShieldCheck} title="Guardrails" empty={guardrails.length === 0}>
        <TagList items={guardrails} cat="var(--cat-analysis)" />
      </DesignSection>

      <DesignSection icon={BookOpen} title="Knowledge" empty={knowledge.length === 0}>
        <TagList items={knowledge} cat="var(--cat-data)" />
      </DesignSection>

      {extras.length > 0 && (
        <div className="lg:col-span-2">
          <ExtraSettings cfg={cfg} keys={extras} />
        </div>
      )}
    </div>
  );
}

const WORKFLOW_KEYS = ["summary", "description", "nodes", "steps", "states", "edges", "transitions", "links"];

function WorkflowDesign({ detail }: { detail: CommunityTemplateDetail }) {
  const cfg = asCfg(detail.config);
  const overview = readString(cfg, ["summary", "description"]);
  const nodes = readNodes(cfg);
  const edges = readEdges(cfg);
  const extras = otherKeys(cfg, WORKFLOW_KEYS);
  const nodeLabel = (nid: string) => nodes.find((n) => n.id === nid)?.label || nid;

  return (
    <div className="space-y-4">
      {overview && (
        <DesignSection icon={BookOpen} title="Overview">
          <p className="text-sm text-muted-foreground">{overview}</p>
        </DesignSection>
      )}

      <DesignSection icon={Wrench} title="Steps" empty={nodes.length === 0}>
        <ol className="space-y-2">
          {nodes.map((n, i) => (
            <li key={`${n.id}-${i}`} className="flex items-start gap-3">
              <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border border-border text-[0.625rem] font-semibold text-muted-foreground">
                {i + 1}
              </span>
              <div>
                <p className="text-sm font-medium text-foreground">{n.label}</p>
                {n.type && <p className="text-xs text-muted-foreground">{n.type}</p>}
              </div>
            </li>
          ))}
        </ol>
      </DesignSection>

      {edges.length > 0 && (
        <DesignSection icon={ArrowRight} title="Flow">
          <ul className="space-y-1.5">
            {edges.map((e, i) => (
              <li key={i} className="flex items-center gap-2 text-sm text-muted-foreground">
                <span className="text-foreground">{nodeLabel(e.from)}</span>
                <ArrowRight size={13} strokeWidth={2} aria-hidden="true" />
                <span className="text-foreground">{nodeLabel(e.to)}</span>
                {e.label && <span className="text-xs">({e.label})</span>}
              </li>
            ))}
          </ul>
        </DesignSection>
      )}

      {extras.length > 0 && <ExtraSettings cfg={cfg} keys={extras} />}
    </div>
  );
}

// Anything in `config` that no dedicated section claimed. Renders primitives
// and string-ish lists inline; for a nested object it names the setting
// without dumping raw JSON at the reader. This exists so an unrecognised
// snapshot field is never invisible - the reader can still see that it's part
// of what they'd import.
function ExtraSettings({ cfg, keys }: { cfg: Cfg; keys: string[] }) {
  return (
    <DesignSection icon={Settings2} title="Also included">
      <dl className="space-y-1.5">
        {keys.map((k) => {
          const v = cfg[k];
          const label = k.replace(/[-_]/g, " ").replace(/([a-z])([A-Z])/g, "$1 $2");
          let rendered: string;
          if (Array.isArray(v)) {
            const items = readList({ [k]: v }, [k]);
            rendered = items.length > 0 ? items.join(", ") : `${v.length} item(s)`;
          } else if (v && typeof v === "object") {
            rendered = "included";
          } else {
            rendered = String(v);
          }
          return (
            <div key={k} className="flex flex-wrap items-baseline gap-x-2 text-sm">
              <dt className="font-medium capitalize text-foreground">{label}:</dt>
              <dd className="text-muted-foreground">{rendered}</dd>
            </div>
          );
        })}
      </dl>
    </DesignSection>
  );
}

function TagList({ items, cat }: { items: string[]; cat: string }) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {items.map((item) => (
        <span
          key={item}
          className="rounded-md border px-2 py-0.5 text-xs font-medium"
          style={{
            color: "var(--cat)",
            borderColor: "color-mix(in srgb, var(--cat) 28%, transparent)",
            backgroundColor: "color-mix(in srgb, var(--cat) 12%, transparent)",
            ["--cat" as string]: cat,
          }}
        >
          {item}
        </span>
      ))}
    </div>
  );
}
