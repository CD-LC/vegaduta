"use client";

// Top of the node config drawer: says whether this node's output is pinned,
// and offers View / Edit / Unpin. For a node with no pin it offers the two ways
// to make one that do not start from a run result: paste JSON, and (trigger
// nodes) "Pin last received event" from the newest run the canvas knows about.
// Renders nothing without a PinsProvider or when the core has no pins route.

import { useContext, useState } from "react";
import { Pin } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { formatPinSize, formatPinTime, pinSourceKey } from "./pinModel";
import { usePinsContext, type PinsContextValue } from "./PinsContext";
import { StepTestContext } from "./StepTestSlot";
import PinOutputButton from "./PinOutputButton";
import "./pins.css";

type Translator = (key: string, values?: Record<string, string | number>) => string;

export default function PinBanner({ nodeId, nodeType }: { nodeId: string; nodeType?: string }) {
  const ctx = usePinsContext();
  if (!ctx || !ctx.loaded) return null;
  return <PinBannerInner ctx={ctx} nodeId={nodeId} nodeType={nodeType} />;
}

function PinBannerInner({ ctx, nodeId, nodeType }: { ctx: PinsContextValue; nodeId: string; nodeType?: string }) {
  const t = useTranslations("workflowCanvas") as Translator;
  const stepCtx = useContext(StepTestContext);
  const [busy, setBusy] = useState(false);

  const pin = ctx.pins[nodeId];
  const nodeLabel = ctx.labelOf(nodeId) || nodeId;

  if (!pin) {
    if (!ctx.canEdit) return null;
    // Trigger nodes never run on their own, so the newest received event is the only realistic sample.
    const sample = nodeType === "trigger" ? stepCtx?.lastIO[nodeId]?.output : undefined;
    return (
      <div className="wfx-pin-hint" data-testid="pin-hint">
        <button type="button" className="wfx-pin-link" onClick={() => ctx.openEditor({ nodeId })}>
          <Pin size={12} aria-hidden="true" />
          {t("pinManual")}
        </button>
        {sample ? (
          <PinOutputButton nodeId={nodeId} data={sample} source="trigger_sample" label="output" />
        ) : null}
      </div>
    );
  }

  const metadataOnly = pin.data == null;
  const who = pin.pinnedBy ? t("pinBannerBy", { who: pin.pinnedBy }) : "";

  return (
    <section className="wfx-pin-banner" aria-label={t("pinBannerTitle")} data-testid="pin-banner">
      <div className="wfx-pin-banner-head">
        <Pin size={13} fill="currentColor" aria-hidden="true" />
        <strong>{t("pinBannerTitle")}</strong>
      </div>
      <p className="wfx-pin-banner-text">{t("pinBannerBody")}</p>
      <p className="wfx-pin-banner-meta">
        {t("pinBannerMeta", {
          source: t(pinSourceKey(pin.source)),
          when: formatPinTime(pin.pinnedAt) || "-",
          size: formatPinSize(pin.sizeChars) || "-",
        })}
        {who ? ` ${who}` : ""}
      </p>
      {metadataOnly ? (
        <p className="wfx-pin-banner-note" role="note">
          {t("pinBannerMetadataOnly")}
        </p>
      ) : null}
      {!metadataOnly ? (
        <div className="wfx-pin-banner-actions">
          <button type="button" className="wfx-pin-link" onClick={() => ctx.openEditor({ nodeId, viewOnly: true })}>
            {t("pinView")}
          </button>
          {ctx.canEdit ? (
            <>
              <button type="button" className="wfx-pin-link" onClick={() => ctx.openEditor({ nodeId })}>
                {t("pinEdit")}
              </button>
              <button
                type="button"
                className="wfx-pin-link wfx-pin-link-danger"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  await ctx.unpin(nodeId);
                  setBusy(false);
                }}
                aria-label={t("pinUnpinNode", { node: nodeLabel })}
              >
                {t("pinUnpin")}
              </button>
            </>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
