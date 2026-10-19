"use client";

// Intent mandate form for the agentic-commerce console (page.tsx).

import { useState } from "react";
import { api } from "../../lib/api";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { csvList } from "./commerceFormat";

// ---- Intent mandate form ------------------------------------------------------

export function IntentForm({ agentId, onCreated }: { agentId: string; onCreated: () => void }) {
  const t = useTranslations("adminCommercePage");
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({
    intentText: "",
    categories: "",
    maxTotal: "",
    currency: "USD",
    expiresAt: "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleCreate = async () => {
    const maxTotalMinor = Math.round(Number(form.maxTotal));
    const expiresAt = form.expiresAt ? new Date(form.expiresAt) : null;
    if (!Number.isFinite(maxTotalMinor) || maxTotalMinor <= 0 || !expiresAt || Number.isNaN(expiresAt.getTime())) {
      setError(t("intentInvalid"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.createIntentMandate({
        agentId,
        intentText: form.intentText || null,
        categories: csvList(form.categories),
        maxTotalMinor,
        currency: form.currency.toUpperCase(),
        expiresAt: expiresAt.toISOString(),
      });
      toast.success(t("intentCreated"));
      setOpen(false);
      setForm({ intentText: "", categories: "", maxTotal: "", currency: "USD", expiresAt: "" });
      onCreated();
    } catch (e: any) {
      setError(e?.message || t("intentFailed"));
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <Button type="button" size="sm" onClick={() => setOpen(true)}>
        {t("newIntent")}
      </Button>
    );
  }

  return (
    <Card className="p-4">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{t("intentWizardTitle")}</h3>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => {
            setOpen(false);
            setError(null);
          }}
        >
          {t("cancel")}
        </Button>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="grid gap-1.5 sm:col-span-2">
          <Label htmlFor="intent-text">{t("fieldIntentText")}</Label>
          <Textarea
            id="intent-text"
            rows={2}
            placeholder={t("fieldIntentTextPlaceholder")}
            value={form.intentText}
            onChange={(e) => setForm((f) => ({ ...f, intentText: e.target.value }))}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="intent-cats">{t("fieldCategories")}</Label>
          <Input
            id="intent-cats"
            placeholder="books, travel"
            value={form.categories}
            onChange={(e) => setForm((f) => ({ ...f, categories: e.target.value }))}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="intent-currency">{t("fieldCurrency")}</Label>
          <Input
            id="intent-currency"
            className="w-24"
            maxLength={3}
            value={form.currency}
            onChange={(e) => setForm((f) => ({ ...f, currency: e.target.value.toUpperCase() }))}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="intent-max">{t("fieldMaxTotal")}</Label>
          <Input
            id="intent-max"
            inputMode="numeric"
            placeholder="500000"
            value={form.maxTotal}
            onChange={(e) => setForm((f) => ({ ...f, maxTotal: e.target.value }))}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="intent-exp">{t("fieldExpires")}</Label>
          <Input
            id="intent-exp"
            type="datetime-local"
            value={form.expiresAt}
            onChange={(e) => setForm((f) => ({ ...f, expiresAt: e.target.value }))}
          />
        </div>
      </div>
      {error && <div className="mt-3 text-sm text-destructive">{error}</div>}
      <div className="mt-4">
        <Button type="button" size="sm" disabled={busy} onClick={handleCreate}>
          {t("createIntent")}
        </Button>
      </div>
    </Card>
  );
}
