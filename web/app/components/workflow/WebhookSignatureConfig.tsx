"use client";

import { useEffect, useState } from "react";
import { api, type NodeWebhookSignature } from "../../lib/api";
import Select from "../Select";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useTranslations } from "../../lib/i18n";
import { useAuth } from "../AuthProvider";
import { toast } from "../../lib/toastBus";
import { errorMessage } from "../../lib/errors";

/**
 * Sender-signature config for a webhook trigger node (V482): the shared-secret
 * URL stays live, but the sender must also prove the per-node HMAC secret over
 * the raw request body - GitHub/Stripe/Slack presets or a generic
 * X-Signature-256 header. Secret is write-only; GET returns mode + configured.
 * Writes need MANAGE_WORKFLOWS_ADMIN server-side - non-admins see the state
 * but the controls stay disabled (AgentRlToggle precedent).
 */
export default function WebhookSignatureConfig({
  workflowId,
  nodeId,
}: {
  workflowId: string;
  nodeId: string;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const { hasPermission } = useAuth();
  const canManage = hasPermission("MANAGE_WORKFLOWS_ADMIN");
  const [signature, setSignature] = useState<NodeWebhookSignature | null>(null);
  const [sigMode, setSigMode] = useState("NONE");
  const [sigSecret, setSigSecret] = useState("");
  const [sigBusy, setSigBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getNodeWebhookSignature(workflowId, nodeId)
      .then((s) => {
        if (cancelled || !s) return;
        setSignature(s);
        setSigMode(s.mode || "NONE");
      })
      .catch((err) => console.warn("[Workflow] Webhook signature hydrate failed:", err));
    return () => {
      cancelled = true;
    };
  }, [workflowId, nodeId]);

  async function save() {
    setSigBusy(true);
    try {
      const next =
        sigMode === "NONE"
          ? await api.clearNodeWebhookSignature(workflowId, nodeId)
          : await api.setNodeWebhookSignature(workflowId, nodeId, sigMode, sigSecret.trim());
      setSignature(next);
      setSigSecret("");
      toast.success(t("signatureSaved"));
    } catch (err) {
      toast.error(errorMessage(err, t("signatureError")));
    } finally {
      setSigBusy(false);
    }
  }

  return (
    <>
      <Label>{t("signatureLabel")}</Label>
      <Select
        value={sigMode}
        onChange={(e) => setSigMode(e.target.value)}
        disabled={sigBusy || !canManage}
        options={[
          { value: "NONE", label: t("signatureNoneOption") },
          { value: "GITHUB", label: t("signatureGithubOption") },
          { value: "STRIPE", label: t("signatureStripeOption") },
          { value: "SLACK", label: t("signatureSlackOption") },
          { value: "HMAC_SHA256", label: t("signatureHmacOption") },
        ]}
      />
      {sigMode !== "NONE" && (
        <Input
          type="password"
          value={sigSecret}
          onChange={(e) => setSigSecret(e.target.value)}
          placeholder={
            signature?.configured ? t("signatureSecretKeepPlaceholder") : t("signatureSecretPlaceholder")
          }
          disabled={sigBusy || !canManage}
        />
      )}
      <div className="row" style={{ gap: "0.4rem" }}>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={save}
          disabled={sigBusy || !canManage || (sigMode !== "NONE" && !sigSecret.trim())}
        >
          {sigBusy ? t("working") : t("signatureSave")}
        </Button>
        {signature?.configured && <Badge variant="outline">{t("signatureConfiguredBadge")}</Badge>}
      </div>
      <p className="ax-wf-empty">{t("signatureHint")}</p>
    </>
  );
}
