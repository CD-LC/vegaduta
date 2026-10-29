"use client";

// Split out of StudioSurface.tsx (behaviour unchanged).
import { useEffect, useState } from "react";
import { Check, ChevronLeft, ChevronRight, Cpu, Link2, Loader2, Rocket, Search, Zap } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { type StudioAiConfig, type StudioAuthMode, type StudioConnection } from "./store";
import { aliasOf, extractResponsePath, renderConnectionTemplate } from "./payload";
import { api, API_BASE } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";

interface ConnectableItem {
  id: string;
  name: string;
}

const CONNECT_PAGE_SIZE = 4;

export default function ConnectPanel({
  connections,
  busy,
  published,
  authMode,
  ai,
  onAttach,
  onDetach,
  onConfigure,
  onSetAuthMode,
  onSetAi,
  onError,
}: {
  connections: StudioConnection[];
  busy: boolean;
  published: boolean;
  authMode: StudioAuthMode;
  ai: StudioAiConfig | null;
  onAttach: (kind: "agent" | "workflow", id: string, name: string) => Promise<void>;
  onDetach: (kind: "agent" | "workflow", id: string) => void;
  onConfigure: (kind: "agent" | "workflow", id: string, patch: Partial<StudioConnection>) => void;
  onSetAuthMode: (mode: StudioAuthMode) => void;
  onSetAi: (cfg: StudioAiConfig | null) => void;
  onError: (message: string) => void;
}) {
    const tHC = useTranslations("hcBuildStudioStudioConnectPanel");
  const [agents, setAgents] = useState<ConnectableItem[] | null>(null);
  const [workflows, setWorkflows] = useState<ConnectableItem[] | null>(null);
  const [working, setWorking] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [agentPage, setAgentPage] = useState(0);
  const [workflowPage, setWorkflowPage] = useState(0);

  useEffect(() => {
    let on = true;
    // Independent reads fired together - a workflows failure still leaves the
    // agents half usable, matching the allSettled posture on /agents.
    void Promise.allSettled([api.listAgents(), api.listWorkflows()]).then(([a, w]) => {
      if (!on) return;
      if (a.status === "fulfilled")
        setAgents((a.value || []).map((x: any) => ({ id: String(x.id), name: x.name || "Untitled agent" })));
      if (w.status === "fulfilled")
        setWorkflows((w.value || []).map((x: any) => ({ id: String(x.id), name: x.name || "Untitled workflow" })));
      if (a.status === "rejected" && w.status === "rejected") {
        setAgents([]);
        setWorkflows([]);
        onError("Couldn't load your agents and workflows - is the platform reachable?");
      }
    });
    return () => {
      on = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onQuery = (v: string) => {
    setQuery(v);
    setAgentPage(0);
    setWorkflowPage(0);
  };

  const attach = async (kind: "agent" | "workflow", item: ConnectableItem) => {
    setWorking(`${kind}:${item.id}`);
    try {
      await onAttach(kind, item.id, item.name);
    } catch (e) {
      onError(`Couldn't connect ${item.name}: ${e instanceof Error ? e.message : "failed"}`);
    } finally {
      setWorking(null);
    }
  };

  const q = query.trim().toLowerCase();
  const section = (
    title: string,
    icon: typeof Cpu,
    kind: "agent" | "workflow",
    items: ConnectableItem[] | null,
    page: number,
    setPage: (n: number) => void
  ) => {
    const filtered = items?.filter((it) => !q || it.name.toLowerCase().includes(q)) ?? null;
    const total = filtered?.length ?? 0;
    const pages = Math.max(1, Math.ceil(total / CONNECT_PAGE_SIZE));
    const safePage = Math.min(page, pages - 1);
    const shown = filtered?.slice(safePage * CONNECT_PAGE_SIZE, safePage * CONNECT_PAGE_SIZE + CONNECT_PAGE_SIZE) ?? [];
    const Icon = icon;
    return (
      <div>
        <div className="mb-2 flex items-center justify-between gap-2">
          <p className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wide text-foreground">
            <Icon size={13} className="text-primary" />
            {title}
          </p>
          {items !== null && total > 0 && (
            <span className="text-[11px] text-muted-foreground">
              {q
                ? `${total} found`
                : `Showing ${safePage * CONNECT_PAGE_SIZE + 1}–${Math.min(total, safePage * CONNECT_PAGE_SIZE + CONNECT_PAGE_SIZE)} of ${total}`}
            </span>
          )}
        </div>
        {items === null ? (
          <p className="rounded-xl border border-dashed border-border p-3 text-xs text-muted-foreground">{tHC("hcBuildStudioStudioConnectPanel.s25")}</p>
        ) : total === 0 ? (
          <p className="rounded-xl border border-dashed border-border p-3 text-xs text-muted-foreground">
            {q
              ? `No ${kind === "agent" ? "agents" : "workflows"} match “${query.trim()}” - try another search.`
              : `None yet - ${kind === "agent" ? "make one under Agents" : "build one under Workflows"} first.`}
          </p>
        ) : (
          <>
            <div className="flex flex-col gap-2">
              {shown.map((it) => {
                const key = `${kind}:${it.id}`;
                const cxn = connections.find((c) => c.kind === kind && c.id === it.id);
                const on = !!cxn;
                return (
                  <div
                    key={key}
                    className="rounded-xl border border-border bg-card transition hover:border-primary/40 hover:shadow-md"
                  >
                    <div className="flex items-center gap-2.5 px-3 py-2.5">
                      <span
                        className={`grid size-9 shrink-0 place-items-center rounded-lg ${
                          kind === "agent" ? "bg-primary/10 text-primary" : "bg-orange-500/10 text-orange-600"
                        }`}
                      >
                        <Icon size={15} />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                          {kind === "agent" ? "Agent" : "Workflow"}
                        </span>
                        <span className="block truncate text-[13px] font-medium text-foreground">{it.name}</span>
                        {cxn && (
                          <span className="block truncate text-[10px] text-muted-foreground">
                            Connected{cxn.alias ? ` · vd.call("${cxn.alias}")` : ""}
                            {cxn.requiresAuth ? " · needs sign-in" : ""}
                          </span>
                        )}
                      </span>
                      {on ? (
                        <>
                          <button
                            type="button"
                            onClick={() => setEditing(editing === key ? null : key)}
                            className="rounded-full border border-border px-2.5 py-1 text-[11px] font-medium text-foreground transition hover:border-primary/40 hover:bg-accent"
                            title={tHC("hcBuildStudioStudioConnectPanel.s24")}
                          >
                            Configure
                          </button>
                          <button
                            type="button"
                            onClick={() => onDetach(kind, it.id)}
                            className="flex items-center gap-1 rounded-full bg-emerald-500/10 px-2.5 py-1 text-[11px] font-medium text-emerald-700 transition hover:bg-destructive/10 hover:text-destructive dark:text-emerald-300"
                            title={tHC("hcBuildStudioStudioConnectPanel.s23")}
                          >
                            <Check aria-hidden="true" size={11} /> Connected
                          </button>
                        </>
                      ) : (
                        <button
                          type="button"
                          disabled={busy || working === key}
                          onClick={() => void attach(kind, it)}
                          className="flex items-center gap-1 rounded-full bg-gradient-to-r from-primary to-orange-500 px-3 py-1.5 text-[11px] font-semibold text-white shadow-sm transition hover:shadow-md hover:brightness-105 disabled:opacity-50"
                        >
                          {working === key ? (
                            <Loader2 aria-hidden="true" size={11} className="animate-spin" />
                          ) : (
                            <Link2 aria-hidden="true" size={11} />
                          )}{" "}
                          Connect
                        </button>
                      )}
                    </div>
                    {cxn && editing === key && (
                      <div className="border-t border-border">
                        <ConnectionEditor
                          c={cxn}
                          published={published}
                          onConfigure={(id, patch) => onConfigure(kind, id, patch)}
                          onError={onError}
                        />
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            {pages > 1 && (
              <div className="mt-2 flex items-center justify-between">
                <button
                  type="button"
                  disabled={safePage === 0}
                  onClick={() => setPage(safePage - 1)}
                  aria-label={`Previous ${title.toLowerCase()} page`}
                  className="grid size-8 place-items-center rounded-full border border-border text-foreground transition hover:border-primary/40 hover:bg-accent disabled:opacity-40"
                >
                  <ChevronLeft aria-hidden="true" size={14} />
                </button>
                <span className="text-[11px] text-muted-foreground">
                  Page {safePage + 1} of {pages}
                </span>
                <button
                  type="button"
                  disabled={safePage >= pages - 1}
                  onClick={() => setPage(safePage + 1)}
                  aria-label={`Next ${title.toLowerCase()} page`}
                  className="grid size-8 place-items-center rounded-full border border-border text-foreground transition hover:border-primary/40 hover:bg-accent disabled:opacity-40"
                >
                  <ChevronRight aria-hidden="true" size={14} />
                </button>
              </div>
            )}
          </>
        )}
      </div>
    );
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2.5 rounded-xl bg-gradient-to-br from-primary/15 via-orange-500/10 to-transparent p-2.5 ring-1 ring-primary/20">
        <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-gradient-to-br from-primary to-orange-500 text-white shadow-sm">
          <Rocket aria-hidden="true" size={15} />
        </span>
        <p className="text-xs leading-snug text-muted-foreground">
          <span className="font-bold text-foreground">{tHC("hcBuildStudioStudioConnectPanel.s22")}</span> {tHC("hcBuildStudioStudioConnectPanel.s21")}
        </p>
      </div>

      <div>
        <label className="relative block">
          <Search
            aria-hidden="true"
            size={14}
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <input
            value={query}
            onChange={(e) => onQuery(e.target.value)}
            placeholder={tHC("hcBuildStudioStudioConnectPanel.s20")}
            aria-label={tHC("hcBuildStudioStudioConnectPanel.s19")}
            className="w-full rounded-xl border border-border bg-background py-2 ps-9 pe-3 text-sm text-foreground outline-none transition focus:border-primary/50 focus:ring-2 focus:ring-primary/20"
          />
        </label>
        <div className="mt-2 flex gap-2">
          <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-1 text-[11px] font-semibold text-primary">
            <Cpu aria-hidden="true" size={11} /> {agents === null ? "…" : agents.length} agents
          </span>
          <span className="inline-flex items-center gap-1 rounded-full bg-orange-500/10 px-2.5 py-1 text-[11px] font-semibold text-orange-600">
            <Zap aria-hidden="true" size={11} /> {workflows === null ? "…" : workflows.length} workflows
          </span>
        </div>
      </div>

      {section("Agents", Cpu, "agent", agents, agentPage, setAgentPage)}
      {section("Workflows", Zap, "workflow", workflows, workflowPage, setWorkflowPage)}

      <details open={authMode !== "off"} className="group rounded-2xl border border-border bg-card p-3.5">
        <summary className="mb-1 flex cursor-pointer list-none items-center justify-between text-xs font-bold uppercase tracking-wide text-foreground">
          {tHC("hcBuildStudioStudioConnectPanel.s18")}
          <span className="text-[11px] font-normal normal-case text-muted-foreground">
            {authMode === "off" ? "Off" : authMode === "optional" ? "Optional" : "Required"}
          </span>
        </summary>
        <p className="mb-2.5 text-xs leading-relaxed text-muted-foreground">
          {tHC("hcBuildStudioStudioConnectPanel.s17")} <em>your</em> {tHC("hcBuildStudioStudioConnectPanel.s16")}
        </p>
        <div className="flex flex-col gap-1.5">
          {(
            [
              ["off", "No sign-in", "Anyone with the link can use the app."],
              [
                "optional",
                "The app decides",
                "Ask for sign-in where you put it - a screen, a button, or a gated connection.",
              ],
              ["required", "Sign in to open", "The link asks people to sign in before the app loads."],
            ] as [StudioAuthMode, string, string][]
          ).map(([m, label, hint]) => (
            <label
              key={m}
              className={`flex cursor-pointer items-start gap-2.5 rounded-xl border p-2.5 transition ${
                authMode === m
                  ? "border-primary/50 bg-primary/5"
                  : "border-border hover:border-primary/30 hover:bg-accent/50"
              }`}
            >
              <input
                type="radio"
                name="app-auth"
                checked={authMode === m}
                onChange={() => onSetAuthMode(m)}
                className="mt-0.5 accent-primary"
              />
              <span>
                <span className="block text-xs font-medium text-foreground">{label}</span>
                <span className="block text-[11px] text-muted-foreground">{hint}</span>
              </span>
            </label>
          ))}
        </div>
        {authMode !== "off" && !published && (
          <p className="mt-2 rounded-lg bg-amber-500/10 p-2 text-[11px] text-amber-700 dark:text-amber-300">
            {tHC("hcBuildStudioStudioConnectPanel.s15")}
          </p>
        )}
      </details>

      <details open={!!ai?.enabled} className="group rounded-2xl border border-border bg-card p-3.5">
        <summary className="mb-1 flex cursor-pointer list-none items-center justify-between text-xs font-bold uppercase tracking-wide text-foreground">
          {tHC("hcBuildStudioStudioConnectPanel.s14")}
          <span className="text-[11px] font-normal normal-case text-muted-foreground">
            {ai?.enabled ? "On" : "Off"}
          </span>
        </summary>
        <label
          className={`flex cursor-pointer items-start gap-2.5 rounded-xl border p-2.5 transition ${
            ai?.enabled ? "border-primary/50 bg-primary/5" : "border-border hover:border-primary/30 hover:bg-accent/50"
          }`}
        >
          <input
            type="checkbox"
            checked={!!ai?.enabled}
            onChange={(e) => onSetAi(e.target.checked ? { enabled: true, systemPrompt: ai?.systemPrompt } : null)}
            className="mt-0.5 accent-primary"
          />
          <span>
            <span className="block text-xs font-medium text-foreground">{tHC("hcBuildStudioStudioConnectPanel.s13")}</span>
            <span className="block text-[11px] leading-relaxed text-muted-foreground">
              {tHC("hcBuildStudioStudioConnectPanel.s12")} <code className="text-foreground">{tHC("hcBuildStudioStudioConnectPanel.s11")}</code> {tHC("hcBuildStudioStudioConnectPanel.s10")}
            </span>
          </span>
        </label>
        {ai?.enabled && (
          <div className="mt-2">
            <p className="mb-1 text-[11px] text-muted-foreground">
              {tHC("hcBuildStudioStudioConnectPanel.s9")}
            </p>
            <textarea
              value={ai.systemPrompt || ""}
              onChange={(e) => onSetAi({ enabled: true, systemPrompt: e.target.value })}
              rows={2}
              placeholder={tHC("hcBuildStudioStudioConnectPanel.s8")}
              className="w-full resize-none rounded-xl border border-border bg-background p-2.5 text-xs text-foreground outline-none transition focus:border-primary/50 focus:ring-2 focus:ring-primary/20"
            />
          </div>
        )}
      </details>
    </div>
  );
}

/* Per-connection contract editor - what the call sends, what the app shows,
 * who may call it. All optional; a bare connection works untouched. */
function ConnectionEditor({
  c,
  published,
  onConfigure,
  onError,
}: {
  c: StudioConnection;
  published: boolean;
  onConfigure: (id: string, patch: Partial<StudioConnection>) => void;
  onError: (message: string) => void;
}) {
    const tHC = useTranslations("hcBuildStudioStudioConnectPanel");
  const [alias, setAlias] = useState(c.alias || aliasOf(c.name));
  const [template, setTemplate] = useState(c.requestTemplate || "");
  const [responsePath, setResponsePath] = useState(c.responsePath || "");
  const [requiresAuth, setRequiresAuth] = useState(!!c.requiresAuth);
  const [testInput, setTestInput] = useState("");
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);

  const save = () => {
    if (!/^[a-z][a-z0-9_-]{0,39}$/.test(alias)) {
      onError("The call name must start with a letter and use only lowercase letters, digits, - and _.");
      return;
    }
    onConfigure(c.id, {
      alias,
      requestTemplate: template || undefined,
      responsePath: responsePath || undefined,
      requiresAuth,
    });
  };

  // Test goes through the live public SHARE endpoint (always up while the
  // connection exists) - the published alias call rides the same agent/workflow.
  const test = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const rendered = renderConnectionTemplate(template, testInput, {}, null);
      const wf = c.kind === "workflow";
      const res = await fetch(
        `${API_BASE}/api/share/${wf ? "workflows" : "agents"}/${encodeURIComponent(c.shareId)}/${wf ? "run" : "chat"}`,
        { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ message: rendered }) }
      );
      const j = await res.json().catch((): null => null);
      const raw =
        typeof j?.reply === "string" && j.reply
          ? j.reply
          : typeof j?.resultText === "string" && j.resultText
            ? j.resultText
            : typeof j?.errorMessage === "string"
              ? j.errorMessage
              : `HTTP ${res.status}`;
      setTestResult(extractResponsePath(responsePath, wf ? "resultText" : "reply", raw));
    } catch {
      setTestResult("Couldn't reach the API.");
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="border-t border-border/70 px-2.5 py-2">
      <div className="flex flex-col gap-2">
        <div>
          <p className="mb-1 text-[11px] text-muted-foreground">{tHC("hcBuildStudioStudioConnectPanel.s7")}</p>
          <Input value={alias} onChange={(e) => setAlias(e.target.value)} className="h-7 font-mono text-[11px]" />
          <p className="mt-1 text-[10px] text-muted-foreground">
            {tHC("hcBuildStudioStudioConnectPanel.s6")} {alias || "it"} {tHC("hcBuildStudioStudioConnectPanel.s5")}
          </p>
        </div>
        <div>
          <p className="mb-1 text-[11px] text-muted-foreground">
            {tHC("hcBuildStudioStudioConnectPanel.s4")}
          </p>
          <textarea
            value={template}
            onChange={(e) => setTemplate(e.target.value)}
            rows={2}
            placeholder={'e.g. "Summarize this for {{user.name}}: {{input}}"'}
            className="w-full resize-none rounded-lg border border-border bg-card p-2 font-mono text-[11px] text-foreground outline-none focus:border-primary/50"
          />
          <p className="mt-1 text-[10px] leading-relaxed text-muted-foreground">
            Placeholders: <code className="text-foreground">{"{{input}}"}</code>,{" "}
            <code className="text-foreground">{"{{user.name}}"}</code>,{" "}
            <code className="text-foreground">{"{{user.email}}"}</code>,{" "}
            <code className="text-foreground">{"{{data.<field>}}"}</code>
          </p>
        </div>
        <div>
          <p className="mb-1 text-[11px] text-muted-foreground">{tHC("hcBuildStudioStudioConnectPanel.s3")}</p>
          <Input
            value={responsePath}
            onChange={(e) => setResponsePath(e.target.value)}
            placeholder={c.kind === "workflow" ? "resultText" : "reply"}
            className="h-7 font-mono text-[11px]"
          />
        </div>
        <label className="flex cursor-pointer items-center gap-2 text-xs text-foreground">
          <input type="checkbox" checked={requiresAuth} onChange={(e) => setRequiresAuth(e.target.checked)} />
          {tHC("hcBuildStudioStudioConnectPanel.s2")}
        </label>
        {!published && requiresAuth && (
          <p className="rounded-md bg-amber-500/10 p-2 text-[11px] text-amber-700 dark:text-amber-300">
            {tHC("hcBuildStudioStudioConnectPanel.s1")}
          </p>
        )}
        <div className="flex items-center gap-1.5">
          <Input
            value={testInput}
            onChange={(e) => setTestInput(e.target.value)}
            placeholder="Try it…"
            className="h-7 flex-1 text-xs"
          />
          <Button
            size="sm"
            variant="outline"
            onClick={() => void test()}
            disabled={testing}
            className="h-7 gap-1 text-[11px]"
          >
            {testing ? <Loader2 aria-hidden="true" size={11} className="animate-spin" /> : null} Test
          </Button>
          <Button size="sm" onClick={save} className="h-7 text-[11px]">
            Save
          </Button>
        </div>
        {testResult !== null && (
          <p className="rounded-md bg-accent/60 p-2 text-[11px] whitespace-pre-wrap text-foreground">{testResult}</p>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
