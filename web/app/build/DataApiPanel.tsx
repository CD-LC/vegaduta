"use client";

// Data API panel (Backend tab): turns on HTTPS access to the project's own
// database schema, shows the base URL + keys, and lets the owner decide which
// tables the public "anon" key may touch. The database URL above only works
// inside Azure; a deployed app (GitHub Pages, a phone, Vercel) uses this.
//
// Keys: the anon key is safe in client code (it can only do what the table
// policies allow). The service key is for servers only and is shown exactly
// once - the API itself refuses it from a browser. See app-builder/app/dataapi.py.

import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, Copy, Globe, KeyRound, Loader2, ShieldCheck } from "lucide-react";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Badge } from "../components/ui/badge";
import { appBuilder, type AppBuilderDataConfig, type AppBuilderDataTable, type DataOp } from "./lib";
import { DATA_OPS, fetchSnippet, isDirty, maskKey, parseOrigins, policiesPayload, riskNotes } from "./dataApi";
import { useTranslations } from "../lib/i18n";

interface Props {
  project: { project_id: string };
  onError: (message: string) => void;
  /** Changes when the project's tables change (schema applied) so the list reloads. */
  refreshToken?: string;
}

const OP_LABEL: Record<DataOp, string> = { select: "Read", insert: "Create", update: "Edit", delete: "Delete" };

// Declared at module level (not inside DataApiPanel): a component defined in the
// render body gets a new identity every render, so React remounted every row on
// each keystroke/copy and dropped focus.
function Copyable({
  label,
  value,
  shown,
  copied,
  onCopy,
}: {
  label: string;
  value: string;
  shown?: string;
  copied: string | null;
  onCopy: (what: string, text: string) => void;
}) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-20 shrink-0 text-[11px] text-muted-foreground">{label}</span>
      <code className="block min-w-0 flex-1 truncate rounded bg-muted/50 px-2 py-1 font-mono text-[11px]">{shown ?? value}</code>
      <Button variant="ghost" size="sm" onClick={() => onCopy(label, value)} aria-label={`Copy ${label}`}>
        {copied === label ? <Check aria-hidden="true" size={13} /> : <Copy aria-hidden="true" size={13} />}
      </Button>
    </div>
  );
}

