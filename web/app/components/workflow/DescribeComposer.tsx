"use client";

import { useId, useState } from "react";
import { ArrowRight, Loader2, Wand2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";
import { UpgradeLock } from "../ui/upgrade-lock";
import { starterGraph } from "./nodeDefs";
import {
  DESCRIBE_PROMPT_MAX_CHARS,
  deriveWorkflowName,
  normalizeDescribePrompt,
  writeDescribeHandoff,
} from "./describeHandoff";

/**
 * "Describe it, automate it" - the plain-language entry point on the
 * Workflows list page (nlflow W3).
 *
 * Before this, the AI builder was only reachable after naming and creating a
 * workflow, opening the canvas and finding a small bar under the graph. Here a
 * newcomer types one sentence and lands on a canvas for it.
 *
 * What submit does, and nothing more:
 *   1. POST /api/workflows (the same call and payload shape as the create form
 *      in WorkflowDesignerPanel) - name derived from the first words of the
 *      sentence, the sentence as description, the one-Trigger starter graph.
 *      The server always creates it as DRAFT.
 *   2. Stash the sentence in sessionStorage for that workflow id (never in the
 *      URL - see describeHandoff.ts).
 *   3. Hand the id to the page, which navigates to /workflows/<id>.
 * Generating the graph happens on the canvas, which picks the stashed sentence
 * up; until that lands the sentence is still saved as the description.
 *
 * The plan's workflow cap is enforced server-side (WorkflowService#create).
 * Its 403 is an expected outcome, so the create call is quiet on 403 (no
 * global toast) and the server's message is shown inline with the upgrade
 * affordance on it.
 */
const EXAMPLE_KEYS = ["exampleInvoice", "exampleLead", "exampleDigest", "exampleTriage", "exampleAbend"] as const;

interface DescribeComposerProps {
  /** Called with the new workflow's id once it exists and the prompt is stashed. */
  onCreated: (workflowId: string) => void;
}

export default function DescribeComposer({ onCreated }: DescribeComposerProps) {
  const t = useTranslations("workflowDescribe");
  const [prompt, setPrompt] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The last failure was a 403 (plan cap): the inline message carries the upgrade popup.
  const [capHit, setCapHit] = useState(false);
  const promptId = useId();
  const hintId = useId();

  const cleaned = normalizeDescribePrompt(prompt);
  const canSubmit = cleaned.length > 0 && !busy;

  async function buildDraft() {
    if (busy) return;
    if (!cleaned) {
      setError(t("promptRequired"));
      return;
    }
    setBusy(true);
    setError(null);
    setCapHit(false);
    try {
      const created = await api.createWorkflowQuietOnCap({
        name: deriveWorkflowName(cleaned) || t("fallbackName"),
        description: cleaned,
        graph: starterGraph(),
      });
      // A failed write (private mode, blocked storage) only loses the
      // auto-build; the workflow exists and the canvas still opens.
      writeDescribeHandoff(created.id, cleaned);
      onCreated(created.id);
      // busy stays true on purpose: the page is navigating away, and
      // re-enabling the button would invite a second, duplicate create.
    } catch (err: any) {
      setError(err?.message || t("couldNotCreate"));
      setCapHit(err?.status === 403);
      setBusy(false);
    }
  }

  return (
    <Card
      data-testid="workflow-describe-composer"
      className="mb-6 border-primary/30 bg-gradient-to-br from-primary/10 via-primary/5 to-transparent"
    >
      <CardContent className="space-y-4 p-4 sm:p-5">
        <div className="flex items-start gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary/15 text-primary ring-1 ring-inset ring-primary/25">
            <Wand2 className="size-5" aria-hidden="true" />
          </span>
          <div className="min-w-0 space-y-1">
            <h2 className="text-base font-semibold tracking-tight text-foreground">{t("heading")}</h2>
            <p id={hintId} className="text-sm leading-relaxed text-muted-foreground">
              {t("subheading")}
            </p>
          </div>
        </div>

        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            void buildDraft();
          }}
        >
          <div className="space-y-1.5">
            <Label htmlFor={promptId} className="sr-only">
              {t("promptLabel")}
            </Label>
            <Textarea
              id={promptId}
              data-testid="workflow-describe-input"
              value={prompt}
              onChange={(e) => {
                setPrompt(e.target.value);
                if (error) setError(null);
              }}
              onKeyDown={(e) => {
                // Enter alone keeps adding lines; Ctrl/Cmd+Enter submits.
                if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                  e.preventDefault();
                  void buildDraft();
                }
              }}
              placeholder={t("promptPlaceholder")}
              maxLength={DESCRIBE_PROMPT_MAX_CHARS}
              rows={3}
              disabled={busy}
              aria-describedby={hintId}
              className="min-h-20 bg-background"
            />
          </div>

          <div className="space-y-2">
            <p className="text-xs font-medium text-muted-foreground">{t("examplesLabel")}</p>
            <div className="flex flex-wrap gap-2">
              {EXAMPLE_KEYS.map((key) => (
                <Button
                  key={key}
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  data-testid={`workflow-describe-example-${key}`}
                  className="h-auto min-h-8 max-w-full whitespace-normal py-1.5 text-start"
                  onClick={() => {
                    setPrompt(t(`${key}Prompt`));
                    setError(null);
                  }}
                >
                  {t(`${key}Label`)}
                </Button>
              ))}
            </div>
          </div>

          {error && capHit ? (
            <UpgradeLock reason={error} label={t("upgradeYourPlan")} className="w-full">
              <p
                role="alert"
                data-testid="workflow-describe-error"
                data-cap-hit="true"
                className="w-full rounded-lg border border-border bg-muted px-3 py-2 text-sm text-foreground"
              >
                {error}
              </p>
            </UpgradeLock>
          ) : error ? (
            <p role="alert" data-testid="workflow-describe-error" className="text-sm text-destructive">
              {error}
            </p>
          ) : null}

          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-xs text-muted-foreground">{t("draftNote")}</p>
            <Button type="submit" disabled={!canSubmit} data-testid="workflow-describe-submit">
              {busy ? (
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
              ) : (
                <Wand2 className="size-4" aria-hidden="true" />
              )}
              {busy ? t("building") : t("buildDraft")}
              {busy ? null : <ArrowRight className="size-4" aria-hidden="true" />}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
