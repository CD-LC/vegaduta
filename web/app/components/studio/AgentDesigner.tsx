"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Save,
  Sparkles,
  Lock,
  Hand,
  Brain,
  ShieldCheck,
  Gauge,
  Loader2,
  FileText,
  Plug,
  NotebookPen,
  SlidersHorizontal,
  Undo2,
} from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { Switch } from "../ui/switch";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "../ui/tabs";
import { studio, type AgentSpec, type StudioAgent, type StudioPalette } from "./studioClient";
import { AUTONOMY_TIERS, GUARDRAILS, REASONING_DEPTHS, parseConnectorTool } from "./studioContent";
import AgentKnowledge from "./AgentKnowledge";
import AgentConnectors from "./AgentConnectors";
import { TONES, pick } from "./studioTheme";
import { useTranslations } from "../../lib/i18n";

// The designer: every control here is a real column on a real agent row in
// the demo tenant, written through the same AgentService setters the paid
// console uses. Locked options are shown, labelled as locked, with the
// reason - a control that silently does nothing is the one thing this page
// must never contain.
//
// Five tabs: Brief (who it is), Hands (built-in tools), Knowledge (documents
// it can search), Connectors (MCP servers from the public registry), Behavior
// (thinking, autonomy, rules, dials). Brief/Hands/Behavior edit one draft and
// save together; Knowledge and Connectors act immediately, because a file
// upload or a connection is an event, not a form field.

export type DesignerDraft = {
  name: string;
  mission: string;
  reasoningDepth: string;
  autonomy: string;
  tools: string[];
  guardrails: string[];
  temperature: number;
  maxTokens: number;
};

export function draftFromAgent(agent: StudioAgent): DesignerDraft {
  return {
    name: agent.name,
    mission: agent.mission,
    reasoningDepth: agent.reasoningDepth,
    autonomy: agent.autonomy,
    tools: agent.tools,
    guardrails: agent.guardrails,
    temperature: agent.temperature,
    maxTokens: agent.maxTokens,
  };
}

export function blankDraft(): DesignerDraft {
  return {
    name: "",
    mission: "",
    reasoningDepth: "CAREFUL",
    autonomy: "SEMI_AUTONOMOUS",
    tools: [],
    guardrails: [],
    temperature: 0.4,
    maxTokens: 1024,
  };
}

/** Only the fields that changed, so a save is a minimal PATCH and an unchanged mission doesn't bump the agent version. */
export function diffSpec(base: DesignerDraft | null, draft: DesignerDraft): AgentSpec {
  const spec: AgentSpec = {};
  const same = (a: string[], b: string[]) => a.length === b.length && a.every((x) => b.includes(x));
  if (!base || base.mission !== draft.mission) spec.mission = draft.mission;
  if (!base || base.reasoningDepth !== draft.reasoningDepth) spec.reasoningDepth = draft.reasoningDepth;
  if (!base || base.autonomy !== draft.autonomy) spec.autonomy = draft.autonomy;
  if (!base || !same(base.tools, draft.tools)) spec.tools = draft.tools;
  if (!base || !same(base.guardrails, draft.guardrails)) spec.guardrails = draft.guardrails;
  if (!base || Math.abs(base.temperature - draft.temperature) > 1e-6) spec.temperature = draft.temperature;
  if (!base || base.maxTokens !== draft.maxTokens) spec.maxTokens = draft.maxTokens;
  if (!base) spec.name = draft.name;
  return spec;
}

export type DesignerTab = "brief" | "hands" | "knowledge" | "connectors" | "behavior";

type Props = {
  token: string;
  agent: StudioAgent | null; // null = creating a new one by hand
  palette: StudioPalette | null;
  readOnly: boolean;
  saving: boolean;
  /** A suggested connector search (from the launcher's topic) - optional. */
  mcpHint?: string;
  /** What the agent is for, in the visitor's words - used to phrase nudges. */
  topicHint?: string;
  onSave: (spec: AgentSpec, draft: DesignerDraft) => Promise<boolean>;
  onCancelNew?: () => void;
  /** Documents or connectors changed the agent server-side (tool allow-list) - reload it. */
  onAgentRefresh: () => void;
  initialTab?: DesignerTab;
};

