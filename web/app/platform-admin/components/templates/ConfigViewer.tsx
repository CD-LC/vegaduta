"use client";

import { useState } from "react";
import {
  MessageSquareText,
  Cpu,
  Wrench,
  ShieldCheck,
  BookOpen,
  SlidersHorizontal,
  Workflow,
  ArrowRight,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  Code2,
} from "lucide-react";
import { Badge } from "../ui/badge";
import type {
  TemplateConfig,
  TemplateKind,
  AgentTemplateConfig,
  WorkflowTemplateConfig,
  WorkflowTemplateNode,
} from "../../templates/types";
import { useTranslations } from "../../../lib/i18n";

// THE core of the moderation job: render a submission's full config so a
// human can actually judge it, never a raw JSON blob (docs/
// COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md - "a moderator approving blind
// is the failure mode this whole feature exists to prevent"). Every field a
// moderator needs to reason about safety/quality gets its own labeled,
// legible block; a collapsed "raw JSON" panel at the bottom is a secondary
// escape hatch for auditing, not how this is meant to be read.

function humanizeKey(key: string): string {
  const spaced = key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/_/g, " ")
    .toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function Section({ title, icon: Icon, children }: { title: any; icon?: any; children: any }) {
  return (
    <div className="rounded-lg border border-border p-4">
      <div className="mb-2.5 flex items-center gap-2 text-sm font-semibold text-foreground">
        {Icon && <Icon size={15} className="text-primary" />}
        {title}
      </div>
      {children}
    </div>
  );
}

function ChipList({
  items,
  emptyText,
  tone = "default",
}: {
  items?: string[];
  emptyText: string;
  tone?: "default" | "warn";
}) {
  if (!items || items.length === 0) {
    return (
      <div
        className={`flex items-start gap-2 rounded-md border px-3 py-2 text-xs ${
          tone === "warn"
            ? "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]"
            : "border-border bg-secondary/30 text-muted-foreground"
        }`}
      >
        {tone === "warn" && <AlertTriangle size={13} className="mt-0.5 shrink-0" />}
        <span>{emptyText}</span>
      </div>
    );
  }
  return (
    <div className="flex flex-wrap gap-1.5">
      {items.map((item, i) => (
        <Badge key={`${item}-${i}`} variant="secondary" className="font-mono text-[11px]">
          {item}
        </Badge>
      ))}
    </div>
  );
}

function KeyValueGrid({ pairs }: { pairs: Array<[string, any]> }) {
  const present = pairs.filter(([, v]) => v !== undefined && v !== null && v !== "");
  if (present.length === 0) return null;
  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs sm:grid-cols-3">
      {present.map(([label, value]) => (
        <div key={label} className="min-w-0">
          <div className="text-muted-foreground">{label}</div>
          <div className="truncate font-mono text-foreground" title={String(value)}>
            {String(value)}
          </div>
        </div>
      ))}
    </div>
  );
}

// Recursive renderer for arbitrary node.data.config maps - indented
// key: value lines rather than a JSON dump, so a nested workflow-node config
// still reads as data, not code.
function NestedObject({ value, depth = 0 }: { value: any; depth?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsTemplatesConfigViewer");
  if (value === null || value === undefined) return <span className="text-muted-foreground">—</span>;
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-muted-foreground">{tHC("s11")}</span>;
    if (value.every((v) => typeof v !== "object" || v === null)) {
      return (
        <div className="flex flex-wrap gap-1">
          {value.map((v, i) => (
            <Badge key={i} variant="outline" className="font-mono text-2xs">
              {String(v)}
            </Badge>
          ))}
        </div>
      );
    }
    return (
      <div className="space-y-1.5">
        {value.map((v, i) => (
          <div key={i} className="rounded border border-border/70 bg-background/50 p-1.5">
            <NestedObject value={v} depth={depth + 1} />
          </div>
        ))}
      </div>
    );
  }
  if (typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return <span className="text-muted-foreground">(empty)</span>;
    return (
      <div className={depth > 0 ? "space-y-1 border-s border-border/70 ps-3" : "space-y-1"}>
        {entries.map(([k, v]) => (
          <div key={k} className="text-xs">
            <span className="text-muted-foreground">{humanizeKey(k)}: </span>
            {typeof v === "object" && v !== null ? (
              <div className="mt-0.5">
                <NestedObject value={v} depth={depth + 1} />
              </div>
            ) : (
              <span className="font-mono text-foreground">{String(v)}</span>
            )}
          </div>
        ))}
      </div>
    );
  }
  return <span className="font-mono text-foreground">{String(value)}</span>;
}

