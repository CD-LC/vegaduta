"use client";

import { Badge } from "../components/ui/badge";
import Select from "../components/Select";
import { SwitchToggle } from "../components/sections/common";
import { useTranslations } from "../../lib/i18n";

/** The per-row badges of a hosted-pool row: credential type, region label and the V484 "Free" flag. */
export function HostedRowBadges({
  row,
  credentialTypeLabels,
}: {
  row: {
    authType?: string;
    vertexProjectId?: string;
    region?: string;
    zeroCost?: boolean;
    toolCallGrade?: string | null;
    toolRouterActive?: string | null;
    smallModelActive?: boolean;
    parameterBillions?: number | null;
  };
  credentialTypeLabels: Record<string, string>;
}) {
    const tHC = useTranslations("hcPlatformadminLlmprovidersHostedRowParts");
  return (
    <>
      {row.authType && row.authType !== "API_KEY" && (
        <Badge
          variant="outline"
          className="border-[var(--info-border)] bg-[var(--info-bg)] text-[var(--info-text)]"
          title={`Authenticates via ${credentialTypeLabels[row.authType] || row.authType}${
            row.vertexProjectId ? ` · project ${row.vertexProjectId}` : ""
          }`}
        >
          {credentialTypeLabels[row.authType] || row.authType}
        </Badge>
      )}
      {row.region && (
        <Badge variant="secondary" title={tHC("s11")}>
          {row.region}
        </Badge>
      )}
      {row.zeroCost && (
        <Badge
          variant="secondary"
          title={tHC("s10")}
        >
          Free
        </Badge>
      )}
      <ToolCallGradeBadge grade={row.toolCallGrade} />
      {row.toolRouterActive && (
        <Badge
          variant="secondary"
          title={
            row.toolRouterActive === "REQUIRED"
              ? "Tool turns use tool_choice=required (vLLM): the model must pick a tool or final_answer every round."
              : "Tool turns are routed through a JSON schema the server enforces (Ollama format): the model picks call-or-answer and the chosen call runs through the normal tool loop."
          }
        >
          Routed: {row.toolRouterActive === "REQUIRED" ? "required" : "schema"}
        </Badge>
      )}
      {row.smallModelActive && (
        <Badge
          variant="secondary"
          title={tHC("s9")}
        >
          {tHC("s8")}{row.parameterBillions ? ` · ${row.parameterBillions}B` : ""}
        </Badge>
      )}
    </>
  );
}

/** What the probe found when the row was saved (V499). */
export const TOOL_CALL_GRADE_LABELS: Record<string, { label: string; title: string }> = {
  STRUCTURED: {
    label: "Tools: structured",
    title: "The model answered the save-time probe with a structured tool call. Agents with tools work normally.",
  },
  TEXT: {
    label: "Tools: text",
    title:
      "The model wrote its tool call as text. Core recovers such calls and routes tool turns through a constrained JSON schema; expect slower, less reliable tool use than a structured model.",
  },
  NONE: {
    label: "Chat only",
    title:
      "The model made no tool call (or the server reports no tool support). Skipped for agent turns while any other row is enabled; plain chat still works.",
  },
};

export function ToolCallGradeBadge({ grade }: { grade?: string | null }) {
  if (!grade) return null;
  const meta = TOOL_CALL_GRADE_LABELS[grade];
  if (!meta) return null;
  const tone =
    grade === "STRUCTURED"
      ? "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
      : grade === "TEXT"
        ? "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]"
        : "border-[var(--danger-border)] bg-[var(--danger-bg)] text-[var(--danger-text)]";
  return (
    <Badge variant="outline" className={tone} title={meta.title}>
      {meta.label}
    </Badge>
  );
}

/**
 * V484 - marks a hosted-pool row as a model the operator runs themselves (Ollama, vLLM,
 * llama.cpp, any OpenAI-compatible server nobody bills per token). Hidden for Vertex rows,
 * which are always metered.
 */
export function ZeroCostToggle({ authType, on, onToggle }: { authType?: string; on: boolean; onToggle: () => void }) {
    const tHC = useTranslations("hcPlatformadminLlmprovidersHostedRowParts");
  if (authType === "VERTEX_ADC" || authType === "VERTEX_SERVICE_ACCOUNT") return null;
  return (
    <div className="flex items-center gap-2">
      <SwitchToggle on={on} label={tHC("s7")} onClick={onToggle} />
      <span
        className="text-xs text-muted-foreground"
        title={tHC("s6")}
      >
        {tHC("s5")}
      </span>
    </div>
  );
}

const TRI_STATE = [
  { value: "", label: "Auto" },
  { value: "on", label: "On" },
  { value: "off", label: "Off" },
];

function TriStateControl({
  id,
  label,
  title,
  value,
  onChange,
}: {
  id: string;
  label: string;
  title: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="flex items-center gap-2">
      <label className="text-xs text-muted-foreground" htmlFor={id} title={title}>
        {label}
      </label>
      <Select id={id} className="text-xs" value={value} onChange={(e) => onChange(e.target.value)} options={TRI_STATE} />
    </div>
  );
}

/**
 * The small-model controls of a hosted row (hidden for Vertex rows, which are always hosted
 * frontier models): the V484 free flag plus the V500 constrained-router override. One component so
 * page.tsx mounts them in one line.
 */
export function LocalModelControls({
  draft,
  setDraft,
}: {
  draft: { authType?: string; zeroCost?: boolean; toolRouterEnabled?: string; smallModel?: string };
  setDraft: (update: (d: any) => any) => void;
}) {
    const tHC = useTranslations("hcPlatformadminLlmprovidersHostedRowParts");
  if (draft.authType === "VERTEX_ADC" || draft.authType === "VERTEX_SERVICE_ACCOUNT") return null;
  return (
    <div className="space-y-2">
      <ZeroCostToggle
        authType={draft.authType}
        on={Boolean(draft.zeroCost)}
        onToggle={() => setDraft((d) => ({ ...d, zeroCost: !d.zeroCost }))}
      />
      <TriStateControl
        id="hosted-tool-router"
        label={tHC("s4")}
        title={tHC("s3")}
        value={draft.toolRouterEnabled ?? ""}
        onChange={(v) => setDraft((d) => ({ ...d, toolRouterEnabled: v }))}
      />
      <TriStateControl
        id="hosted-small-model"
        label={tHC("s2")}
        title={tHC("s1")}
        value={draft.smallModel ?? ""}
        onChange={(v) => setDraft((d) => ({ ...d, smallModel: v }))}
      />
    </div>
  );
}
