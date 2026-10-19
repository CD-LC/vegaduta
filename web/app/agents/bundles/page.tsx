"use client";

import { useRef, useState, type ChangeEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AlertCircle, ArrowLeft, Bot, Info, Loader2, Plug, ShieldCheck, Sparkles, Upload } from "lucide-react";
import { api } from "../../lib/api";
import type { AgentBundle, AgentBundlePreview } from "../../lib/api/agentBundles";
import { useTranslations } from "../../lib/i18n";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import { READY_MADE, parseBundle } from "./bundleLogic";

/**
 * /agents/bundles - start from a ready-made agent, or import an agent file someone sent you.
 *
 * A bundle is untrusted, so the person always sees a preview first (tools the agent may use, the
 * autonomy it will really get, what the server changed for safety) and only then imports. The file
 * is held in component state and sent in the request body; it is never logged or stored.
 */

type PageError = { message: string };
type Preview = { bundle: AgentBundle; data: AgentBundlePreview };

export default function AgentBundlesPage() {
  const t = useTranslations("agentBundles");
  const router = useRouter();
  const fileRef = useRef<HTMLInputElement>(null);
  const [text, setText] = useState("");
  const [fileName, setFileName] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState<null | "preview" | "import">(null);
  const [error, setError] = useState<PageError | null>(null);

  const fail = (err: { status?: number; message?: string } | null) => {
    const status = typeof err?.status === "number" ? err.status : undefined;
    if (status === 404) return setError({ message: t("errUnavailable") });
    if (status === 403) return setError({ message: t("errPlan") });
    if (status === 429) return setError({ message: t("errRateLimit") });
    if (status === 400 || status === 422) {
      return setError({ message: typeof err?.message === "string" && err.message ? err.message : t("errInvalid") });
    }
    setError({ message: t("errGeneric") });
  };

  const showPreview = async (bundle: AgentBundle) => {
    setBusy("preview");
    setError(null);
    try {
      const data = await api.previewAgentBundle(bundle);
      setPreview({ bundle, data });
      setName(bundle.title);
    } catch (err) {
      setPreview(null);
      fail(err);
    } finally {
      setBusy(null);
    }
  };

  const previewText = async (raw: string) => {
    const parsed = parseBundle(raw);
    if (!parsed.bundle) {
      setPreview(null);
      const key = {
        empty: "errEmpty",
        "too-large": "errTooLarge",
        "invalid-json": "errInvalidJson",
        "not-a-bundle": "errNotBundle",
        "wrong-format": "errWrongFormat",
      }[parsed.reason || "not-a-bundle"];
      return setError({ message: t(key) });
    }
    await showPreview(parsed.bundle);
  };

  const previewReady = async (file: string) => {
    if (busy) return;
    setError(null);
    try {
      const res = await fetch(`/agent-bundles/${file}`);
      if (!res.ok) throw new Error("fetch");
      await previewText(await res.text());
    } catch {
      setError({ message: t("errGeneric") });
    }
  };

  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    try {
      const raw = await file.text();
      setText(raw);
      setFileName(file.name);
      await previewText(raw);
    } catch {
      setError({ message: t("errFileRead") });
    }
  };

  const runImport = async () => {
    if (busy || !preview) return;
    setBusy("import");
    setError(null);
    try {
      const res = await api.importAgentBundle({ ...preview.bundle, title: name.trim() || preview.bundle.title });
      if (res?.resourceId) {
        // Stay busy while the route changes so a second click cannot create a duplicate.
        router.push(`/agents?agentId=${encodeURIComponent(res.resourceId)}`);
        return;
      }
      setError({ message: t("errGeneric") });
    } catch (err) {
      fail(err);
    }
    setBusy(null);
  };

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        title={t("title")}
        subtitle={t("subtitle")}
        actions={
          <>
            <Button asChild variant="outline" size="sm">
              <Link href="/connect">
                <Plug size={14} aria-hidden="true" /> {t("connectChannels")}
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href="/agents">
                <ArrowLeft size={14} aria-hidden="true" /> {t("backToAgents")}
              </Link>
            </Button>
          </>
        }
      />
      <div className="mx-auto w-full max-w-4xl space-y-6">
        <section aria-labelledby="ready-heading" className="space-y-3">
          <h2 id="ready-heading" className="text-sm font-medium text-foreground">
            {t("readyHeading")}
          </h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {READY_MADE.map((b) => (
              <Card key={b.id} className="gap-3 py-4" data-testid={`ready-${b.id}`}>
                <CardHeader className="px-4">
                  <CardTitle className="flex items-center gap-2 text-sm">
                    <Bot size={16} aria-hidden="true" /> {t(`ready.${b.id}.title`)}
                  </CardTitle>
                  <CardDescription>{t(`ready.${b.id}.body`)}</CardDescription>
                </CardHeader>
                <CardContent className="flex items-center justify-between gap-2 px-4">
                  <Badge variant="secondary">{t(b.needs === "nothing" ? "needsNothing" : "needsGoogle")}</Badge>
                  <Button size="sm" variant="outline" disabled={busy !== null} onClick={() => previewReady(b.file)}>
                    {busy === "preview" ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Sparkles aria-hidden="true" />}
                    {t("previewButton")}
                  </Button>
                </CardContent>
              </Card>
            ))}
          </div>
        </section>

        <Card className="gap-4 py-5">
          <CardHeader className="px-4 sm:px-6">
            <CardTitle className="text-sm">{t("fileHeading")}</CardTitle>
            <CardDescription>{t("fileBody")}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 px-4 sm:px-6">
            <Label htmlFor="bundle-text" className="sr-only">
              {t("pasteLabel")}
            </Label>
            <Textarea
              id="bundle-text"
              value={text}
              onChange={(e) => {
                setFileName(null);
                setText(e.target.value);
              }}
              placeholder={t("pastePlaceholder")}
              spellCheck={false}
              autoComplete="off"
              className="min-h-40 max-h-80 overflow-auto font-mono text-xs"
              disabled={busy !== null}
            />
            <div className="flex flex-wrap items-center gap-3">
              <input
                ref={fileRef}
                type="file"
                accept=".json,application/json"
                className="sr-only"
                tabIndex={-1}
                aria-label={t("uploadButton")}
                data-testid="bundle-file-input"
                onChange={onFile}
              />
              <Button type="button" variant="outline" size="sm" disabled={busy !== null} onClick={() => fileRef.current?.click()}>
                <Upload size={14} aria-hidden="true" /> {t("uploadButton")}
              </Button>
              <Button type="button" size="sm" disabled={busy !== null || !text.trim()} onClick={() => previewText(text)}>
                <Sparkles size={14} aria-hidden="true" /> {t("previewButton")}
              </Button>
              {fileName && <span className="min-w-0 break-all text-xs text-muted-foreground">{t("fileLoaded", { name: fileName })}</span>}
            </div>
          </CardContent>
        </Card>

        {error && (
          <Alert variant="error" data-testid="bundle-error">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              <p className="break-words">{error.message}</p>
            </AlertDescription>
          </Alert>
        )}

        {preview && (
          <Card className="gap-4 py-5" data-testid="bundle-preview">
            <CardHeader className="px-4 sm:px-6">
              <CardTitle className="text-base">{t("previewHeading")}</CardTitle>
              <CardDescription>{t("previewBody")}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-5 px-4 sm:px-6">
              <div className="space-y-2">
                <Label htmlFor="bundle-name">{t("nameLabel")}</Label>
                <Input id="bundle-name" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} disabled={busy !== null} />
              </div>
              <div className="space-y-1.5">
                <p className="text-sm font-medium text-foreground">{t("toolsLabel")}</p>
                <div className="flex flex-wrap gap-1.5">
                  {preview.data.tools.length ? (
                    preview.data.tools.map((tool) => (
                      <Badge key={tool} variant="outline">
                        {tool}
                      </Badge>
                    ))
                  ) : (
                    <span className="text-sm text-muted-foreground">{t("toolsAll")}</span>
                  )}
                </div>
              </div>
              <div className="flex items-start gap-2 text-sm">
                <ShieldCheck className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <span>{t("autonomy", { tier: preview.data.autonomyTier })}</span>
              </div>
              {preview.data.adjustments.length > 0 && (
                <Alert variant="info" data-testid="bundle-adjustments">
                  <Info aria-hidden="true" />
                  <AlertDescription>
                    <p className="font-medium">{t("adjustedTitle")}</p>
                    <ul className="list-disc pl-5">
                      {preview.data.adjustments.map((a) => (
                        <li key={a}>{a}</li>
                      ))}
                    </ul>
                  </AlertDescription>
                </Alert>
              )}
              <div className="flex flex-col gap-2 border-t border-border pt-4 sm:flex-row sm:items-center sm:justify-between">
                <p className="text-xs text-muted-foreground">{t("importNote")}</p>
                <Button type="button" onClick={runImport} disabled={busy !== null || !name.trim()}>
                  {busy === "import" ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
                  {busy === "import" ? t("importing") : t("importButton")}
                </Button>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </AppShell>
  );
}