const toggle = (list: string[], item: string) =>
  list.includes(item) ? list.filter((x) => x !== item) : [...list, item];

const TOOL_GROUPS: { id: "hands" | "memory"; title: string; blurb: string }[] = [
  { id: "hands", title: "Hands", blurb: "Reach out into the world" },
  { id: "memory", title: "Memory", blurb: "Remember and recall" },
];

export default function AgentDesigner({
  token,
  agent,
  palette,
  readOnly,
  saving,
  mcpHint,
  topicHint,
  onSave,
  onCancelNew,
  onAgentRefresh,
  initialTab,
}: Props) {
    const tHC = useTranslations("hcComponentsStudioAgentDesigner");
  const base = useMemo(() => (agent ? draftFromAgent(agent) : null), [agent]);
  const [draft, setDraft] = useState<DesignerDraft>(() => base ?? blankDraft());
  const [savedFlash, setSavedFlash] = useState(false);
  const [tab, setTab] = useState<DesignerTab>(initialTab ?? "brief");
  const [docCount, setDocCount] = useState<number | null>(null);

  useEffect(() => {
    setDraft(base ?? blankDraft());
  }, [base]);

  useEffect(() => {
    if (initialTab) setTab(initialTab);
  }, [initialTab, agent?.id]);

  const agentId = agent?.id ?? null;
  const refreshDocCount = useCallback(async () => {
    if (!agentId) {
      setDocCount(null);
      return;
    }
    const res = await studio.listDocuments(token, agentId);
    setDocCount(res.ok ? res.value.length : 0);
  }, [token, agentId]);

  useEffect(() => {
    void refreshDocCount();
  }, [refreshDocCount]);

  const spec = useMemo(() => diffSpec(base, draft), [base, draft]);
  const dirty = Object.keys(spec).length > 0;
  const canSave = dirty && draft.name.trim().length > 0 && draft.mission.trim().length > 0 && !readOnly && !saving;

  const availableTools = palette?.tools ?? [];
  const enforcedRules = new Set(palette?.enforcedGuardrails ?? []);
  const lockedAutonomy = new Set(palette?.lockedAutonomyTiers ?? ["SUPERVISED"]);
  const guardAvailability = new Map((palette?.guardrails ?? []).map((g) => [g.id, g.available]));
  const connectorTools = draft.tools.filter((t) => parseConnectorTool(t));
  const connectorServers = new Set(connectorTools.map((t) => parseConnectorTool(t)!.server));
  const catalogOn = draft.tools.filter((t) => !parseConnectorTool(t));

  const submit = async () => {
    if (!canSave) return;
    const ok = await onSave(spec, draft);
    if (ok) {
      setSavedFlash(true);
      window.setTimeout(() => setSavedFlash(false), 1800);
    }
  };

  const changed = () => {
    onAgentRefresh();
    void refreshDocCount();
  };

  return (
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_260px]">
      <Tabs value={tab} onValueChange={(v) => setTab(v as DesignerTab)} className="min-w-0 gap-5">
        <TabsList className="h-auto w-full flex-wrap justify-start gap-1 p-1" data-tour="designer-tabs">
          <TabsTrigger
            value="brief"
            className="flex-none px-3 py-1.5 data-[state=active]:bg-violet-600 data-[state=active]:text-white data-[state=active]:shadow-md data-[state=active]:shadow-violet-500/30"
          >
            <NotebookPen aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Brief
          </TabsTrigger>
          <TabsTrigger
            value="hands"
            className="flex-none px-3 py-1.5 data-[state=active]:bg-violet-600 data-[state=active]:text-white data-[state=active]:shadow-md data-[state=active]:shadow-violet-500/30"
          >
            <Hand aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Hands
            <span className="rounded-full bg-background/70 px-1.5 text-[10px]">{catalogOn.length}</span>
          </TabsTrigger>
          <TabsTrigger
            value="knowledge"
            className="flex-none px-3 py-1.5 data-[state=active]:bg-violet-600 data-[state=active]:text-white data-[state=active]:shadow-md data-[state=active]:shadow-violet-500/30"
            disabled={!agent}
          >
            <FileText aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Knowledge
            {docCount ? (
              <span className="rounded-full bg-primary/15 px-1.5 text-[10px] text-primary">{docCount}</span>
            ) : null}
          </TabsTrigger>
          <TabsTrigger
            value="connectors"
            className="flex-none px-3 py-1.5 data-[state=active]:bg-violet-600 data-[state=active]:text-white data-[state=active]:shadow-md data-[state=active]:shadow-violet-500/30"
            disabled={!agent}
          >
            <Plug aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Connectors
            {connectorServers.size ? (
              <span className="rounded-full bg-primary/15 px-1.5 text-[10px] text-primary">
                {connectorServers.size}
              </span>
            ) : null}
          </TabsTrigger>
          <TabsTrigger
            value="behavior"
            className="flex-none px-3 py-1.5 data-[state=active]:bg-violet-600 data-[state=active]:text-white data-[state=active]:shadow-md data-[state=active]:shadow-violet-500/30"
          >
            <SlidersHorizontal aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Behavior
          </TabsTrigger>
        </TabsList>

        {/* ---------------- Brief ---------------- */}
        <TabsContent value="brief" className="space-y-5">
          <section className="space-y-3">
            <div>
              <label className="mb-1 block text-sm font-medium" htmlFor="studio-agent-name">
                Name
              </label>
              <Input
                id="studio-agent-name"
                value={draft.name}
                disabled={!!agent || readOnly}
                maxLength={60}
                placeholder={tHC("hcComponentsStudioAgentDesigner.s23")}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
              {agent && (
                <p className="mt-1 text-xs text-muted-foreground">
                  {tHC("hcComponentsStudioAgentDesigner.s22")}
                </p>
              )}
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium" htmlFor="studio-agent-mission">
                Mission{" "}
                <span className="font-normal text-muted-foreground">{tHC("hcComponentsStudioAgentDesigner.s21")}</span>
              </label>
              <Textarea
                id="studio-agent-mission"
                value={draft.mission}
                disabled={readOnly}
                rows={12}
                maxLength={4000}
                placeholder={tHC("hcComponentsStudioAgentDesigner.s20")}
                onChange={(e) => setDraft({ ...draft, mission: e.target.value })}
                className="font-mono text-[13px] leading-relaxed"
              />
              <div className="mt-1 text-end text-[11px] text-muted-foreground">{draft.mission.length}/4000</div>
            </div>
          </section>
        </TabsContent>

        {/* ---------------- Hands ---------------- */}
        <TabsContent value="hands" className="space-y-5">
          <p className="text-xs text-muted-foreground">
            {tHC("hcComponentsStudioAgentDesigner.s19")}
          </p>
          {TOOL_GROUPS.map((g) => {
            const items = availableTools.filter((t) => t.group === g.id);
            if (items.length === 0) return null;
            return (
              <section key={g.id}>
                <div className="mb-2 flex items-baseline gap-2 text-sm font-medium">
                  {g.title} <span className="text-xs font-normal text-muted-foreground">{g.blurb}</span>
                </div>
                <div className="grid gap-2 sm:grid-cols-2">
                  {items.map((t) => {
                    const on = draft.tools.includes(t.name);
                    return (
                      <div
                        key={t.name}
                        className={`flex items-start gap-3 rounded-xl border p-3 text-sm transition-colors ${
                          !t.available ? "border-border/60 opacity-60" : pick("emerald", on)
                        }`}
                      >
                        <Switch
                          className={`mt-0.5 ${on ? "bg-emerald-600" : ""}`}
                          checked={on}
                          aria-label={t.label}
                          disabled={!t.available || readOnly}
                          onCheckedChange={() => setDraft({ ...draft, tools: toggle(draft.tools, t.name) })}
                        />
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2 font-medium">
                            {t.label}
                            {!t.available && t.name === "search_knowledge_base" && (
                              <Badge variant="outline" className="text-[10px]">
                                <FileText aria-hidden="true" /> {tHC("hcComponentsStudioAgentDesigner.s18")}
                              </Badge>
                            )}
                            {!t.available && t.name !== "search_knowledge_base" && (
                              <Badge variant="outline" className="text-[10px]">
                                <Lock aria-hidden="true" /> {tHC("hcComponentsStudioAgentDesigner.s17")}
                              </Badge>
                            )}
                          </div>
                          <div className="text-xs text-muted-foreground">{t.description}</div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </section>
            );
          })}
          {availableTools.length === 0 && (
            <div className="text-xs text-muted-foreground">{tHC("hcComponentsStudioAgentDesigner.s16")}</div>
          )}
          {connectorTools.length > 0 && (
            <div className="rounded-xl border border-border/60 p-3 text-xs text-muted-foreground">
              Plus {connectorTools.length} tool{connectorTools.length === 1 ? "" : "s"} from {connectorServers.size}{" "}
              connector{connectorServers.size === 1 ? "" : "s"} {tHC("hcComponentsStudioAgentDesigner.s15")}
            </div>
          )}
          {draft.tools.length === 0 && (
            <div className="inline-flex items-center gap-2 rounded-lg bg-amber-500/10 px-3 py-1.5 text-xs text-amber-800 dark:text-amber-300">
              <Sparkles aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> {tHC("hcComponentsStudioAgentDesigner.s14")}
            </div>
          )}
        </TabsContent>

        {/* ---------------- Knowledge ---------------- */}
        <TabsContent value="knowledge">
          {agent ? (
            <AgentKnowledge
              token={token}
              agent={agent}
              limits={palette?.documents}
              readOnly={readOnly}
              topicHint={topicHint ?? "a policy, a price list or your own notes"}
              onChanged={changed}
            />
          ) : (
            <p className="text-sm text-muted-foreground">{tHC("hcComponentsStudioAgentDesigner.s13")}</p>
          )}
        </TabsContent>

        {/* ---------------- Connectors ---------------- */}
        <TabsContent value="connectors">
          {agent ? (
            <AgentConnectors
              token={token}
              agent={agent}
              readOnly={readOnly}
              connectorsEnabled={!!palette?.connectorsEnabled}
              suggestions={mcpHint ? [mcpHint] : []}
              onChanged={changed}
            />
          ) : (
            <p className="text-sm text-muted-foreground">{tHC("hcComponentsStudioAgentDesigner.s12")}</p>
          )}
        </TabsContent>

        {/* ---------------- Behavior ---------------- */}
        <TabsContent value="behavior" className="space-y-6">
          <section className="grid gap-4 md:grid-cols-2" data-tour="dials">
            <div>
              <div className="mb-1 flex items-center gap-2 text-sm font-medium">
                <Brain aria-hidden="true" className="h-4 w-4 shrink-0" /> Thinking
              </div>
              <div className="space-y-1.5">
                {REASONING_DEPTHS.map((d) => (
                  <label
                    key={d.id}
                    className={`flex cursor-pointer items-start gap-3 rounded-xl border p-2.5 text-sm ${pick(
                      "amber",
                      draft.reasoningDepth === d.id
                    )}`}
                  >
                    <input
                      type="radio"
                      name="depth"
                      className="mt-1"
                      checked={draft.reasoningDepth === d.id}
                      disabled={readOnly}
                      onChange={() => setDraft({ ...draft, reasoningDepth: d.id })}
                    />
                    <span>
                      <span className="font-medium">{d.label}</span>
                      <span className="block text-xs text-muted-foreground">{d.blurb}</span>
                    </span>
                  </label>
                ))}
              </div>
            </div>
            <div>
              <div className="mb-1 flex items-center gap-2 text-sm font-medium">
                <Gauge aria-hidden="true" className="h-4 w-4 shrink-0" /> Autonomy
              </div>
              <div className="space-y-1.5">
                {AUTONOMY_TIERS.map((a) => {
                  const locked = lockedAutonomy.has(a.id);
                  return (
                    <label
                      key={a.id}
                      className={`flex items-start gap-3 rounded-xl border p-2.5 text-sm ${locked ? "cursor-not-allowed opacity-70" : "cursor-pointer"} ${pick(
                        "sky",
                        draft.autonomy === a.id
                      )}`}
                    >
                      <input
                        type="radio"
                        name="autonomy"
                        className="mt-1"
                        checked={draft.autonomy === a.id}
                        disabled={locked || readOnly}
                        onChange={() => setDraft({ ...draft, autonomy: a.id })}
                      />
                      <span>
                        <span className="flex items-center gap-2 font-medium">
                          {a.label}
                          {locked && (
                            <Badge variant="outline" className="text-[10px]">
                              <Lock aria-hidden="true" /> {tHC("hcComponentsStudioAgentDesigner.s11")}
                            </Badge>
                          )}
                        </span>
                        <span className="block text-xs text-muted-foreground">
                          {a.blurb}
                          {locked &&
                            " The approvals inbox lives in your workspace - the workflow's approval step shows the same pause here."}
                        </span>
                      </span>
                    </label>
                  );
                })}
              </div>
            </div>
          </section>

          <section>
            <div className="mb-1 flex items-center gap-2 text-sm font-medium">
              <ShieldCheck aria-hidden="true" className="h-4 w-4 shrink-0" /> Rules{" "}
              <span className="font-normal text-muted-foreground">{tHC("hcComponentsStudioAgentDesigner.s10")}</span>
            </div>
            <div className="grid gap-2 sm:grid-cols-2">
              {GUARDRAILS.map((g) => {
                const available = guardAvailability.get(g.id) ?? false;
                const enforced = enforcedRules.has(g.id);
                const on = enforced || draft.guardrails.includes(g.id);
                return (
                  <div
                    key={g.id}
                    className={`flex min-w-0 items-start gap-3 rounded-xl border p-3 text-sm ${pick("emerald", on)}`}
                  >
                    <Switch
                      className={`mt-0.5 shrink-0 disabled:opacity-100 ${on ? "bg-emerald-700" : ""}`}
                      checked={on}
                      aria-label={g.label}
                      aria-describedby={`rule-${g.id}-desc`}
                      disabled={!available || readOnly || enforced}
                      onCheckedChange={() => setDraft({ ...draft, guardrails: toggle(draft.guardrails, g.id) })}
                    />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 font-medium text-foreground">
                        <span>{g.label}</span>
                        {enforced && (
                          <Badge variant="outline" className="text-[11px] text-foreground">
                            <ShieldCheck aria-hidden="true" /> {tHC("hcComponentsStudioAgentDesigner.s9")}
                          </Badge>
                        )}
                        {!available && !enforced && (
                          <Badge variant="outline" className="text-[11px] text-foreground">
                            <Lock aria-hidden="true" /> {tHC("hcComponentsStudioAgentDesigner.s8")}
                          </Badge>
                        )}
                      </div>
                      <div
                        id={`rule-${g.id}-desc`}
                        className="mt-0.5 text-xs leading-relaxed text-slate-700 dark:text-slate-300"
                      >
                        {g.blurb}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </section>

          <section className="grid gap-4 md:grid-cols-2">
            <div>
              <label className="mb-1 flex items-center justify-between text-sm font-medium" htmlFor="studio-temp">
                <span>Creativity</span>
                <span className="text-xs font-normal text-muted-foreground">
                  temperature {draft.temperature.toFixed(2)}
                </span>
              </label>
              <input
                id="studio-temp"
                type="range"
                min={0}
                max={1.5}
                step={0.05}
                value={draft.temperature}
                disabled={readOnly}
                onChange={(e) => setDraft({ ...draft, temperature: Number(e.target.value) })}
                className="w-full"
              />
              <p className="text-xs text-muted-foreground">
                {tHC("hcComponentsStudioAgentDesigner.s7")}
              </p>
            </div>
            <div>
              <label className="mb-1 flex items-center justify-between text-sm font-medium" htmlFor="studio-tokens">
                <span>{tHC("hcComponentsStudioAgentDesigner.s6")}</span>
                <span className="text-xs font-normal text-muted-foreground">up to {draft.maxTokens} tokens</span>
              </label>
              <input
                id="studio-tokens"
                type="range"
                min={128}
                max={4096}
                step={64}
                value={draft.maxTokens}
                disabled={readOnly}
                onChange={(e) => setDraft({ ...draft, maxTokens: Number(e.target.value) })}
                className="w-full"
              />
              <p className="text-xs text-muted-foreground">
                {tHC("hcComponentsStudioAgentDesigner.s5")}
              </p>
            </div>
          </section>
        </TabsContent>

        {/* Save bar - for the tabs that edit the draft. Knowledge and Connectors act immediately. */}
        {(tab === "brief" || tab === "hands" || tab === "behavior") && (
          <div className="sticky bottom-2 z-10 flex flex-wrap items-center gap-3 rounded-xl border border-border/60 bg-background/90 p-3 shadow-sm backdrop-blur">
            <Button className={TONES.violet.cta} onClick={submit} disabled={!canSave}>
              {saving ? (
                <Loader2 aria-hidden="true" className="me-2 h-4 w-4 animate-spin shrink-0" />
              ) : (
                <Save aria-hidden="true" className="me-2 h-4 w-4 shrink-0" />
              )}
              {agent ? "Save changes" : "Create agent"}
            </Button>
            {agent && dirty && !saving && (
              <Button variant="ghost" onClick={() => setDraft(base ?? blankDraft())}>
                <Undo2 aria-hidden="true" className="me-1.5 h-4 w-4 shrink-0" /> Discard
              </Button>
            )}
            {!agent && onCancelNew && (
              <Button variant="ghost" onClick={onCancelNew} disabled={saving}>
                Cancel
              </Button>
            )}
            {savedFlash && (
              <span className="text-sm text-emerald-600">{tHC("hcComponentsStudioAgentDesigner.s4")}</span>
            )}
            {readOnly && <span className="text-sm text-muted-foreground">{tHC("hcComponentsStudioAgentDesigner.s3")}</span>}
            {!readOnly && agent && !dirty && !savedFlash && (
              <span className="text-xs text-muted-foreground">{tHC("hcComponentsStudioAgentDesigner.s2")}</span>
            )}
          </div>
        )}
      </Tabs>

      {/* Blueprint: the agent at a glance, always in view. */}
      <aside className="hidden xl:block">
        <div className="sticky top-24 space-y-3 rounded-2xl border border-border/60 bg-muted/30 p-4">
          <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Blueprint</div>
          <div className="text-base font-semibold leading-tight">{draft.name || "Unnamed agent"}</div>
          <div className="space-y-2 text-sm">
            <Meter icon={Brain} label="Thinking" value={draft.reasoningDepth.toLowerCase()} on />
            <Meter
              icon={Hand}
              label="Hands"
              value={
                draft.tools.length === 0
                  ? "none - chatbot"
                  : `${catalogOn.length} tool${catalogOn.length === 1 ? "" : "s"}`
              }
              on={catalogOn.length > 0}
            />
            <Meter
              icon={FileText}
              label="Knowledge"
              value={
                docCount == null
                  ? "-"
                  : docCount === 0
                    ? "no documents"
                    : `${docCount} document${docCount === 1 ? "" : "s"}`
              }
              on={!!docCount}
            />
            <Meter
              icon={Plug}
              label="Connectors"
              value={connectorServers.size === 0 ? "none" : `${connectorServers.size} connected`}
              on={connectorServers.size > 0}
            />
            <Meter
              icon={ShieldCheck}
              label="Rules"
              value={draft.guardrails.length === 0 ? "none" : `${draft.guardrails.length} active`}
              on={draft.guardrails.length > 0}
            />
          </div>
          <div className="border-t border-border/60 pt-2 text-[11px] leading-relaxed text-muted-foreground">
            {tHC("hcComponentsStudioAgentDesigner.s1")}
          </div>
        </div>
      </aside>
    </div>
  );
}

function Meter({ icon: Icon, label, value, on }: { icon: typeof Brain; label: string; value: string; on?: boolean }) {
  return (
    <div className="flex items-center gap-2">
      <span
        className={`inline-flex h-6 w-6 items-center justify-center rounded-md ${on ? "bg-primary/15 text-primary" : "bg-muted text-muted-foreground"}`}
      >
        <Icon className="h-3.5 w-3.5" />
      </span>
      <span className="text-muted-foreground">{label}</span>
      <span className="ms-auto text-end font-medium">{value}</span>
    </div>
  );
}
