"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { RefreshCw, KeyRound, Trash2, Plug, ChevronRight } from "lucide-react";
import { api, type WebSearchProvidersStatus } from "../lib/api";
import AdminShell from "../components/AdminShell";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Badge } from "../components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { isPersonalEdition } from "../../lib/edition";
import { useCloudSpeechOffered } from "../../lib/cloudSpeech";
import { useTranslations } from "../../lib/i18n";

// Platform-admin-settable credentials for tool-capability providers that were
// previously BYOK-only with zero admin path - 2026-08-03, "even I should be
// able to add in platform admin" direct feedback. One card per tool
// (PlatformToolCredentialController's fixed toolKey set), each an operator
// key + an optional tool-specific "extra config" field (ElevenLabs: a
// platform-owned voice_id already cloned in the ElevenLabs dashboard; E2B: a
// platform-owned code-execution template id). Configuring ElevenLabs here
// makes the `speak` tool "just work" for every tenant with no BYOK voice of
// their own, quota-capped per tenant (ElevenLabsGenerationQuotaService) so it
// can never become an unbounded bill - see AgentToolProvider#resolveAudioTools.
// E2B's credential can be stored here too, but the platform-fallback RUNTIME
// path (actually using it for a tenant's run_code calls) is not wired yet -
// E2B bills by sandbox-seconds, not per-call, and a count-based cap copied
// from the audio pattern would not accurately bound spend, so that wiring is
// intentionally left as a follow-up rather than shipped with a wrong model.

const TOOLS = [
  {
    key: "ELEVENLABS",
    label: "ElevenLabs Voice",
    description:
      "Lets every tenant's agent speak in a platform-owned voice via the speak tool, even without their own ElevenLabs key. Quota-capped per tenant. The voice must already be cloned in the ElevenLabs dashboard - paste its voice_id below.",
    extraLabel: "Platform voice_id",
    extraPlaceholder: "e.g. 21m00Tcm4TlvDq8ikWAM",
    wired: true,
  },
  {
    key: "E2B",
    label: "E2B Code Execution",
    description:
      "Stores a platform-wide E2B key + code-execution template id. Runtime fallback for tenants without their own E2B key is not wired yet (E2B bills by sandbox-seconds, not per-call - an accurate quota model is still needed before this goes live for tenant traffic).",
    extraLabel: "Platform code-execution template id",
    extraPlaceholder: "e.g. base",
    wired: false,
  },
  {
    key: "BRAVE_SEARCH",
    label: "Brave Web Search",
    description:
      "Powers the web_search built-in tool, which every tenant's agent gets for free - it is how an agent DISCOVERS a page rather than only reading a URL it was handed. Until a key is saved here, web_search is not offered to any agent at all (a tool that can only answer \"not configured\" would waste prompt context on every turn). Brave applies a monthly free credit automatically, so low-volume use is generally free; check current rates on Brave's own pricing page rather than trusting a number written here. No per-tenant quota is enforced yet - watch usage in the Brave dashboard.",
    wired: true,
  },
  {
    key: "TAVILY_SEARCH",
    label: "Tavily Web Search",
    description:
      "Optional alternative backend for web_search. It is used only when the operator selects it as the primary provider or in the fallback list (WEB_SEARCH_PROVIDER / WEB_SEARCH_FALLBACK); saving a key alone changes nothing. Check Tavily's own pricing page for current free allowance and rates.",
    wired: true,
  },
  {
    key: "EXA_SEARCH",
    label: "Exa Web Search",
    description:
      "Optional alternative backend for web_search. Used only when the operator selects it as the primary provider or in the fallback list (WEB_SEARCH_PROVIDER / WEB_SEARCH_FALLBACK); saving a key alone changes nothing. Check Exa's own pricing page for current rates.",
    wired: true,
  },
  {
    key: "SERPER_SEARCH",
    label: "Serper Web Search",
    description:
      "Optional alternative backend for web_search (Google results via the Serper API). Used only when the operator selects it as the primary provider or in the fallback list (WEB_SEARCH_PROVIDER / WEB_SEARCH_FALLBACK); saving a key alone changes nothing. Check Serper's own pricing page for current rates.",
    wired: true,
  },
  {
    key: "BROWSER_TASK",
    label: "Managed Browser (web_task)",
    description:
      "Powers the web_task built-in tool - managed browser automation (clicking, forms, logins, JS-heavy sites) for every tenant's agent, with no per-tenant setup. Point the base URL at a Steel/Browserbase-style provider or a self-hosted Steel-compatible gateway (it must accept POST /v1/tasks with {task, startUrl, maxSteps, timeoutSeconds}). The provider URL is platform-admin config, not tenant input, so an in-cluster address like http://steel:3000 is fine. Until both the key and base URL are saved, web_task is not offered to any agent.",
    extraLabel: "Provider base URL",
    extraPlaceholder: "e.g. https://api.steel.dev or http://steel:3000",
    wired: true,
  },
  {
    key: "TYPESAFE",
    label: "TypeSafe Jev (decisions)",
    description:
      "Powers Jev - TypeSafe's System One decision model - the platform's cheap-decision layer. With a key saved, two paths upgrade from keyword heuristics to calibrated model decisions: cost routing (borderline-simple turns get a second opinion before routing to an agent's economy model) and Quick Task agent selection (semantic fit instead of token overlap, including multi-agent picks in a single call). Jev is a decision model, not a chat model - it never generates text and never touches tenant BYOK providers. Until a key is saved, or if Jev is unreachable, every path silently keeps its existing heuristic behavior - nothing breaks. No per-tenant quota yet; Jev bills ~$0.042/1M input tokens with no output charge, so spend is bounded and tiny.",
    wired: true,
  },
];

