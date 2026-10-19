"use client";

import { useEffect, useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { HTTP_METHODS, HTTP_BODY_TYPES, type HttpHeaderPair } from "../../nodeDefs";
import { api, type VaultSecret } from "../../../../lib/api";
import Select from "../../../Select";
import { Button } from "../../../ui/button";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { ScopedExpressionField } from "../../ExpressionField";
import { type WorkflowNodeConfig, type Translator } from "../types";

/**
 * One editable name/value list (headers, query parameters) for the HTTP
 * Request node. Rows are stored exactly as `[{name, value}]` - the shape
 * WorkflowNodeExecutor#pairsConfig reads back - and never re-keyed, so a
 * half-typed row (name but no value yet) survives every keystroke.
 */
function HttpPairList({
  t,
  rows,
  onChange,
  namePlaceholder,
  valuePlaceholder,
  addLabel,
  fieldPrefix,
}: {
  t: Translator;
  /** Config key these rows live under ("headers" | "query"): the expression field path of row i is prefix[i].value. */
  fieldPrefix: string;
  rows: HttpHeaderPair[];
  onChange: (rows: HttpHeaderPair[]) => void;
  namePlaceholder: string;
  valuePlaceholder: string;
  addLabel: string;
}) {
  function update(index: number, patch: Partial<HttpHeaderPair>) {
    onChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  }
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
      {rows.map((row, index) => (
        <div key={index} className="row" style={{ gap: "0.4rem", alignItems: "center" }}>
          <Input
            type="text"
            value={row.name || ""}
            onChange={(e) => update(index, { name: e.target.value })}
            placeholder={namePlaceholder}
            aria-label={t("pairNameAria")}
            style={{ flex: "0 0 38%" }}
          />
          <div style={{ flex: 1, minWidth: 0 }}>
            <ScopedExpressionField
              field={`${fieldPrefix}[${index}].value`}
              value={row.value || ""}
              onChange={(v) => update(index, { value: v })}
              placeholder={valuePlaceholder}
              ariaLabel={t("pairValueAria")}
            />
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={() => onChange(rows.filter((_, i) => i !== index))}
            title={t("removePair")}
            aria-label={t("removePair")}
          >
            <Trash2 size={14} />
          </Button>
        </div>
      ))}
      <div>
        <Button type="button" variant="outline" size="sm" onClick={() => onChange([...rows, { name: "", value: "" }])}>
          <Plus size={14} />
          {addLabel}
        </Button>
      </div>
    </div>
  );
}

/**
 * HTTP Request node config - method, URL, headers, query, body, response
 * handling. Mirrors the server contract in WorkflowNodeExecutor#executeHttpNode
 * (config keys: method, url, headers, query, bodyType, body, saveAs,
 * timeoutSeconds, failOnHttpError). Every text field accepts the Transform
 * node's "{{input}}" / "{{field}}" templating plus "{{input_json}}".
 *
 * Secrets: a header/query value may be a vault reference ("{{secret.NAME}}").
 * The picker below lists the vault's NAMES only (api.listSecrets never returns
 * values) and inserts the reference text; the plaintext is substituted inside
 * HttpRequestExecutor at run time and redacted out of every response and log
 * line, so nothing typed here should ever be a real key. A non-admin whose
 * list call 403s simply gets no picker - the reference can still be typed.
 */