export default function DataApiPanel({ project, onError, refreshToken }: Props) {
    const tHC = useTranslations("hcBuildDataApiPanel");
  const id = project.project_id;
  const [cfg, setCfg] = useState<AppBuilderDataConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [tables, setTables] = useState<AppBuilderDataTable[]>([]);
  const [originsRaw, setOriginsRaw] = useState("");
  const [serviceKey, setServiceKey] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<null | "rotate" | "disable">(null);

  const apply = useCallback((c: AppBuilderDataConfig) => {
    setCfg(c);
    setTables(c.tables ?? []);
    setOriginsRaw((c.allowed_origins ?? []).join(", "));
    if (c.service_key) setServiceKey(c.service_key);
  }, []);

  const load = useCallback(() => {
    setLoading(true);
    appBuilder
      .dataConfig(id)
      .then(apply)
      .catch((e: Error) => onError(e.message))
      .finally(() => setLoading(false));
  }, [id, apply, onError]);

  useEffect(() => {
    setConfirm(null);
    load();
  }, [load, refreshToken]);

  // A different project must never inherit the previous one's one-time key.
  useEffect(() => {
    setServiceKey(null);
  }, [id]);

  const run = async (fn: () => Promise<AppBuilderDataConfig | { enabled: false }>) => {
    setBusy(true);
    try {
      const r = await fn();
      if (r.enabled) apply(r as AppBuilderDataConfig);
      else {
        setCfg({ enabled: false, available: true });
        setTables([]);
        setServiceKey(null);
      }
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  const copy = async (what: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(what);
      setTimeout(() => setCopied((c) => (c === what ? null : c)), 1500);
    } catch {
      onError("Couldn't copy to the clipboard - select the text manually.");
    }
  };

  const toggle = (name: string, op: DataOp) =>
    setTables((cur) => cur.map((t) => (t.name === name ? { ...t, policy: { ...t.policy, [op]: !t.policy[op] } } : t)));

  const parsed = useMemo(() => parseOrigins(originsRaw), [originsRaw]);
  const dirty = cfg?.enabled ? isDirty(cfg.tables ?? [], tables, cfg.allowed_origins ?? [], originsRaw) : false;

  const save = () => {
    if (parsed.invalid.length) {
      onError(`Not a valid origin (use https://your-app.com): ${parsed.invalid[0]}`);
      return;
    }
    void run(() => appBuilder.dataSetPolicies(id, { policies: policiesPayload(tables), allowed_origins: parsed.origins }));
  };

  if (loading && !cfg) {
    return (
      <div className="mt-4 flex items-center gap-2 text-xs text-muted-foreground">
        <Loader2 aria-hidden="true" size={13} className="animate-spin" /> {tHC("hcBuildDataApiPanel.s30")}
      </div>
    );
  }

  return (
    <div className="mt-5 space-y-3 border-t border-border pt-4" data-testid="data-api-panel">
      <div className="flex items-center gap-2">
        <Globe aria-hidden="true" size={15} className="text-primary" />
        <h3 className="text-sm font-medium text-foreground">{tHC("hcBuildDataApiPanel.s29")}</h3>
        {cfg?.enabled && <Badge variant="secondary">on</Badge>}
      </div>

      {cfg?.available === false && (
        <p className="text-xs text-muted-foreground">{tHC("hcBuildDataApiPanel.s28")}</p>
      )}

      {cfg && !cfg.enabled && cfg.available !== false && (
        <>
          <p className="text-xs leading-relaxed text-muted-foreground">
            {tHC("hcBuildDataApiPanel.s27")} <em>{tHC("hcBuildDataApiPanel.s26")}</em>{tHC("hcBuildDataApiPanel.s25")}
          </p>
          <Button size="sm" disabled={busy} onClick={() => run(() => appBuilder.dataEnable(id))}>
            {busy ? <Loader2 aria-hidden="true" size={14} className="animate-spin" /> : <ShieldCheck aria-hidden="true" size={14} />} {tHC("hcBuildDataApiPanel.s24")}
          </Button>
        </>
      )}

      {cfg?.enabled && (
        <>
          <div className="space-y-1.5">
            <Copyable label={tHC("hcBuildDataApiPanel.s23")} value={cfg.base_url ?? ""} copied={copied} onCopy={copy} />
            <Copyable label={tHC("hcBuildDataApiPanel.s22")} value={cfg.anon_key ?? ""} shown={maskKey(cfg.anon_key ?? "")} copied={copied} onCopy={copy} />
          </div>
          <p className="text-[11px] text-muted-foreground">
            {tHC("hcBuildDataApiPanel.s21")}
          </p>

          {serviceKey && (
            <div role="alert" className="rounded-lg border border-amber-500/50 bg-amber-500/10 p-3">
              <div className="mb-1 flex items-center gap-1.5 text-xs font-medium text-foreground">
                <KeyRound aria-hidden="true" size={13} /> {tHC("hcBuildDataApiPanel.s20")}
              </div>
              <Copyable label={tHC("hcBuildDataApiPanel.s19")} value={serviceKey} shown={maskKey(serviceKey)} copied={copied} onCopy={copy} />
              <p className="mt-1 text-[11px] text-muted-foreground">
                {tHC("hcBuildDataApiPanel.s18")}
              </p>
              <Button variant="ghost" size="sm" className="mt-1" onClick={() => setServiceKey(null)}>
                {tHC("hcBuildDataApiPanel.s17")}
              </Button>
            </div>
          )}

          <div className="space-y-1.5">
            <Label className="text-xs">{tHC("hcBuildDataApiPanel.s16")}</Label>
            {cfg.tables_error && <p className="text-xs text-destructive">{tHC("hcBuildDataApiPanel.s15")} {cfg.tables_error}</p>}
            {tables.length === 0 && !cfg.tables_error && (
              <p className="text-xs text-muted-foreground">{tHC("hcBuildDataApiPanel.s14")}</p>
            )}
            {tables.map((t) => {
              const notes = riskNotes(t);
              return (
                <div key={t.name} className="rounded-md border border-border p-2">
                  <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                    <span className="min-w-[6rem] font-mono text-xs font-medium text-foreground">{t.name}</span>
                    {DATA_OPS.map((op) => (
                      <label key={op} className="flex cursor-pointer items-center gap-1.5 text-xs text-foreground">
                        <input
                          type="checkbox"
                          className="size-3.5 accent-primary"
                          checked={!!t.policy[op]}
                          onChange={() => toggle(t.name, op)}
                          aria-label={`${OP_LABEL[op]} rows in ${t.name}`}
                        />
                        {OP_LABEL[op]}
                      </label>
                    ))}
                  </div>
                  {notes.map((n) => (
                    <p key={n} className="mt-1 flex items-center gap-1 text-[11px] text-amber-600 dark:text-amber-400">
                      <AlertTriangle aria-hidden="true" size={11} /> {n}
                    </p>
                  ))}
                </div>
              );
            })}
          </div>

          <div className="space-y-1">
            <Label htmlFor="data-origins" className="text-xs">
              {tHC("hcBuildDataApiPanel.s13")}
            </Label>
            <Input
              id="data-origins"
              className="font-mono text-xs"
              placeholder="https://your-app.com, http://localhost:3000"
              value={originsRaw}
              onChange={(e) => setOriginsRaw(e.target.value)}
            />
            <p className="text-[11px] text-muted-foreground">
              {tHC("hcBuildDataApiPanel.s12")}
              {parsed.invalid.length > 0 && <span className="text-destructive"> {tHC("hcBuildDataApiPanel.s11")} {parsed.invalid[0]}</span>}
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" disabled={busy || !dirty} onClick={save}>
              {busy ? <Loader2 aria-hidden="true" size={14} className="animate-spin" /> : <Check aria-hidden="true" size={14} />} {tHC("hcBuildDataApiPanel.s10")}
            </Button>
            {dirty && <span className="text-[11px] text-muted-foreground">{tHC("hcBuildDataApiPanel.s9")}</span>}
          </div>

          <details className="rounded-md border border-border p-2">
            <summary className="cursor-pointer text-xs font-medium text-foreground">{tHC("hcBuildDataApiPanel.s8")}</summary>
            <pre className="mt-2 max-h-56 overflow-auto rounded bg-muted/50 p-2 font-mono text-[11px] leading-snug">
              {fetchSnippet(cfg.base_url ?? "", cfg.anon_key ?? "", tables)}
            </pre>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => copy("snippet", fetchSnippet(cfg.base_url ?? "", cfg.anon_key ?? "", tables))}
            >
              {copied === "snippet" ? <Check aria-hidden="true" size={13} /> : <Copy aria-hidden="true" size={13} />} {tHC("hcBuildDataApiPanel.s7")}
            </Button>
          </details>

          <div className="flex flex-wrap items-center gap-2 border-t border-border pt-2">
            {confirm === "rotate" ? (
              <>
                <span className="text-[11px] text-muted-foreground">{tHC("hcBuildDataApiPanel.s6")}</span>
                <Button size="sm" variant="outline" disabled={busy} onClick={() => run(() => appBuilder.dataRotateKey(id, "service"))}>
                  {tHC("hcBuildDataApiPanel.s5")}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirm(null)}>
                  Cancel
                </Button>
              </>
            ) : confirm === "disable" ? (
              <>
                <span className="text-[11px] text-muted-foreground">{tHC("hcBuildDataApiPanel.s4")}</span>
                <Button
                  size="sm"
                  variant="outline"
                  className="text-destructive"
                  disabled={busy}
                  onClick={() => run(() => appBuilder.dataDisable(id))}
                >
                  {tHC("hcBuildDataApiPanel.s3")}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirm(null)}>
                  Cancel
                </Button>
              </>
            ) : (
              <>
                <Button size="sm" variant="ghost" onClick={() => setConfirm("rotate")}>
                  <KeyRound aria-hidden="true" size={13} /> {tHC("hcBuildDataApiPanel.s2")}
                </Button>
                <Button size="sm" variant="ghost" className="text-destructive" onClick={() => setConfirm("disable")}>
                  {tHC("hcBuildDataApiPanel.s1")}
                </Button>
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}
