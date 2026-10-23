"use client";

import { useEffect, useState } from "react";
import { TriangleAlert } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { api } from "../../lib/api";
import type { PromptPreview, PromptPreviewBlock } from "../../lib/api/promptRegistry";
import { errorMessage } from "../../lib/errors";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import { Label } from "../../components/ui/label";
import { Select } from "../../components/ui/select";
import { Switch } from "../../components/ui/switch";
import { Textarea } from "../../components/ui/textarea";

const NUMBER = new Intl.NumberFormat();

/** Where a block's wording came from, as one short badge label. */
export function blockOrigin(b: Pick<PromptPreviewBlock, "key" | "source" | "scope" | "version">, t: (k: string, v?: Record<string, string | number>) => string): string {
  if (!b.key) return b.source === "agent_config" ? t("regOriginAgentConfig") : t("regOriginCode");
  if (b.version === null || b.version === undefined || b.version <= 0) return t("regOriginDefault");
  return t("regOriginOverride", { scope: b.scope ?? b.source, version: b.version });
}

/**
 * "Preview final prompt": the real system prompt and tool list of one agent, built by the same code a turn uses
 * (POST /api/agents/{id}/prompt-preview), with every block labelled by the registry key, scope and version that
 * supplied it and a token estimate. Nothing is sent to a model.
 */
export default function PromptPreviewPanel({ initialAgentId = null }: { initialAgentId?: string | null }) {
  const t = useTranslations("promptDirectives");
  const [agents, setAgents] = useState<Array<{ id: string; name: string }>>([]);
  const [agentId, setAgentId] = useState<string | null>(initialAgentId);
  const [message, setMessage] = useState("");
  const [smallModel, setSmallModel] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<PromptPreview | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .listAgents()
      .then((list: Array<{ id: string; name?: string | null }>) => {
        if (!cancelled) setAgents(list.map((a) => ({ id: a.id, name: a.name || a.id })));
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(errorMessage(e, t("regAgentsFailed")));
      });
    return () => {
      cancelled = true;
    };
  }, [t]);

  async function build() {
    if (!agentId) return;
    setBusy(true);
    setError(null);
    try {
      setPreview(
        await api.previewAgentPrompt(agentId, {
          message: message.trim() || undefined,
          smallModel,
        })
      );
    } catch (e: unknown) {
      setError(errorMessage(e, t("regPreviewFailed")));
    } finally {
      setBusy(false);
    }
  }

  const messages = preview ? Array.from(new Set(preview.blocks.map((b) => b.message))) : [];

  return (
    <section aria-label={t("regPreviewTitle")} className="flex flex-col gap-4" data-testid="prompt-preview-panel">
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">{t("regPreviewTitle")}</CardTitle>
          <CardDescription className="text-xs">{t("regPreviewIntro")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <Label htmlFor="prev-agent" className="text-xs">
              {t("regAgentLabel")}
            </Label>
            <Select
              id="prev-agent"
              aria-label={t("regAgentLabel")}
              value={agentId ?? ""}
              placeholder={t("regAgentPlaceholder")}
              options={agents.map((a) => ({ value: a.id, label: a.name }))}
              onValueChange={setAgentId}
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="prev-message" className="text-xs">
              {t("regPreviewMessageLabel")}
            </Label>
            <Textarea
              id="prev-message"
              className="min-h-16 text-sm"
              value={message}
              placeholder={t("regPreviewMessagePlaceholder")}
              onChange={(e) => setMessage(e.target.value)}
            />
          </div>
          <div className="flex items-center gap-2">
            <Switch id="prev-small" checked={smallModel} onCheckedChange={setSmallModel} aria-label={t("regPreviewSmallModel")} />
            <Label htmlFor="prev-small" className="text-xs">
              {t("regPreviewSmallModel")}
            </Label>
          </div>
          <div>
            <Button type="button" size="sm" disabled={!agentId || busy} onClick={() => void build()}>
              {busy ? t("regPreviewBuilding") : t("regPreviewBuild")}
            </Button>
          </div>
        </CardContent>
      </Card>

      {error && (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {preview && (
        <div className="flex flex-col gap-4" data-testid="prompt-preview-result">
          <p className="text-sm" role="status">
            {t("regPreviewTotals", {
              prompt: NUMBER.format(preview.promptEstimatedTokens),
              tools: NUMBER.format(preview.toolsEstimatedTokens),
            })}
          </p>
          {preview.overBudget && (
            <Alert variant="warning">
              <TriangleAlert aria-hidden="true" />
              <AlertTitle>{t("regPreviewOverBudgetTitle")}</AlertTitle>
              <AlertDescription>{t("regPreviewOverBudget", { budget: NUMBER.format(preview.tokenBudget) })}</AlertDescription>
            </Alert>
          )}
          {preview.tokenEstimateNote && <p className="text-xs text-muted-foreground">{preview.tokenEstimateNote}</p>}

          {messages.map((m) => (
            <Card key={m}>
              <CardHeader>
                <CardTitle className="text-sm">{t("regPreviewMessageHeading", { message: m })}</CardTitle>
              </CardHeader>
              <CardContent>
                <ol className="flex flex-col gap-2">
                  {preview.blocks
                    .filter((b) => b.message === m)
                    .map((b, i) => (
                      <li
                        key={`${b.id}-${i}`}
                        className="rounded-md border border-border bg-background/50 p-2 text-xs"
                        data-testid={`block-${b.id}`}
                      >
                        <div className="flex flex-wrap items-center gap-2">
                          <code className="font-mono font-medium text-foreground">{b.key ?? b.id}</code>
                          <Badge variant={b.version && b.version > 0 ? "accent" : "secondary"}>{blockOrigin(b, t)}</Badge>
                          <span className="text-muted-foreground">
                            {t("regPreviewTokens", { tokens: NUMBER.format(b.estimatedTokens) })}
                          </span>
                        </div>
                        <pre className="mt-1.5 max-h-48 overflow-auto whitespace-pre-wrap break-words font-sans">{b.text}</pre>
                      </li>
                    ))}
                </ol>
              </CardContent>
            </Card>
          ))}

          <Card>
            <CardHeader>
              <CardTitle className="text-sm">{t("regPreviewTools", { count: preview.tools.length })}</CardTitle>
            </CardHeader>
            <CardContent>
              {preview.tools.length === 0 ? (
                <p className="text-xs text-muted-foreground">{t("regPreviewNoTools")}</p>
              ) : (
                <ul className="flex flex-col gap-1.5">
                  {preview.tools.map((tool) => (
                    <li key={tool.name} className="text-xs">
                      <code className="font-mono font-medium text-foreground">{tool.name}</code>{" "}
                      <span className="text-muted-foreground">
                        {t("regPreviewTokens", { tokens: NUMBER.format(tool.estimatedTokens) })}
                      </span>
                      {tool.description && <p className="text-muted-foreground">{tool.description}</p>}
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>

          {preview.notes.length > 0 && (
            <Alert>
              <AlertTitle>{t("regPreviewNotes")}</AlertTitle>
              <AlertDescription>
                <ul className="list-disc pl-4">
                  {preview.notes.map((n) => (
                    <li key={n}>{n}</li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}
        </div>
      )}
    </section>
  );
}
