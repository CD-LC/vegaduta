"use client";

import { useEffect, useState } from "react";
import { api, type NodePollTrigger } from "../../lib/api";
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
 * HTTP polling trigger config (V482): the trigger node polls a URL on an
 * interval, dedupes items by the id path, and fires a run per new item - the
 * "new row / new email" trigger shape for apps that cannot push webhooks to a
 * self-hosted install. Independent of the node's inbound-channel binding: a
 * node can listen on a channel AND poll an endpoint.
 *
 * The Authorization field is the dominant real-world auth shape (Bearer/API
 * key); custom headers beyond that come through the REST API. Stored
 * AES-256-GCM server-side and write-only - GET only reports
 * {@code headersConfigured}.
 *
 * Writes need MANAGE_WORKFLOWS_ADMIN server-side (a poll fires unattended
 * forever - same trust tier as a schedule); non-admins see a read-only view.
 */
export default function TriggerPollConfig({
  workflowId,
  nodeId,
}: {
  workflowId: string;
  nodeId: string;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const { hasPermission } = useAuth();
  const canManage = hasPermission("MANAGE_WORKFLOWS_ADMIN");
  const [poll, setPoll] = useState<NodePollTrigger | null>(null);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({
    url: "",
    method: "GET",
    intervalSeconds: 300,
    itemsPath: "",
    idPath: "id",
    authorization: "",
    seedOnEnable: true,
  });

  useEffect(() => {
    let cancelled = false;
    api
      .getNodePollTrigger(workflowId, nodeId)
      .then((p) => {
        if (cancelled || !p) return;
        setPoll(p);
        if (p.configured) {
          setForm((f) => ({
            ...f,
            url: p.url || "",
            method: p.method || "GET",
            intervalSeconds: p.intervalSeconds || 300,
            itemsPath: p.itemsPath || "",
            idPath: p.idPath || "id",
            seedOnEnable: p.seedOnEnable,
            authorization: "", // stored encrypted; never readable back
          }));
        }
      })
      .catch((err) => console.warn("[Workflow] Poll trigger hydrate failed:", err));
    return () => {
      cancelled = true;
    };
  }, [workflowId, nodeId]);

  async function save() {
    setBusy(true);
    try {
      const headers: Record<string, string> = {};
      if (form.authorization.trim()) headers.Authorization = form.authorization.trim();
      const next = await api.setNodePollTrigger(workflowId, nodeId, {
        url: form.url.trim(),
        method: form.method,
        headers: Object.keys(headers).length ? headers : undefined,
        intervalSeconds: form.intervalSeconds,
        itemsPath: form.itemsPath.trim() || undefined,
        idPath: form.idPath.trim() || undefined,
        seedOnEnable: form.seedOnEnable,
      });
      setPoll(next);
      toast.success(t("pollSaved"));
    } catch (err) {
      toast.error(errorMessage(err, t("pollError")));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    try {
      setPoll(await api.deleteNodePollTrigger(workflowId, nodeId));
      setForm((f) => ({ ...f, url: "", authorization: "" }));
      toast.success(t("pollDisabled"));
    } catch (err) {
      toast.error(errorMessage(err, t("pollError")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="wf-trigger-box" style={{ flexDirection: "column", alignItems: "stretch" }}>
      <Label>{t("pollLabel")}</Label>
      <p className="ax-wf-empty">{t("pollHint")}</p>
      <Input
        value={form.url}
        onChange={(e) => setForm((f) => ({ ...f, url: e.target.value }))}
        placeholder={t("pollUrlPlaceholder")}
        disabled={busy || !canManage}
      />
      <div className="row" style={{ gap: "0.4rem" }}>
        <Select
          value={form.method}
          onChange={(e) => setForm((f) => ({ ...f, method: e.target.value }))}
          disabled={busy || !canManage}
          options={[
            { value: "GET", label: "GET" },
            { value: "POST", label: "POST" },
          ]}
        />
        <Input
          type="number"
          min={60}
          value={form.intervalSeconds}
          onChange={(e) => setForm((f) => ({ ...f, intervalSeconds: Number(e.target.value) || 300 }))}
          title={t("pollIntervalLabel")}
          disabled={busy || !canManage}
          style={{ width: "7rem" }}
        />
      </div>
      <div className="row" style={{ gap: "0.4rem" }}>
        <Input
          value={form.itemsPath}
          onChange={(e) => setForm((f) => ({ ...f, itemsPath: e.target.value }))}
          placeholder={t("pollItemsPathPlaceholder")}
          disabled={busy || !canManage}
        />
        <Input
          value={form.idPath}
          onChange={(e) => setForm((f) => ({ ...f, idPath: e.target.value }))}
          placeholder={t("pollIdPathPlaceholder")}
          disabled={busy || !canManage}
        />
      </div>
      <Input
        type="password"
        value={form.authorization}
        onChange={(e) => setForm((f) => ({ ...f, authorization: e.target.value }))}
        placeholder={poll?.headersConfigured ? t("pollAuthKeepPlaceholder") : t("pollAuthPlaceholder")}
        disabled={busy || !canManage}
      />
      <div className="row" style={{ gap: "0.4rem", alignItems: "center" }}>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={save}
          disabled={busy || !canManage || !form.url.trim()}
        >
          {busy ? t("working") : t("pollSave")}
        </Button>
        {poll?.configured && (
          <Button type="button" variant="outline" size="sm" onClick={remove} disabled={busy || !canManage}>
            {t("pollDisable")}
          </Button>
        )}
        {poll?.configured && poll?.enabled && <Badge variant="outline">{poll.lastStatus || t("pollActiveBadge")}</Badge>}
        {poll?.configured && poll?.consecutiveFailures > 0 && (
          <Badge variant="outline">{t("pollFailuresBadge", { count: poll.consecutiveFailures })}</Badge>
        )}
      </div>
    </div>
  );
}