function AgentConfigView({ config }: { config: AgentTemplateConfig }) {
    const tHC = useTranslations("hcPlatformadminComponentsTemplatesConfigViewer");
  const modelPairs: Array<[string, any]> = [
    ["Provider", config.providerName],
    ["Model", config.model],
    ["Fallback provider", config.fallbackProviderName],
    ["Fallback model", config.fallbackModel],
    ["Economy provider", config.economyProviderName],
    ["Economy model", config.economyModel],
    ["Text model", config.textModel ? `${config.textModelProvider ?? ""} ${config.textModel}`.trim() : undefined],
    ["Voice model", config.voiceModel ? `${config.voiceModelProvider ?? ""} ${config.voiceModel}`.trim() : undefined],
    ["Video model", config.videoModel ? `${config.videoModelProvider ?? ""} ${config.videoModel}`.trim() : undefined],
  ];
  const tuningPairs: Array<[string, any]> = [
    ["Autonomy tier", config.autonomyTier],
    ["Reasoning depth", config.reasoningDepth],
    ["Tuning mode", config.tuningMode],
    ["Experience mode", config.experienceMode],
    ["Temperature", config.temperature],
    ["Top P", config.topP],
    ["Max tokens", config.maxTokens],
  ];

  return (
    <div className="space-y-3">
      <Section title={tHC("s10")} icon={MessageSquareText}>
        {config.systemPrompt ? (
          <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-secondary/30 p-3 font-mono text-xs leading-relaxed text-foreground">
            {config.systemPrompt}
          </pre>
        ) : (
          <div className="flex items-center gap-2 rounded-md border border-[var(--warning-border)] bg-[var(--warning-bg)] px-3 py-2 text-xs text-[var(--warning-text)]">
            <AlertTriangle size={13} /> {tHC("s9")}
          </div>
        )}
      </Section>

      <Section title={tHC("s8")} icon={Cpu}>
        <KeyValueGrid pairs={modelPairs} />
      </Section>

      <Section title={tHC("s7")} icon={Wrench}>
        <ChipList
          items={config.allowedToolNames}
          tone="warn"
          emptyText="No tool restriction configured - this agent can invoke any available tool. Review carefully."
        />
      </Section>

      <Section title="Guardrails" icon={ShieldCheck}>
        <ChipList items={config.enabledGuardrails} tone="warn" emptyText="No guardrails enabled on this submission." />
      </Section>

      {config.knowledgeCollections && config.knowledgeCollections.length > 0 && (
        <Section title={tHC("s6")} icon={BookOpen}>
          <div className="space-y-1.5">
            {config.knowledgeCollections.map((kc, i) => (
              <div key={i} className="rounded-md border border-border bg-secondary/20 px-3 py-1.5 text-xs">
                <span className="font-medium text-foreground">{kc.name}</span>
                {kc.description && <span className="text-muted-foreground"> — {kc.description}</span>}
              </div>
            ))}
          </div>
        </Section>
      )}

      {tuningPairs.some(([, v]) => v !== undefined && v !== null && v !== "") && (
        <Section title="Tuning" icon={SlidersHorizontal}>
          <KeyValueGrid pairs={tuningPairs} />
        </Section>
      )}
    </div>
  );
}

