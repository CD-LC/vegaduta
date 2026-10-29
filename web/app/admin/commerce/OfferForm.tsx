"use client";

import { Fragment, useState } from "react";
import { api } from "../../lib/api";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";

// ---- x402 offers ---------------------------------------------------------------

export default function OfferForm({ agentId, onCreated }: { agentId: string; onCreated: () => void }) {
    const tHC = useTranslations("hcAdminCommerceOfferForm");
  const t = useTranslations("adminCommercePage");
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState({
    slug: "",
    title: "",
    description: "",
    priceMinor: "",
    currency: "USD",
    resourcePayload: "",
    evm: false,
    evmPriceAtomic: "",
    evmAsset: "",
    evmNetwork: "base",
    evmAssetName: "USD Coin",
    evmAssetVersion: "2",
    payTo: "",
  });

  const handleCreate = async () => {
    const priceMinor = Math.round(Number(form.priceMinor));
    if (!form.slug.trim() || !form.title.trim() || !Number.isFinite(priceMinor) || priceMinor <= 0) {
      setError(t("offerInvalid"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.createX402Offer(agentId, {
        slug: form.slug.trim(),
        title: form.title.trim(),
        description: form.description || null,
        priceMinor,
        currency: form.currency.toUpperCase(),
        resourcePayload: form.resourcePayload || null,
        ...(form.evm
          ? {
              evmPriceAtomic: Math.round(Number(form.evmPriceAtomic)),
              evmAsset: form.evmAsset || null,
              evmNetwork: form.evmNetwork || null,
              evmAssetName: form.evmAssetName || null,
              evmAssetVersion: form.evmAssetVersion || null,
              payTo: form.payTo || null,
            }
          : {}),
      });
      toast.success(t("offerCreated"));
      setOpen(false);
      onCreated();
    } catch (e: any) {
      setError(e?.message || t("offerFailed"));
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <Button type="button" size="sm" onClick={() => setOpen(true)}>
        {t("newOffer")}
      </Button>
    );
  }

  return (
    <Card className="p-4">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{t("offerWizardTitle")}</h3>
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
        <div className="grid gap-1.5">
          <Label htmlFor="offer-slug">{t("fieldSlug")}</Label>
          <Input
            id="offer-slug"
            placeholder="premium-data"
            value={form.slug}
            onChange={(e) => setForm((f) => ({ ...f, slug: e.target.value.toLowerCase() }))}
          />
          <p className="text-xs text-muted-foreground">{t("fieldSlugHint")}</p>
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="offer-title">{t("fieldTitle")}</Label>
          <Input
            id="offer-title"
            value={form.title}
            onChange={(e) => setForm((f) => ({ ...f, title: e.target.value }))}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="offer-price">{t("fieldPriceMinor")}</Label>
          <Input
            id="offer-price"
            inputMode="numeric"
            placeholder="500"
            value={form.priceMinor}
            onChange={(e) => setForm((f) => ({ ...f, priceMinor: e.target.value }))}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="offer-ccy">{t("fieldCurrency")}</Label>
          <Input
            id="offer-ccy"
            className="w-24"
            maxLength={3}
            value={form.currency}
            onChange={(e) => setForm((f) => ({ ...f, currency: e.target.value.toUpperCase() }))}
          />
        </div>
        <div className="grid gap-1.5 sm:col-span-2">
          <Label htmlFor="offer-desc">{t("fieldDescription")}</Label>
          <Input
            id="offer-desc"
            value={form.description}
            onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
          />
        </div>
        <div className="grid gap-1.5 sm:col-span-2">
          <Label htmlFor="offer-payload">{t("fieldResourcePayload")}</Label>
          <Textarea
            id="offer-payload"
            rows={3}
            className="font-mono text-xs"
            placeholder={tHC("hcAdminCommerceOfferForm.s1")}
            value={form.resourcePayload}
            onChange={(e) => setForm((f) => ({ ...f, resourcePayload: e.target.value }))}
          />
        </div>
        <label className="flex items-center gap-2 text-sm sm:col-span-2">
          <input
            type="checkbox"
            checked={form.evm}
            onChange={(e) => setForm((f) => ({ ...f, evm: e.target.checked }))}
          />
          {t("evmToggle")}
        </label>
        {form.evm && (
          <Fragment>
            <div className="grid gap-1.5">
              <Label htmlFor="offer-evm-price">{t("fieldEvmPrice")}</Label>
              <Input
                id="offer-evm-price"
                inputMode="numeric"
                value={form.evmPriceAtomic}
                onChange={(e) => setForm((f) => ({ ...f, evmPriceAtomic: e.target.value }))}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="offer-evm-asset">{t("fieldEvmAsset")}</Label>
              <Input
                id="offer-evm-asset"
                placeholder="0x8335…"
                value={form.evmAsset}
                onChange={(e) => setForm((f) => ({ ...f, evmAsset: e.target.value }))}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="offer-evm-network">{t("fieldEvmNetwork")}</Label>
              <Input
                id="offer-evm-network"
                placeholder="base"
                value={form.evmNetwork}
                onChange={(e) => setForm((f) => ({ ...f, evmNetwork: e.target.value }))}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="offer-payto">{t("fieldPayTo")}</Label>
              <Input
                id="offer-payto"
                placeholder="0x…"
                value={form.payTo}
                onChange={(e) => setForm((f) => ({ ...f, payTo: e.target.value }))}
              />
            </div>
          </Fragment>
        )}
      </div>
      {error && <div className="mt-3 text-sm text-destructive">{error}</div>}
      <div className="mt-4">
        <Button type="button" size="sm" disabled={busy} onClick={handleCreate}>
          {t("createOffer")}
        </Button>
      </div>
    </Card>
  );
}

