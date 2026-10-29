"use client";

import { useTranslations } from "../../lib/i18n";
import { useEffect, useState } from "react";
import { Loader2, TriangleAlert } from "lucide-react";
import { api } from "../../lib/api";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import {
  DEFAULT_CONTENT_POLICY,
  PII_CATEGORY_FIELDS,
  isContentPolicyDirty,
  isPiiRedactionInert,
  normalizeContentPolicy,
  type ContentPolicy,
  type InjectionAction,
} from "./contentPolicy";

// Not translated, following the rest of /admin/guardrails (see the note at the
// top of page.tsx): plain English until the next I18N-MIGRATION-PLAN batch.

export default function ContentPolicyCard({ canManage }: { canManage: boolean }) {
  const tg = useTranslations("f2GapsAdmin");
  const [saved, setSaved] = useState<ContentPolicy>(DEFAULT_CONTENT_POLICY);
  const [draft, setDraft] = useState<ContentPolicy>(DEFAULT_CONTENT_POLICY);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getLlmGatewayContentPolicy()
      .then((raw: unknown) => {
        if (cancelled) return;
        const p = normalizeContentPolicy(raw);
        setSaved(p);
        setDraft(p);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error && e.message ? e.message : tg("contentPolicyLoadFailed"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- tg only supplies a fallback message
  }, []);

  function patch(change: Partial<ContentPolicy>) {
    setDraft((d) => ({ ...d, ...change }));
    setNotice(null);
  }

  async function save() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const p = normalizeContentPolicy(await api.updateLlmGatewayContentPolicy(draft));
      setSaved(p);
      setDraft(p);
      setNotice("Saved. It applies from the next model call.");
    } catch (e: unknown) {
      setError(e instanceof Error && e.message ? e.message : "Could not save the content policy.");
    } finally {
      setBusy(false);
    }
  }

  const dirty = isContentPolicyDirty(saved, draft);
  const disabled = busy || !canManage;

  return (
    <Card data-testid="content-policy-card">
      <CardHeader>
        <CardTitle>Model call content policy</CardTitle>
        <CardDescription>
          Applied to every prompt this workspace sends to a model. Both checks are off until you turn them on.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        {loading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" aria-hidden="true" /> Loading…
          </div>
        ) : (
          <>
            {error && (
              <Alert variant="destructive">
                <TriangleAlert className="size-4" aria-hidden="true" />
                <AlertTitle>That didn&apos;t work</AlertTitle>
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            {notice && (
              <Alert>
                <AlertTitle>Saved</AlertTitle>
                <AlertDescription>{notice}</AlertDescription>
              </Alert>
            )}
            {!canManage && (
              <p className="text-xs text-muted-foreground">Only workspace admins can change this policy.</p>
            )}

            <fieldset className="flex flex-col gap-2">
              <label className="flex cursor-pointer items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  className="mt-1"
                  data-testid="content-policy-pii-enabled"
                  checked={draft.piiRedactionEnabled}
                  disabled={disabled}
                  onChange={(e) => patch({ piiRedactionEnabled: e.target.checked })}
                />
                <span>
                  <span className="font-medium">{tg("redactPii")}</span>
                  <span className="block text-xs text-muted-foreground">
                    Matching values in outgoing prompts are replaced with a placeholder.
                  </span>
                </span>
              </label>
              <div className="ms-6 flex flex-col gap-1.5">
                {PII_CATEGORY_FIELDS.map(({ key, label }) => (
                  <label key={key} className="flex cursor-pointer items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={draft[key]}
                      disabled={disabled || !draft.piiRedactionEnabled}
                      onChange={(e) => patch({ [key]: e.target.checked })}
                    />
                    {label}
                  </label>
                ))}
                {isPiiRedactionInert(draft) && (
                  <p className="text-xs text-destructive">Pick at least one category, or nothing will be redacted.</p>
                )}
              </div>
            </fieldset>

            <fieldset className="flex flex-col gap-2">
              <label className="flex cursor-pointer items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  className="mt-1"
                  data-testid="content-policy-injection-enabled"
                  checked={draft.injectionScreeningEnabled}
                  disabled={disabled}
                  onChange={(e) => patch({ injectionScreeningEnabled: e.target.checked })}
                />
                <span>
                  <span className="font-medium">Screen for prompt-injection attempts</span>
                  <span className="block text-xs text-muted-foreground">
                    Detects messages that try to override the agent&apos;s instructions.
                  </span>
                </span>
              </label>
              <div className="ms-6 flex flex-col gap-1.5">
                {(
                  [
                    ["FLAG", "Flag only", "Record it in the audit log and let the call continue."],
                    [
                      "BLOCK",
                      "Block",
                      "Reject the call before it reaches the model. A false positive loses that turn.",
                    ],
                  ] as [InjectionAction, string, string][]
                ).map(([value, title, help]) => (
                  <label key={value} className="flex cursor-pointer items-start gap-2 text-sm">
                    <input
                      type="radio"
                      name="content-policy-injection-action"
                      className="mt-1"
                      checked={draft.injectionScreeningAction === value}
                      disabled={disabled || !draft.injectionScreeningEnabled}
                      onChange={() => patch({ injectionScreeningAction: value })}
                    />
                    <span>
                      <span className="font-medium">{title}</span>
                      <span className="block text-xs text-muted-foreground">{help}</span>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>

            <div>
              <Button
                type="button"
                size="sm"
                data-testid="content-policy-save"
                disabled={disabled || !dirty}
                onClick={() => void save()}
              >
                {busy && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
                Save policy
              </Button>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