function NodeCard({ node }: { node: WorkflowTemplateNode }) {
  const label = node.data?.label || node.id;
  const config = node.data?.config;
  return (
    <div className="rounded-md border border-border bg-secondary/20 p-3">
      <div className="flex items-center gap-2">
        <Badge variant="accent" className="font-mono text-[10.5px] uppercase tracking-wide">
          {node.type}
        </Badge>
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">{label}</span>
      </div>
      {config && Object.keys(config).length > 0 && (
        <div className="mt-2 border-t border-border/60 pt-2">
          <NestedObject value={config} />
        </div>
      )}
    </div>
  );
}

function WorkflowConfigView({ config }: { config: WorkflowTemplateConfig }) {
    const tHC = useTranslations("hcPlatformadminComponentsTemplatesConfigViewer");
  const nodes = config.graph?.nodes || [];
  const edges = config.graph?.edges || [];
  const labelOf = (id: string) => nodes.find((n) => n.id === id)?.data?.label || id;

  return (
    <div className="space-y-3">
      {(config.name || config.description) && (
        <Section title="Workflow" icon={Workflow}>
          {config.name && <div className="text-sm font-medium text-foreground">{config.name}</div>}
          {config.description && <p className="mt-1 text-xs text-muted-foreground">{config.description}</p>}
        </Section>
      )}

      <Section title={`Steps (${nodes.length})`} icon={Workflow}>
        {nodes.length === 0 ? (
          <div className="flex items-center gap-2 rounded-md border border-[var(--warning-border)] bg-[var(--warning-bg)] px-3 py-2 text-xs text-[var(--warning-text)]">
            <AlertTriangle size={13} /> {tHC("s5")}
          </div>
        ) : (
          <div className="space-y-2">
            {nodes.map((n) => (
              <NodeCard key={n.id} node={n} />
            ))}
          </div>
        )}
      </Section>

      {edges.length > 0 && (
        <Section title="Flow" icon={ArrowRight}>
          <div className="flex flex-col gap-1 text-xs">
            {edges.map((e) => (
              <div key={e.id} className="flex items-center gap-1.5 font-mono text-foreground">
                <span className="min-w-0 flex-1 truncate">{labelOf(e.source)}</span>
                <ArrowRight size={12} className="shrink-0 text-muted-foreground" />
                <span className="min-w-0 flex-1 truncate">{labelOf(e.target)}</span>
              </div>
            ))}
          </div>
        </Section>
      )}
    </div>
  );
}

export default function ConfigViewer({ kind, config }: { kind: TemplateKind | string; config?: TemplateConfig }) {
    const tHC = useTranslations("hcPlatformadminComponentsTemplatesConfigViewer");
  const [rawOpen, setRawOpen] = useState(false);

  if (!config || Object.keys(config).length === 0) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2.5 text-xs text-destructive">
        <AlertTriangle size={14} /> {tHC("s4")}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {kind === "AGENT" && <AgentConfigView config={config as AgentTemplateConfig} />}
      {kind === "WORKFLOW" && <WorkflowConfigView config={config as WorkflowTemplateConfig} />}
      {kind !== "AGENT" && kind !== "WORKFLOW" && (
        <div className="flex items-center gap-2 rounded-md border border-[var(--warning-border)] bg-[var(--warning-bg)] px-3 py-2 text-xs text-[var(--warning-text)]">
          <AlertTriangle size={13} /> {tHC("s3")}{String(kind)}{tHC("s2")}
        </div>
      )}

      <button
        type="button"
        onClick={() => setRawOpen((o) => !o)}
        className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground hover:text-foreground"
      >
        {rawOpen ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
        <Code2 size={13} />
        {tHC("s1")}
      </button>
      {rawOpen && (
        <pre className="max-h-96 overflow-auto rounded-md border border-border bg-secondary/30 p-3 font-mono text-[11px] leading-relaxed text-foreground">
          {JSON.stringify(config, null, 2)}
        </pre>
      )}
    </div>
  );
}