export default function ToolCredentialsPage() {
  const tHC = useTranslations("hcPlatformadminToolcredentialsPage");
  const askConfirm = useConfirm();
  // The ElevenLabs voice credential is a CLOUD_SPEECH feature: no card where core has it off.
  const speech = useCloudSpeechOffered();
  const [statuses, setStatuses] = useState<Record<string, any>>({});
  const [loading, setLoading] = useState(true);
  const [formState, setFormState] = useState<Record<string, { apiKey?: string; extraConfig?: string }>>({});
  const [busyKey, setBusyKey] = useState(null);
  const [searchStatus, setSearchStatus] = useState<WebSearchProvidersStatus | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.listPlatformToolCredentials();
      const byKey: Record<string, any> = {};
      for (const row of list) byKey[row.toolKey] = row;
      setStatuses(byKey);
      // Provider picture is informational: a failure here must not hide the credential cards.
      try {
        setSearchStatus(await api.getWebSearchProviders());
      } catch {
        setSearchStatus(null);
      }
    } catch (err) {
      toast.error((err as Error).message || "Could not load tool credentials.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Failed to load tool credentials", e));
  }, [refresh]);

  function setField(toolKey: string, field: string, value: string) {
    setFormState((prev) => ({ ...prev, [toolKey]: { ...prev[toolKey], [field]: value } }));
  }

  async function handleActivate(toolKey: string) {
    const form = formState[toolKey] || {};
    if (!form.apiKey?.trim()) {
      toast.error("An API key is required.");
      return;
    }
    setBusyKey(toolKey);
    try {
      await api.activatePlatformToolCredential(toolKey, form.apiKey.trim(), form.extraConfig?.trim() || null);
      toast.success(`${toolKey} platform credential saved.`);
      setFormState((prev) => ({ ...prev, [toolKey]: { apiKey: "", extraConfig: "" } }));
      await refresh();
    } catch (err) {
      toast.error((err as Error).message || "Could not save this credential.");
    } finally {
      setBusyKey(null);
    }
  }

  async function handleDeactivate(toolKey: string) {
    if (!(await askConfirm(`Remove the stored platform credential for "${toolKey}"? This cannot be undone.`))) return;
    setBusyKey(toolKey);
    try {
      await api.deactivatePlatformToolCredential(toolKey);
      toast.success(`${toolKey} platform credential removed.`);
      await refresh();
    } catch (err) {
      toast.error((err as Error).message || "Could not remove this credential.");
    } finally {
      setBusyKey(null);
    }
  }

  return (
    <AdminShell
      title={tHC("s12")}
      actions={
        <Button variant="outline" size="sm" onClick={refresh} disabled={loading} title="Refresh">
          <RefreshCw size={14} className={loading ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <p className="mb-6 text-sm text-muted-foreground">{tHC("s11")}</p>

      {/* /platform-admin/mcp-catalog is outside the personal allowlist (a real 404 there): no card to it. */}
      {!isPersonalEdition() && (
        <Card className="mb-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <Plug size={16} className="text-primary" /> {tHC("s10")}
            </CardTitle>
            <p className="text-xs text-muted-foreground">{tHC("s9")}</p>
          </CardHeader>
          <CardContent>
            <Link
              href="/platform-admin/mcp-catalog"
              className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
            >
              {tHC("s8")} <ChevronRight size={13} />
            </Link>
          </CardContent>
        </Card>
      )}

      {searchStatus && (
        <Card className="mb-6" data-testid="web-search-providers">
          <CardHeader>
            <CardTitle className="text-sm">{tHC("s7")}</CardTitle>
            <p className="text-xs text-muted-foreground">
              {searchStatus.multiProviderActive
                ? `Primary: ${searchStatus.primary}${
                    searchStatus.fallback?.length ? `, fallback order: ${searchStatus.fallback.join(" > ")}` : ""
                  }. ${
                    searchStatus.activeNow
                      ? `Answering searches now: ${searchStatus.activeNow}.`
                      : "No provider in the chain is configured, so web_search is not offered."
                  }`
                : "Default mode: Brave only, exactly as before. Alternative providers are used only when the operator sets WEB_SEARCH_PROVIDER or WEB_SEARCH_FALLBACK."}{" "}
              {tHC("s6")}
            </p>
          </CardHeader>
          <CardContent>
            <ul className="space-y-1.5 text-sm">
              {(searchStatus.providers || []).map((p) => (
                <li key={p.id} className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{p.label}</span>
                  <Badge variant={p.configured ? "outline" : "secondary"}>
                    {p.configured ? "Configured" : "Not configured"}
                  </Badge>
                  {p.role === "primary" && <Badge>Primary</Badge>}
                  {p.role === "fallback" && (
                    <Badge variant="secondary">
                      {tHC("s5")}
                      {p.fallbackPosition}
                    </Badge>
                  )}
                  {p.active && <Badge>{tHC("s4")}</Badge>}
                  {p.dailyCap > 0 && (
                    <span className="text-xs text-muted-foreground">
                      {p.usedToday}/{p.dailyCap} {tHC("s3")}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {TOOLS.filter((tool) => speech || tool.key !== "ELEVENLABS").map((tool) => {
          const status = statuses[tool.key];
          const configured = !!status?.configured;
          const form = formState[tool.key] || {};
          const busy = busyKey === tool.key;
          return (
            <Card key={tool.key} className="gap-4 p-5">
              <CardHeader className="p-0">
                <div className="flex items-center justify-between gap-2">
                  <CardTitle className="text-base">{tool.label}</CardTitle>
                  <Badge variant={configured ? "outline" : "secondary"}>
                    {configured ? "Configured" : "Not configured"}
                  </Badge>
                </div>
              </CardHeader>
              <CardContent className="space-y-3 p-0">
                <p className="text-sm text-muted-foreground">{tool.description}</p>
                {!tool.wired && <p className="text-xs text-[var(--warning-text)]">{tHC("s2")}</p>}
                {configured && status.extraConfig && (
                  <p className="text-xs text-muted-foreground">
                    {tool.extraLabel}: <span className="font-mono">{status.extraConfig}</span>
                  </p>
                )}
                {configured && status.updatedAt && (
                  <p className="text-xs text-muted-foreground">
                    {tHC("s1")} {new Date(status.updatedAt).toLocaleString()}
                    {status.updatedBy ? ` by ${status.updatedBy}` : ""}
                  </p>
                )}
                <div className="space-y-1.5">
                  <Label htmlFor={`${tool.key}-key`}>API key</Label>
                  <Input
                    id={`${tool.key}-key`}
                    type="password"
                    placeholder={configured ? "Enter a new key to replace it" : "Paste API key"}
                    value={form.apiKey || ""}
                    onChange={(e) => setField(tool.key, "apiKey", e.target.value)}
                  />
                </div>
                {/* Only tools that actually HAVE a second config value render this.
                    Brave Search needs an API key and nothing else, and without
                    the guard its card showed an empty-labelled text box inviting
                    an admin to fill in a field that does not exist. */}
                {tool.extraLabel && (
                  <div className="space-y-1.5">
                    <Label htmlFor={`${tool.key}-extra`}>{tool.extraLabel}</Label>
                    <Input
                      id={`${tool.key}-extra`}
                      type="text"
                      placeholder={tool.extraPlaceholder}
                      value={form.extraConfig || ""}
                      onChange={(e) => setField(tool.key, "extraConfig", e.target.value)}
                    />
                  </div>
                )}
                <div className="flex flex-wrap items-center gap-2 pt-1">
                  <Button size="sm" disabled={busy} onClick={() => handleActivate(tool.key)}>
                    <KeyRound size={14} /> {busy ? "Saving..." : configured ? "Update" : "Activate"}
                  </Button>
                  {configured && (
                    <Button size="sm" variant="outline" disabled={busy} onClick={() => handleDeactivate(tool.key)}>
                      <Trash2 size={14} /> Remove
                    </Button>
                  )}
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>
    </AdminShell>
  );
}
