// @ts-nocheck
"use client";

// Split out of page.tsx (behaviour unchanged).
import { useCallback, useEffect, useState } from "react";
import { Loader2, Play, Copy, Check, CheckCircle2, Database } from "lucide-react";
import { Button } from "../components/ui/button";
import { Textarea } from "../components/ui/textarea";
import { Label } from "../components/ui/label";
import { Badge } from "../components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import DataApiPanel from "./DataApiPanel";
import { useTranslations } from "../lib/i18n";
import { appBuilder } from "./lib";

function maskDbUrl(url) {
  if (!url) return "";
  // postgres://user:pass@host:port/db -> postgres://user:***@host:port/db
  return url.replace(/:\/\/([^:/@]+):([^@/]+)@/, "://$1:***@");
}

/* ------------------------------------------------------------------ */
/* Backend panel                                                      */

export default function BackendPanel({ project, setProject, onError, fileSet }) {
    const tHC = useTranslations("hcBuildBackendPanel");
  const t = useTranslations("appBuilder");
  const [busy, setBusy] = useState(false);
  const [dbUrl, setDbUrl] = useState(null);
  const [unavailable, setUnavailable] = useState(false);
  const [reason, setReason] = useState(null);
  const [sql, setSql] = useState("");
  const [applying, setApplying] = useState(false);
  const [applyError, setApplyError] = useState(null);
  const [tables, setTables] = useState([]);
  const [copied, setCopied] = useState(false);
  const schemaFile = (fileSet || []).find((f) => /^(db\/)?schema\.sql$/.test(f.path));

  const loadTables = useCallback(() => {
    appBuilder
      .backendTables(project.project_id)
      .then((r) => setTables(r?.tables || []))
      .catch(() => setTables([]));
  }, [project.project_id]);

  // A project that was provisioned earlier already carries its role-scoped
  // URL; surface it (and its tables) without making the user click again.
  useEffect(() => {
    setDbUrl(project?.backend?.database_url || null);
    setSql("");
    setApplyError(null);
    setTables([]);
    if (project?.backend?.role) loadTables();
  }, [project.project_id, project?.backend?.database_url, project?.backend?.role, loadTables]);

  const applySchema = async () => {
    setApplying(true);
    setApplyError(null);
    try {
      const r = await appBuilder.applyBackendSchema(project.project_id, sql);
      if (r?.applied) setTables(r.tables || []);
      else setApplyError(r?.reason || "The schema could not be applied.");
    } catch (e) {
      setApplyError(e.message);
    } finally {
      setApplying(false);
    }
  };

  const copyUrl = async () => {
    try {
      await navigator.clipboard.writeText(dbUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      onError("Couldn't copy to the clipboard - select the URL manually.");
    }
  };

  const provision = async () => {
    setBusy(true);
    setUnavailable(false);
    setReason(null);
    try {
      const r = await appBuilder.provisionBackend(project.project_id);
      setDbUrl(r?.database_url || null);
      // Keep the result on the project itself so it survives switching tabs
      // (BackendPanel unmounts) instead of reverting to "not provisioned".
      if (r?.provisioned !== false && r?.database_url) {
        setProject?.((cur) => ({
          ...cur,
          backend: { ...cur?.backend, role: r.role, schema: r.schema, database_url: r.database_url },
        }));
      }
      // provisioned:false comes back as a 200 with a human reason (backend DB
      // not configured, unsafe project id, driver missing) - show it verbatim.
      if (r && r.provisioned === false) {
        setUnavailable(true);
        setReason(r.reason || null);
      } else {
        loadTables();
      }
    } catch (e) {
      // Show what actually went wrong (an unconfigured backend DB, an archived
      // project's 409, a timeout) rather than one generic line for everything.
      setUnavailable(true);
      setDbUrl(null);
      setReason(e?.message || null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="h-[min(560px,85dvh)] overflow-auto">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm">
          <Database aria-hidden="true" size={16} className="text-primary" /> {t("backendHeading")}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="mb-4 text-sm leading-relaxed text-muted-foreground">{t("backendExplainerText")}</p>
        <Button size="sm" disabled={busy} onClick={provision}>
          {busy ? <Loader2 aria-hidden="true" size={15} className="animate-spin" /> : <Database aria-hidden="true" size={15} />} {t("provisionButton")}
        </Button>

        {dbUrl && (
          <div className="mt-4 rounded-lg border border-[color-mix(in_srgb,var(--state-success)_40%,transparent)] bg-[color-mix(in_srgb,var(--state-success)_5%,transparent)] p-3">
            <div className="mb-1 flex items-center gap-1.5 text-sm font-medium text-foreground">
              <CheckCircle2 aria-hidden="true" size={14} className="text-[var(--state-success)]" /> {t("backendProvisionedText")}
            </div>
            <div className="flex items-center gap-2">
              <code className="block min-w-0 flex-1 truncate font-mono text-xs text-muted-foreground">{maskDbUrl(dbUrl)}</code>
              <Button variant="ghost" size="sm" onClick={copyUrl} aria-label={tHC("hcBuildBackendPanel.s5")}>
                {copied ? <Check aria-hidden="true" size={13} /> : <Copy aria-hidden="true" size={13} />}
              </Button>
            </div>
            <p className="mt-1 text-[11px] text-muted-foreground">
              {tHC("hcBuildBackendPanel.s4")}
            </p>
          </div>
        )}
        {unavailable && (
          <div className="mt-4 rounded-lg border border-border bg-muted/40 p-3 text-sm text-muted-foreground">
            {reason || t("backendUnavailableText")}
          </div>
        )}

        {dbUrl && (
          <div className="mt-4 space-y-2">
            <div className="flex items-center justify-between gap-2">
              <Label htmlFor="backend-sql" className="text-xs">{tHC("hcBuildBackendPanel.s3")}</Label>
              {schemaFile && (
                <Button variant="ghost" size="sm" onClick={() => setSql(schemaFile.content)}>
                  Load {schemaFile.path}
                </Button>
              )}
            </div>
            <Textarea
              id="backend-sql"
              className="min-h-[96px] font-mono text-xs"
              placeholder={"create table notes (\n  id bigint generated always as identity primary key,\n  body text not null\n);\nalter table notes enable row level security;"}
              value={sql}
              onChange={(e) => setSql(e.target.value)}
            />
            <div className="flex items-center gap-2">
              <Button size="sm" disabled={applying || !sql.trim()} onClick={applySchema}>
                {applying ? <Loader2 aria-hidden="true" size={14} className="animate-spin" /> : <Play aria-hidden="true" size={14} />} {tHC("hcBuildBackendPanel.s2")}
              </Button>
              <span className="text-[11px] text-muted-foreground">{tHC("hcBuildBackendPanel.s1")}</span>
            </div>
            {applyError && (
              <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/5 p-2 font-mono text-xs text-destructive">
                {applyError}
              </p>
            )}
            {tables.length > 0 && (
              <ul className="space-y-1.5 pt-1">
                {tables.map((tb) => (
                  <li key={tb.name} className="rounded-md border border-border p-2">
                    <div className="flex items-center gap-2 text-xs font-medium text-foreground">
                      {tb.name}
                      <Badge variant={tb.rls ? "secondary" : "outline"}>{tb.rls ? "RLS on" : "RLS off"}</Badge>
                    </div>
                    <p className="font-mono text-[11px] text-muted-foreground">
                      {tb.columns.map((c) => `${c.name} ${c.type}${c.nullable ? "" : " !"}`).join(", ")}
                    </p>
                  </li>
                ))}
              </ul>
            )}
            <DataApiPanel
              project={project}
              onError={onError}
              refreshToken={tables.map((tb) => tb.name).join(",")}
            />
          </div>
        )}
      </CardContent>
    </Card>
  );
}
