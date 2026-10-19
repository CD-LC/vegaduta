"use client";

import { useEffect, useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import Select from "../Select";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useTranslations } from "../../lib/i18n";
import {
  FORM_FIELD_TYPES,
  FORM_MAX_FIELDS,
  emptyFormDef,
  formIssues,
  formUrl,
  isFormTrigger,
  nextFieldKey,
  parseOptions,
  readFormDef,
  type FormDef,
  type FormFieldDef,
  type FormFieldType,
} from "./workflowControl";

const TEXTAREA_CLASS =
  "flex w-full min-w-0 rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow] placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50";

type Props = {
  /** The Trigger node's config (triggerType + form live here). */
  config: any;
  setConfig: (patch: any) => void;
  /** The node's webhook token when its webhook is enabled; the form link is built from it. Null otherwise. */
  webhookToken: string | null;
  /** True when the webhook runs in OAuth2 mode - a hosted form needs the secret-token mode. */
  oauthMode: boolean;
};

/**
 * Hosted-form trigger editor, rendered inside the Trigger node's drawer section. Picks the trigger type
 * (webhook/channel vs. hosted form) and, for a form, edits its title, fields and thank-you behaviour. The form
 * is served at /forms/<webhook token> (the SAME per-node token the webhook trigger uses - no new credential), so
 * the link only appears once the node's webhook is switched on. Everything is written into the node's own
 * config (`triggerType`, `form`) and saved with the graph; core validates it again at save/activate.
 */
