"use client";

import { useEffect, useState } from "react";
import { BULK_EMAIL_MAX_RECIPIENTS, BULK_EMAIL_MAX_PER_MINUTE, type BulkEmailSource } from "../../nodeDefs";
import { api } from "../../../../lib/api";
import Select from "../../../Select";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { ScopedExpressionField } from "../../ExpressionField";
import { TEXTAREA_CLASS } from "../shared";
import { type WorkflowNodeConfig } from "../types";

/**
 * Bulk Email node (WorkflowNodeType.BULK_EMAIL) - recipient source, the
 * {{var}} subject/body templates, and the send-safety knobs (throttle, cap,
 * skip-if-replied, unsubscribe link). The server clamps maxPerMinute and
 * maxRecipients (see BulkEmailNodeHandler), so the inputs clamp to the same
 * ceilings instead of showing a value the backend would rewrite.
 */
export function BulkEmailNodeConfig({
  config,
  setConfig,
}: {
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const source: BulkEmailSource = config.recipientsSource || "items";
  const [groups, setGroups] = useState<{ id: string; name: string }[]>([]);

  useEffect(() => {
    if (source !== "group") return undefined;
    let cancelled = false;
    api
      .listContactGroups()
      .then((list: { id: string; name: string }[] | null) => {
        if (!cancelled) setGroups(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        if (!cancelled) setGroups([]);
      });
    return () => {
      cancelled = true;
    };
  }, [source]);

  function numberPatch(raw: string, min: number, max: number): number | undefined {
    return raw === "" ? undefined : Math.max(min, Math.min(max, Number(raw)));
  }

  const bodyText = typeof config.body === "string" ? config.body : "";

  return (
    <>
      <p className="ax-wf-empty">{t("bulkEmailHint")}</p>

      <div className="field">
        <Label>{t("bulkEmailSourceLabel")}</Label>
        <Select
          data-testid="bulk-email-source-select"
          value={source}
          onChange={(e) => setConfig({ recipientsSource: e.target.value as BulkEmailSource })}
          options={[
            { value: "items", label: t("bulkEmailSourceItems") },
            { value: "group", label: t("bulkEmailSourceGroup") },
            { value: "static", label: t("bulkEmailSourceStatic") },
          ]}
        />
      </div>

      {source === "items" && (
        <div className="field">
          <div className="row" style={{ gap: "0.5rem" }}>
            <div style={{ flex: 1 }}>
              <Label>{t("bulkEmailItemsFieldLabel")}</Label>
              <Input
                type="text"
                value={config.itemsField || ""}
                placeholder={t("bulkEmailItemsFieldPlaceholder")}
                onChange={(e) => setConfig({ itemsField: e.target.value })}
              />
            </div>
            <div style={{ flex: 1 }}>
              <Label>{t("bulkEmailEmailFieldLabel")}</Label>
              <Input
                type="text"
                value={config.emailField || ""}
                placeholder="email"
                onChange={(e) => setConfig({ emailField: e.target.value })}
              />
            </div>
          </div>
          <p className="ax-wf-empty">{t("bulkEmailItemsHint")}</p>
        </div>
      )}

      {source === "group" && (
        <div className="field">
          <Label>{t("bulkEmailGroupLabel")}</Label>
          <Select
            data-testid="bulk-email-group-select"
            value={config.groupId || ""}
            onChange={(e) => setConfig({ groupId: e.target.value })}
            options={[
              { value: "", label: t("bulkEmailGroupPlaceholder") },
              ...groups.map((g) => ({ value: g.id, label: g.name })),
            ]}
          />
          <p className="ax-wf-empty">{t("bulkEmailGroupHint")}</p>
        </div>
      )}

      {source === "static" && (
        <div className="field">
          <Label>{t("bulkEmailStaticLabel")}</Label>
          <textarea
            rows={3}
            className={TEXTAREA_CLASS}
            placeholder={t("bulkEmailStaticPlaceholder")}
            value={config.staticRecipients || ""}
            onChange={(e) => setConfig({ staticRecipients: e.target.value })}
          />
        </div>
      )}

      <div className="field">
        <Label>{t("bulkEmailSubjectLabel")}</Label>
        <ScopedExpressionField
          field="subject"
          value={config.subject || ""}
          placeholder={t("bulkEmailSubjectPlaceholder")}
          onChange={(v) => setConfig({ subject: v })}
          ariaLabel={t("bulkEmailSubjectLabel")}
        />
      </div>

      <div className="field">
        <Label>{t("bulkEmailBodyLabel")}</Label>
        <ScopedExpressionField
          field="body"
          multiline
          placeholder={t("bulkEmailBodyPlaceholder")}
          value={bodyText}
          onChange={(v) => setConfig({ body: v })}
          ariaLabel={t("bulkEmailBodyLabel")}
        />
        <p className="ax-wf-empty">{t("bulkEmailBodyHint")}</p>
        <label
          className="row"
          style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)", marginTop: "0.5rem" }}
        >
          <input type="checkbox" checked={!!config.html} onChange={(e) => setConfig({ html: e.target.checked })} />
          {t("bulkEmailHtmlLabel")}
        </label>
      </div>

      <div className="field">
        <Label>{t("bulkEmailReplyToLabel")}</Label>
        <ScopedExpressionField
          field="replyTo"
          value={config.replyTo || ""}
          placeholder={t("bulkEmailReplyToPlaceholder")}
          onChange={(v) => setConfig({ replyTo: v })}
          ariaLabel={t("bulkEmailReplyToLabel")}
        />
        <p className="ax-wf-empty">{t("bulkEmailReplyToHint")}</p>
      </div>

      <div className="field">
        <div className="row" style={{ gap: "0.5rem" }}>
          <div style={{ flex: 1 }}>
            <Label>{t("bulkEmailMaxPerMinuteLabel")}</Label>
            <Input
              type="number"
              min={1}
              max={BULK_EMAIL_MAX_PER_MINUTE}
              value={config.maxPerMinute ?? ""}
              placeholder="60"
              onChange={(e) => setConfig({ maxPerMinute: numberPatch(e.target.value, 1, BULK_EMAIL_MAX_PER_MINUTE) })}
            />
          </div>
          <div style={{ flex: 1 }}>
            <Label>{t("bulkEmailMaxRecipientsLabel")}</Label>
            <Input
              type="number"
              min={1}
              max={BULK_EMAIL_MAX_RECIPIENTS}
              value={config.maxRecipients ?? ""}
              placeholder="100"
              onChange={(e) => setConfig({ maxRecipients: numberPatch(e.target.value, 1, BULK_EMAIL_MAX_RECIPIENTS) })}
            />
          </div>
        </div>
        <p className="ax-wf-empty">{t("bulkEmailThrottleHint")}</p>
      </div>

      <label
        className="row"
        style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)", marginTop: "0.5rem" }}
      >
        <input
          type="checkbox"
          checked={config.skipIfReplied === true}
          onChange={(e) => setConfig({ skipIfReplied: e.target.checked })}
        />
        {t("bulkEmailSkipIfRepliedLabel")}
      </label>
      <label
        className="row"
        style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)", marginTop: "0.5rem" }}
      >
        <input
          type="checkbox"
          checked={config.includeUnsubscribe !== false}
          onChange={(e) => setConfig({ includeUnsubscribe: e.target.checked })}
        />
        {t("bulkEmailIncludeUnsubscribeLabel")}
      </label>
    </>
  );
}

