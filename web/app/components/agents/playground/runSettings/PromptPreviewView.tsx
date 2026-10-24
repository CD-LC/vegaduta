"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { TriangleAlert } from "lucide-react";
import { api } from "../../../../lib/api";
import type { PromptPreview, PromptPreviewBlock } from "../../../../lib/api/promptRegistry";
import { errorMessage } from "../../../../lib/errors";
import { useTranslations } from "../../../../lib/i18n";
import { Alert, AlertDescription, AlertTitle } from "../../../ui/alert";
import { Badge } from "../../../ui/badge";
import { Button } from "../../../ui/button";
import { Skeleton } from "../../../ui/skeleton";
import { PROMPTS_ADMIN_HREF } from "../runSettingsModel";
import type { PlaygroundAgent } from "../types";

const NUMBER = new Intl.NumberFormat();

type Translate = (key: string, vars?: Record<string, string | number>) => string;

/** Where a block's wording comes from, as one short label: the registry scope and version, or "platform code". */
export function blockSourceLabel(
  t: Translate,
  b: Pick<PromptPreviewBlock, "key" | "source" | "scope" | "version">
): string {
  if (!b.key) return b.source === "agent_config" ? t("rsOriginAgentConfig") : t("rsOriginCode");
  if (b.version == null || b.version <= 0) return t("rsOriginDefault");
  return t("rsOriginOverride", { scope: (b.scope ?? b.source ?? "").toLowerCase(), version: b.version });
}

/**
 * "Preview prompt": the final system prompt and tool list this agent would send, built by the same code a turn
 * uses (POST /api/agents/{id}/prompt-preview) without calling a model. Every block says which prompt it is and
 * where its wording came from (shipped default, or a workspace / agent override at a version).
 */
export function PromptPreviewView({ agent, sessionId }: { agent: PlaygroundAgent; sessionId: string | null }) {
  const t = useTranslations("agentPlayground");
  const tRef = useRef(t);
  tRef.current = t;
  const [state, setState] = useState<
    { status: "loading" } | { status: "error"; message: string } | { status: "ready"; preview: PromptPreview }
  >({ status: "loading" });

  const build = useCallback(() => {
    let cancelled = false;
    setState({ status: "loading" });
    Promise.resolve()
      .then(() => api.previewAgentPrompt(agent.id, sessionId ? { sessionId } : {}))
      .then((preview: PromptPreview) => {
        if (!cancelled) setState({ status: "ready", preview });
      })
      .catch((e: unknown) => {
        if (!cancelled) setState({ status: "error", message: errorMessage(e, tRef.current("rsPreviewFailed")) });
      });
    return () => {
      cancelled = true;
    };
  }, [agent.id, sessionId]);

  useEffect(() => build(), [build]);

  if (state.status === "loading") {
    return (
      <div role="status" aria-label={t("rsPreviewBuilding")} className="space-y-2" data-testid="rs-preview-loading">
        <p className="m-0 text-xs text-muted-foreground">{t("rsPreviewBuilding")}</p>
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
      </div>
    );
  }
  if (state.status === "error") {
    return (
      <div role="alert" className="flex flex-wrap items-center gap-2 text-sm text-destructive" data-testid="rs-preview-error">
        <span className="min-w-0">{state.message}</span>
        <Button type="button" size="sm" variant="outline" onClick={build}>
          {t("rsRetry")}
        </Button>
      </div>
    );
  }

  const { preview } = state;
  const messages = Array.from(new Set(preview.blocks.map((b) => b.message)));
  return (
    <div className="space-y-3" data-testid="rs-preview-result">
      <p className="m-0 text-xs text-foreground" role="status">
        {t("rsPreviewTotals", {
          prompt: NUMBER.format(preview.promptEstimatedTokens),
          tools: NUMBER.format(preview.toolsEstimatedTokens),
        })}
      </p>
      {preview.overBudget && (
        <Alert variant="warning">
          <TriangleAlert aria-hidden="true" />
          <AlertTitle>{t("rsPreviewOverBudgetTitle")}</AlertTitle>
          <AlertDescription>{t("rsPreviewOverBudget", { budget: NUMBER.format(preview.tokenBudget) })}</AlertDescription>
        </Alert>
      )}
      {messages.length === 0 && <p className="m-0 text-xs text-muted-foreground">{t("rsPreviewNoBlocks")}</p>}
      {messages.map((m) => (
        <section key={m} aria-label={t("rsPreviewMessage", { message: m })} className="space-y-1.5">
          <h4 className="m-0 text-xs font-semibold text-foreground">{t("rsPreviewMessage", { message: m })}</h4>
          <ol className="m-0 list-none space-y-1.5 p-0">
            {preview.blocks
              .filter((b) => b.message === m)
              .map((b, i) => (
                <li
                  key={`${b.id}-${i}`}
                  className="rounded-md border border-border bg-background/50 p-2 text-xs"
                  data-testid={`rs-block-${b.id}`}
                >
                  <div className="flex flex-wrap items-center gap-1.5">
                    <code className="break-all font-mono font-medium text-foreground">{b.key ?? b.id}</code>
                    <Badge variant={b.version && b.version > 0 ? "accent" : "secondary"}>{blockSourceLabel(t, b)}</Badge>
                    <span className="text-muted-foreground">
                      {t("rsPreviewTokens", { tokens: NUMBER.format(b.estimatedTokens) })}
                    </span>
                  </div>
                  <details className="mt-1">
                    <summary className="cursor-pointer text-muted-foreground">{t("rsPreviewShowText")}</summary>
                    <pre className="mt-1 max-h-48 overflow-auto whitespace-pre-wrap break-words font-sans">{b.text}</pre>
                  </details>
                </li>
              ))}
          </ol>
        </section>
      ))}
      <section aria-label={t("rsPreviewTools", { count: preview.tools.length })} className="space-y-1.5">
        <h4 className="m-0 text-xs font-semibold text-foreground">{t("rsPreviewTools", { count: preview.tools.length })}</h4>
        {preview.tools.length === 0 ? (
          <p className="m-0 text-xs text-muted-foreground">{t("rsPreviewNoTools")}</p>
        ) : (
          <ul className="m-0 list-none space-y-1.5 p-0">
            {preview.tools.map((tool) => (
              <li key={tool.name} className="text-xs" data-testid={`rs-preview-tool-${tool.name}`}>
                <code className="break-all font-mono font-medium text-foreground">{tool.name}</code>{" "}
                <span className="text-muted-foreground">
                  {t("rsPreviewTokens", { tokens: NUMBER.format(tool.estimatedTokens) })}
                </span>
                {tool.description && <p className="m-0 text-muted-foreground">{tool.description}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>
      {preview.notes.length > 0 && (
        <Alert>
          <AlertTitle>{t("rsPreviewNotes")}</AlertTitle>
          <AlertDescription>
            <ul className="m-0 list-disc pl-4">
              {preview.notes.map((n) => (
                <li key={n}>{n}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      <Link href={PROMPTS_ADMIN_HREF} className="inline-block text-xs font-medium text-primary underline-offset-4 hover:underline">
        {t("rsPreviewOpenAdmin")}
      </Link>
    </div>
  );
}