export default function FormTriggerEditor({ config, setConfig, webhookToken, oauthMode }: Props) {
  const t = useTranslations("workflowForms");
  const [copied, setCopied] = useState(false);
  const isForm = isFormTrigger(config);
  const def: FormDef = isForm ? readFormDef(config.form) : emptyFormDef();
  const issues = formIssues(def);
  const link = formUrl(typeof window !== "undefined" ? window.location.origin : undefined, webhookToken);

  function setForm(patch: Partial<FormDef>) {
    setConfig({ form: { ...def, ...patch } });
  }

  function patchField(index: number, patch: Partial<FormFieldDef>) {
    setForm({ fields: def.fields.map((f, i) => (i === index ? { ...f, ...patch } : f)) });
  }

  function addField() {
    if (def.fields.length >= FORM_MAX_FIELDS) return;
    const key = nextFieldKey(def.fields);
    setForm({ fields: [...def.fields, { key, label: "", type: "text", required: false }] });
  }

  function removeField(index: number) {
    setForm({ fields: def.fields.filter((_, i) => i !== index) });
  }

  function copyLink() {
    if (!link) return;
    void navigator.clipboard?.writeText(link);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  return (
    <div className="field" data-testid="form-trigger-editor">
      <Label>{t("triggerTypeLabel")}</Label>
      <Select
        value={isForm ? "form" : "webhook"}
        onChange={(e: any) => {
          if (e.target.value === "form") {
            setConfig({ triggerType: "form", form: isForm ? config.form : emptyFormDef() });
          } else {
            setConfig({ triggerType: undefined });
          }
        }}
        options={[
          { value: "webhook", label: t("triggerTypeWebhook") },
          { value: "form", label: t("triggerTypeForm") },
        ]}
      />

      {isForm && (
        <>
          <p className="ax-wf-empty">{t("formHint")}</p>
          {oauthMode && (
            <p className="ax-wf-empty" role="alert">
              {t("formOauthNotSupported")}
            </p>
          )}
          {!oauthMode && !link && <p className="ax-wf-empty">{t("formEnableWebhookFirst")}</p>}
          {!oauthMode && link && (
            <div className="field">
              <Label>{t("formUrlLabel")}</Label>
              <div className="row" style={{ gap: "0.4rem", alignItems: "center" }}>
                <code className="wf-trigger-url">{link}</code>
                <Button type="button" variant="outline" size="sm" onClick={copyLink}>
                  {copied ? t("formCopied") : t("formCopy")}
                </Button>
              </div>
            </div>
          )}

          <div className="field">
            <Label>{t("formTitleLabel")}</Label>
            <Input type="text" maxLength={120} value={def.title} onChange={(e) => setForm({ title: e.target.value })} />
          </div>
          <div className="field">
            <Label>{t("formDescriptionLabel")}</Label>
            <textarea
              rows={2}
              maxLength={1000}
              className={TEXTAREA_CLASS}
              value={def.description}
              onChange={(e) => setForm({ description: e.target.value })}
            />
          </div>
          <div className="field">
            <Label>{t("formSubmitLabelLabel")}</Label>
            <Input
              type="text"
              maxLength={40}
              value={def.submitLabel}
              onChange={(e) => setForm({ submitLabel: e.target.value })}
            />
          </div>
          <div className="field">
            <Label>{t("formSuccessMessageLabel")}</Label>
            <Input
              type="text"
              maxLength={500}
              value={def.successMessage}
              onChange={(e) => setForm({ successMessage: e.target.value })}
            />
          </div>
          <div className="field">
            <Label>{t("formRedirectLabel")}</Label>
            <Input
              type="url"
              maxLength={2000}
              value={def.redirectUrl}
              placeholder="https://"
              aria-invalid={issues.redirect}
              onChange={(e) => setForm({ redirectUrl: e.target.value.trim() })}
            />
            {issues.redirect && (
              <p className="ax-wf-empty" role="alert">
                {t("formRedirectIssue")}
              </p>
            )}
          </div>

          <div className="field">
            <Label>{t("formFieldsHeading")}</Label>
            {def.fields.length === 0 && <p className="ax-wf-empty">{t("formNoFields")}</p>}
            {def.fields.map((field, i) => (
              <FieldRow
                key={i}
                field={field}
                issue={issues.keys[i]}
                onChange={(patch) => patchField(i, patch)}
                onRemove={() => removeField(i)}
              />
            ))}
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={addField}
              disabled={def.fields.length >= FORM_MAX_FIELDS}
            >
              <Plus size={14} aria-hidden="true" /> {t("formAddField")}
            </Button>
          </div>
        </>
      )}
    </div>
  );
}

function FieldRow({
  field,
  issue,
  onChange,
  onRemove,
}: {
  field: FormFieldDef;
  issue?: "invalid" | "duplicate";
  onChange: (patch: Partial<FormFieldDef>) => void;
  onRemove: () => void;
}) {
  const t = useTranslations("workflowForms");
  const numeric = (v: string) => (v.trim() === "" ? undefined : Number(v));
  return (
    <div
      style={{
        border: "1px solid var(--surface-border)",
        borderRadius: "0.5rem",
        padding: "0.5rem",
        marginBottom: "0.5rem",
        display: "flex",
        flexDirection: "column",
        gap: "0.35rem",
      }}
    >
      <div className="row" style={{ gap: "0.4rem" }}>
        <Input
          type="text"
          value={field.key}
          maxLength={64}
          aria-label={t("formFieldKeyLabel")}
          aria-invalid={!!issue}
          placeholder={t("formFieldKeyLabel")}
          onChange={(e) => onChange({ key: e.target.value.trim() })}
        />
        <Button type="button" variant="ghost" size="icon" onClick={onRemove} aria-label={t("formRemoveField")}>
          <Trash2 size={14} aria-hidden="true" />
        </Button>
      </div>
      {issue && (
        <p className="ax-wf-empty" role="alert">
          {issue === "duplicate" ? t("formKeyIssueDuplicate") : t("formKeyIssueInvalid")}
        </p>
      )}
      <Input
        type="text"
        value={field.label}
        maxLength={200}
        aria-label={t("formFieldLabelLabel")}
        placeholder={t("formFieldLabelLabel")}
        onChange={(e) => onChange({ label: e.target.value })}
      />
      <div className="row" style={{ gap: "0.4rem", alignItems: "center" }}>
        <Select
          value={field.type}
          onChange={(e: any) => onChange({ type: e.target.value as FormFieldType })}
          options={FORM_FIELD_TYPES.map((type) => ({ value: type, label: t(`fieldType_${type}`) }))}
        />
        <label
          className="row"
          style={{ gap: "0.3rem", fontSize: "0.8rem", color: "var(--text-secondary)", whiteSpace: "nowrap" }}
        >
          <input type="checkbox" checked={field.required} onChange={(e) => onChange({ required: e.target.checked })} />
          {t("formFieldRequiredLabel")}
        </label>
      </div>
      {(field.type === "text" || field.type === "long_text" || field.type === "email" || field.type === "number") && (
        <Input
          type="text"
          value={field.placeholder || ""}
          maxLength={200}
          aria-label={t("formFieldPlaceholderLabel")}
          placeholder={t("formFieldPlaceholderLabel")}
          onChange={(e) => onChange({ placeholder: e.target.value || undefined })}
        />
      )}
      {(field.type === "text" || field.type === "long_text") && (
        <div className="field">
          <Label>{t("formFieldMaxLengthLabel")}</Label>
          <Input
            type="number"
            min={1}
            max={field.type === "long_text" ? 10000 : 1000}
            value={field.maxLength ?? ""}
            onChange={(e) => onChange({ maxLength: numeric(e.target.value) })}
          />
        </div>
      )}
      {field.type === "number" && (
        <div className="row" style={{ gap: "0.4rem" }}>
          <div className="field" style={{ flex: 1 }}>
            <Label>{t("formFieldMinLabel")}</Label>
            <Input type="number" value={field.min ?? ""} onChange={(e) => onChange({ min: numeric(e.target.value) })} />
          </div>
          <div className="field" style={{ flex: 1 }}>
            <Label>{t("formFieldMaxLabel")}</Label>
            <Input type="number" value={field.max ?? ""} onChange={(e) => onChange({ max: numeric(e.target.value) })} />
          </div>
        </div>
      )}
      {field.type === "select" && (
        <div className="field">
          <Label>{t("formFieldOptionsLabel")}</Label>
          <OptionsTextarea options={field.options || []} onChange={(options) => onChange({ options })} />
        </div>
      )}
    </div>
  );
}

/**
 * One option per line. Keeps its own raw text so a trailing newline survives while typing (the stored list
 * drops blanks, which would otherwise swallow every Enter), and commits the parsed list on each change.
 */
function OptionsTextarea({ options, onChange }: { options: string[]; onChange: (options: string[]) => void }) {
  const [text, setText] = useState(options.join("\n"));
  // A different field slid into this row (one above was removed): show its options, not the previous row's text.
  useEffect(() => {
    if (JSON.stringify(parseOptions(text)) !== JSON.stringify(options)) setText(options.join("\n"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [options]);
  return (
    <textarea
      rows={3}
      className={TEXTAREA_CLASS}
      value={text}
      onChange={(e) => {
        setText(e.target.value);
        onChange(parseOptions(e.target.value));
      }}
    />
  );
}
