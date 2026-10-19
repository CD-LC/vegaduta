"use client";

// One mandate row (status actions + event trail) for the agentic-commerce console.

import { useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { api } from "../../lib/api";
import type { CommerceEvent, CommerceMandate } from "../../lib/api/agentTrust";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import { PagedList } from "./listKit";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { fmtTime, statusBadge } from "./commerceFormat";

// ---- Mandate row: status actions + event trail -------------------------------

export function MandateRow({ m, onChanged }: { m: CommerceMandate; onChanged: () => void }) {
  const t = useTranslations("adminCommercePage");
  const tu = useTranslations("adminCommerceUi");
  const [events, setEvents] = useState<CommerceEvent[] | null>(null);
  const [cartForm, setCartForm] = useState(false);
  const [cartItemsJson, setCartItemsJson] = useState(
    '[{"sku":"item-1","name":"Item","qty":1,"unit_price_minor":1000}]'
  );
  const [busy, setBusy] = useState(false);

  const act = async (fn: () => Promise<any>, okKey: string) => {
    setBusy(true);
    try {
      await fn();
      toast.success(t(okKey));
      onChanged();
    } catch (e: any) {
      toast.error(e?.message || t("actionFailed"));
    } finally {
      setBusy(false);
    }
  };

  const toggleEvents = async () => {
    if (events) {
      setEvents(null);
      return;
    }
    try {
      const rows = await api.listCommerceEvents(m.id);
      setEvents(Array.isArray(rows) ? rows : []);
    } catch {
      setEvents([]);
    }
  };

  const createCart = async () => {
    let items;
    try {
      items = JSON.parse(cartItemsJson);
      if (!Array.isArray(items) || items.length === 0) throw new Error("empty");
    } catch {
      toast.error(t("cartItemsInvalid"));
      return;
    }
    await act(() => api.createCartMandate(m.id, { items }), "cartCreated");
    setCartForm(false);
  };

  return (
    <div className="rounded-lg border border-border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="outline">{m.kind}</Badge>
        {statusBadge(m.status)}
        <span className="font-mono text-xs">
          {m.kind === "INTENT"
            ? t("maxRow", { max: m.maxTotalMinor ?? 0, ccy: m.currency })
            : t("cartRow", { total: m.cartTotalMinor ?? 0, ccy: m.currency, payee: m.payee || "—" })}
        </span>
        {m.signatureValid === true && <Badge variant="default">{t("signedBadge")}</Badge>}
        <span className="ml-auto text-xs text-muted-foreground">{fmtTime(m.createdAt)}</span>
        <Button type="button" variant="ghost" size="sm" onClick={toggleEvents}>
          {events ? <ChevronUp size={14} aria-hidden="true" /> : <ChevronDown size={14} aria-hidden="true" />}
          {t("events")}
        </Button>
        {m.kind === "INTENT" && m.status === "ACTIVE" && (
          <Button type="button" variant="outline" size="sm" onClick={() => setCartForm((v) => !v)}>
            {t("newCart")}
          </Button>
        )}
        {m.kind === "CART" && m.status === "ACTIVE" && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => act(() => api.authorizeCart(m.id), "authorizeDone")}
          >
            {t("authorize")}
          </Button>
        )}
        {m.kind === "CART" && m.status === "AUTHORIZED" && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => act(() => api.captureCart(m.id), "captureDone")}
          >
            {t("capture")}
          </Button>
        )}
        {m.kind === "CART" && m.status === "CAPTURED" && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => act(() => api.refundCart(m.id), "refundDone")}
          >
            {t("refund")}
          </Button>
        )}
        {["ACTIVE", "AUTHORIZED"].includes(m.status) && (
          <Button
            type="button"
            variant="destructive"
            size="sm"
            disabled={busy}
            onClick={() => act(() => api.revokeCommerceMandate(m.id), "revokeDone")}
          >
            {t("revoke")}
          </Button>
        )}
      </div>
      {m.intentText && <p className="mt-1 text-xs text-muted-foreground">{m.intentText}</p>}
      {m.canonicalHashSha256 && (
        <div className="mt-1 text-xs text-muted-foreground">
          hash <code className="break-all font-mono">{m.canonicalHashSha256}</code>
        </div>
      )}
      {cartForm && (
        <div className="mt-2 grid gap-2 rounded-md border border-border p-2">
          <Label htmlFor={`cart-items-${m.id}`}>{t("cartItemsLabel")}</Label>
          <Textarea
            id={`cart-items-${m.id}`}
            rows={3}
            className="font-mono text-xs"
            value={cartItemsJson}
            onChange={(e) => setCartItemsJson(e.target.value)}
          />
          <div className="flex gap-2">
            <Button type="button" size="sm" disabled={busy} onClick={createCart}>
              {t("createCart")}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setCartForm(false)}>
              {t("cancel")}
            </Button>
          </div>
        </div>
      )}
      {events && (
        <div className="mt-2">
          {events.length === 0 ? (
            <p className="text-xs text-muted-foreground">{t("eventsEmpty")}</p>
          ) : (
            <PagedList
              items={events}
              itemLabel={tu("itemEvents")}
              className="grid gap-1 text-xs"
              renderItem={(ev) => (
                <div key={ev.id} className="flex flex-wrap gap-2">
                  <span className="text-muted-foreground">{fmtTime(ev.createdAt)}</span>
                  <Badge variant="outline">{ev.eventType}</Badge>
                  <span className="text-muted-foreground">{ev.actor || ""}</span>
                </div>
              )}
            />
          )}
        </div>
      )}
    </div>
  );
}
