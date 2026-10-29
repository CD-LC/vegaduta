// @ts-nocheck
"use client";

// Split out of page.tsx (behaviour unchanged).

import { useEffect, useId, useState } from "react";
import { Sparkles, Loader2 } from "lucide-react";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Textarea } from "../components/ui/textarea";
import { Label } from "../components/ui/label";
import { Card, CardContent } from "../components/ui/card";
import { useTranslations } from "../lib/i18n";
import { appBuilder, newProjectId } from "./lib";

/* ------------------------------------------------------------------ */
/* Empty state: template picker + prompt                              */

export function EmptyState({ templates, onCreated, onError }) {
  const t = useTranslations("appBuilder");
  const [templateId, setTemplateId] = useState("");
  const [prompt, setPrompt] = useState("");
  const [ephemeral, setEphemeral] = useState(false);
  // Held as text so the field can be cleared while typing a new number;
  // clamped to 1..720 (default 72) only when it is used.
  const [ttlRaw, setTtlRaw] = useState("72");
  const [busy, setBusy] = useState(false);
  const promptId = useId();
  const ttlId = useId();

  useEffect(() => {
    if (!templateId && templates.length > 0) setTemplateId(templates[0].template_id);
  }, [templates, templateId]);

  const create = async () => {
    if (!templateId) return;
    setBusy(true);
    try {
      const project_id = newProjectId();
      const body = { project_id, template_id: templateId };
      if (prompt.trim()) body.prompt = prompt.trim();
      if (ephemeral) {
        body.ephemeral = true;
        body.ttl_hours = Math.max(1, Math.min(720, parseInt(ttlRaw, 10) || 72));
      }
      const p = await appBuilder.createProject(body);
      onCreated(
        p || {
          project_id,
          template_id: templateId,
          status: "creating",
          created_at: new Date().toISOString(),
          preview: null,
        }
      );
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-3xl py-6">
      <div className="mb-6 text-center">
        <div className="mb-3 inline-flex size-12 items-center justify-center rounded-2xl bg-primary/10 text-primary">
          <Sparkles aria-hidden="true" size={22} />
        </div>
        <h2 className="text-lg font-semibold text-foreground">{t("emptyHeading")}</h2>
        <p className="mx-auto mt-1.5 max-w-xl text-sm text-muted-foreground">{t("emptySubtext")}</p>
      </div>

      <div className="mb-5">
        <Label className="mb-2 block text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {t("templateHeading")}
        </Label>
        {templates.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("noTemplatesText")}</p>
        ) : (
          <div className="grid gap-3 sm:grid-cols-3">
            {templates.map((tpl) => {
              const active = templateId === tpl.template_id;
              return (
                <button
                  key={tpl.template_id}
                  type="button"
                  aria-pressed={active}
                  onClick={() => setTemplateId(tpl.template_id)}
                  className={`rounded-xl border p-4 text-start transition-colors ${
                    active ? "border-primary bg-primary/5" : "border-border hover:bg-accent/50"
                  }`}
                >
                  <div className="text-sm font-semibold text-foreground">{tpl.name}</div>
                  <div className="mt-1 text-xs leading-relaxed text-muted-foreground">{tpl.description}</div>
                </button>
              );
            })}
          </div>
        )}
      </div>

      <Card>
        <CardContent className="pt-5">
          <Label
            htmlFor={promptId}
            className="mb-2 block text-xs font-medium uppercase tracking-wide text-muted-foreground"
          >
            {t("promptHeading")}
          </Label>
          <Textarea
            id={promptId}
            className="min-h-32"
            placeholder={t("promptPlaceholder")}
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
          />
          <label className="mt-4 flex cursor-pointer items-start gap-2.5 rounded-lg border border-border p-3">
            <input
              type="checkbox"
              className="mt-0.5"
              checked={ephemeral}
              onChange={(e) => setEphemeral(e.target.checked)}
            />
            <span>
              <span className="block text-sm font-medium text-foreground">{t("ephemeralLabel")}</span>
              <span className="block text-xs leading-relaxed text-muted-foreground">{t("ephemeralHint")}</span>
            </span>
          </label>
          {ephemeral && (
            <div className="mt-2 flex items-center gap-2 ps-1">
              <Label htmlFor={ttlId} className="text-xs text-muted-foreground">
                {t("ttlLabel")}
              </Label>
              <Input
                id={ttlId}
                type="number"
                min={1}
                max={720}
                className="w-24"
                inputMode="numeric"
                value={ttlRaw}
                onChange={(e) => setTtlRaw(e.target.value.replace(/[^0-9]/g, "").slice(0, 3))}
                onBlur={() => setTtlRaw(String(Math.max(1, Math.min(720, parseInt(ttlRaw, 10) || 72))))}
              />
            </div>
          )}
          <Button className="mt-4" disabled={busy || !templateId} onClick={create}>
            {busy ? (
              <Loader2 aria-hidden="true" size={15} className="animate-spin" />
            ) : (
              <Sparkles aria-hidden="true" size={15} />
            )}{" "}
            {t("createButton")}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