export function HttpNodeConfig({
  config,
  setConfig,
}: {
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("httpNode");
  const [secrets, setSecrets] = useState<VaultSecret[]>([]);
  const method = (config.method || "GET").toUpperCase();
  const bodyType = config.bodyType || "none";
  const headers = Array.isArray(config.headers) ? config.headers : [];
  const query = Array.isArray(config.query) ? config.query : [];
  const methodOptions = HTTP_METHODS.map((value) => ({ value, label: value }));
  const bodyTypeOptions = HTTP_BODY_TYPES.map((value) => ({ value, label: t(`bodyType_${value}`) }));
  const secretOptions = secrets
    .filter((s) => s.status === "ACTIVE" && !s.expired)
    .map((s) => ({
      value: s.reference,
      label: `${s.name} (${s.scope === "USER" ? t("scopeUser") : t("scopeWorkspace")})`,
    }));

  useEffect(() => {
    let cancelled = false;
    api
      .listSecrets()
      .then((list) => {
        if (!cancelled) setSecrets(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        if (!cancelled) setSecrets([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  function insertSecretHeader(reference: string) {
    if (!reference) return;
    const hasAuthorization = headers.some((h) => (h.name || "").trim().toLowerCase() === "authorization");
    const row: HttpHeaderPair = hasAuthorization
      ? { name: "", value: reference }
      : { name: "Authorization", value: `Bearer ${reference}` };
    setConfig({ headers: [...headers, row] });
  }

  function bodyPlaceholder() {
    switch (bodyType) {
      case "json":
        return t("bodyPlaceholderJson");
      case "form":
        return t("bodyPlaceholderForm");
      default:
        return t("bodyPlaceholderText");
    }
  }

  return (
    <>
      <div className="row" style={{ gap: "0.5rem", alignItems: "flex-end" }}>
        <div className="field" style={{ flex: "0 0 32%", marginBottom: 0 }}>
          <Label>{t("methodLabel")}</Label>
          <Select value={method} onChange={(e) => setConfig({ method: e.target.value })} options={methodOptions} />
        </div>
        <div className="field" style={{ flex: 1, marginBottom: 0 }}>
          <Label>{t("urlLabel")}</Label>
          <ScopedExpressionField
            field="url"
            value={config.url || ""}
            onChange={(v) => setConfig({ url: v })}
            placeholder={t("urlPlaceholder")}
            ariaLabel={t("urlLabel")}
          />
        </div>
      </div>
      <p className="ax-wf-empty">
        {"{{input}}"}
        {t("templateHintInput")}
        {"{{name}}"}
        {t("templateHintFields")}
        {"{{input_json}}"}
        {t("templateHintJson")}
      </p>

      <div className="field">
        <Label>{t("headersLabel")}</Label>
        <HttpPairList
          t={t}
          fieldPrefix="headers"
          rows={headers}
          onChange={(rows) => setConfig({ headers: rows })}
          namePlaceholder={t("headerNamePlaceholder")}
          valuePlaceholder={t("headerValuePlaceholder")}
          addLabel={t("addHeader")}
        />
        {secretOptions.length > 0 ? (
          <div style={{ marginTop: "0.5rem" }}>
            <Select
              value=""
              onChange={(e) => insertSecretHeader(String(e.target.value || ""))}
              options={secretOptions}
              placeholder={t("insertSecret")}
              searchable
            />
          </div>
        ) : null}
        <p className="ax-wf-empty">
          {t("secretsHintPrefix")}
          {"{{secret.NAME}}"}
          {t("secretsHintSuffix")}{" "}
          <a href="/secrets" target="_blank" rel="noreferrer" style={{ textDecoration: "underline" }}>
            {t("secretsPageLink")}
          </a>
        </p>
      </div>

      <div className="field">
        <Label>{t("queryLabel")}</Label>
        <HttpPairList
          t={t}
          fieldPrefix="query"
          rows={query}
          onChange={(rows) => setConfig({ query: rows })}
          namePlaceholder={t("queryNamePlaceholder")}
          valuePlaceholder={t("queryValuePlaceholder")}
          addLabel={t("addQuery")}
        />
      </div>

      <div className="field">
        <Label>{t("bodyTypeLabel")}</Label>
        <Select value={bodyType} onChange={(e) => setConfig({ bodyType: e.target.value })} options={bodyTypeOptions} />
      </div>

      {bodyType !== "none" && (
        <div className="field">
          <Label>{t("bodyLabel")}</Label>
          <ScopedExpressionField
            field="body"
            multiline
            value={typeof config.body === "string" ? config.body : ""}
            onChange={(v) => setConfig({ body: v })}
            placeholder={bodyPlaceholder()}
            ariaLabel={t("bodyLabel")}
          />
          {bodyType === "form" && <p className="ax-wf-empty">{t("bodyHintForm")}</p>}
          {bodyType === "json" && <p className="ax-wf-empty">{t("bodyHintJson")}</p>}
        </div>
      )}

      <div className="field">
        <Label>{t("saveAsLabel")}</Label>
        <Input
          type="text"
          value={config.saveAs || ""}
          onChange={(e) => setConfig({ saveAs: e.target.value })}
          placeholder={t("saveAsPlaceholder")}
        />
        <p className="ax-wf-empty">{t("saveAsHint")}</p>
      </div>

      <div className="field">
        <Label>{t("timeoutLabel")}</Label>
        <Input
          type="number"
          min={1}
          max={30}
          value={config.timeoutSeconds ?? 15}
          onChange={(e) => {
            const n = Number(e.target.value);
            setConfig({ timeoutSeconds: Number.isFinite(n) && n > 0 ? Math.min(30, Math.round(n)) : 15 });
          }}
        />
      </div>

      <label
        className="row"
        style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)", marginTop: "0.5rem" }}
      >
        <input
          type="checkbox"
          checked={config.failOnHttpError !== false}
          onChange={(e) => setConfig({ failOnHttpError: e.target.checked })}
        />
        {t("failOnHttpErrorLabel")}
      </label>

      <p className="ax-wf-empty" style={{ marginTop: "0.5rem" }}>
        {t("ssrfNote")}
      </p>
    </>
  );
}

// ---------------------------------------------------------------------------
// Tier-3 node config sections.
// ---------------------------------------------------------------------------

