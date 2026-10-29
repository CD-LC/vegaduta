"use client";

import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useParams } from "next/navigation";
import { Loader2 } from "lucide-react";
import { Button } from "../../components/ui/button";
import { usePageReady } from "../../components/RouteTransition";
import { useTranslations } from "../../lib/i18n";
import { loadWorkflowForm, submitWorkflowForm, type FormErrorCode } from "./api";
import {
  initialValues,
  toPayload,
  validateValues,
  type FieldErrorCode,
  type FormValues,
  type PublicField,
  type PublicForm,
} from "./formModel";

// /forms/<token> - the PUBLIC page of a workflow Trigger node configured as a hosted form. Standalone (no
// AppShell, no auth): the visitor is a stranger with a link, and the opaque token is the only credential. It
// renders the schema the server sends, pre-checks it for a faster round trip, and POSTs the values; core validates
// every field again, applies a honeypot, per-token and per-IP limits, and starts the run. Strings: one namespace,
// "workflowForms". Accessible by construction: real <label>s, aria-invalid/aria-describedby on errors, a status
// region for the outcome, 16px inputs (no iOS zoom) and a single column that works at phone width.

const INPUT_CLASS =
  "w-full min-h-11 rounded-md border bg-background px-3 py-2 text-base text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60";

type Phase = "loading" | "ready" | "unavailable" | "busy" | "done";

export default function HostedWorkflowFormPage() {
  usePageReady(true);
  const t = useTranslations("workflowForms");
  const params = useParams();
  const raw = params?.token;
  const token = typeof raw === "string" ? raw : Array.isArray(raw) ? raw[0] || "" : "";

  const [phase, setPhase] = useState<Phase>("loading");
  const [form, setForm] = useState<PublicForm | null>(null);
  const [values, setValues] = useState<FormValues>({});
  const [honeypot, setHoneypot] = useState("");
  const [errors, setErrors] = useState<Record<string, FieldErrorCode>>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [doneMessage, setDoneMessage] = useState<string | null>(null);
  const [redirecting, setRedirecting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const result = await loadWorkflowForm(token);
      if (cancelled) return;
      if (result.ok && result.form) {
        setForm(result.form);
        setValues(initialValues(result.form.fields));
        setPhase("ready");
      } else {
        setPhase(result.error === "not_found" ? "unavailable" : "busy");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token]);

  function setValue(key: string, value: string | boolean) {
    setValues((prev) => ({ ...prev, [key]: value }));
    if (errors[key]) {
      setErrors((prev) => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
  }

  function noticeFor(error: FormErrorCode | "invalid" | undefined): string {
    if (error === "rate_limited") return t("pageRateLimited");
    if (error === "too_large") return t("pageTooLarge");
    return t("pageBusy");
  }

  function focusFirstInvalid(fields: PublicField[], found: Record<string, FieldErrorCode>) {
    const first = fields.find((f) => found[f.key]);
    if (first) document.getElementById(`wf-field-${first.key}`)?.focus();
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form || submitting) return;
    setNotice(null);
    const local = validateValues(form.fields, values);
    if (Object.keys(local).length > 0) {
      setErrors(local);
      setNotice(t("pageFixErrors"));
      focusFirstInvalid(form.fields, local);
      return;
    }
    setSubmitting(true);
    const result = await submitWorkflowForm(token, toPayload(form.fields, values, form.honeypotField, honeypot));
    setSubmitting(false);
    if (result.ok) {
      setDoneMessage(result.message || null);
      setPhase("done");
      if (result.redirectUrl) {
        setRedirecting(true);
        const target = result.redirectUrl;
        setTimeout(() => window.location.assign(target), 1500);
      }
      return;
    }
    if (result.error === "invalid" && result.fieldErrors) {
      setErrors(result.fieldErrors);
      setNotice(t("pageFixErrors"));
      focusFirstInvalid(form.fields, result.fieldErrors);
      return;
    }
    if (result.error === "not_found") {
      setPhase("unavailable");
      return;
    }
    setNotice(noticeFor(result.error));
  }

  const title = form?.title || t("pageDefaultTitle");

  return (
    <main className="vd-pub relative flex min-h-screen w-full flex-col items-center bg-background px-4 py-8 text-foreground antialiased sm:py-12">
      <div className="w-full max-w-xl">
        {phase === "loading" && (
          <div className="flex flex-col items-center gap-3 py-24 text-center" role="status">
            <Loader2 className="size-6 animate-spin text-primary" aria-hidden="true" />
            <p className="text-sm text-muted-foreground">{t("pageLoading")}</p>
          </div>
        )}

        {phase === "unavailable" && (
          <section className="rounded-xl border border-border bg-card p-6 text-center" role="alert">
            <h1 className="text-xl font-semibold">{t("pageUnavailableTitle")}</h1>
            <p className="mt-2 text-sm text-muted-foreground">{t("pageUnavailableBody")}</p>
          </section>
        )}

        {phase === "busy" && (
          <section className="rounded-xl border border-border bg-card p-6 text-center" role="alert">
            <p className="text-sm text-muted-foreground">{t("pageBusy")}</p>
          </section>
        )}

        {phase === "done" && (
          <section className="rounded-xl border border-border bg-card p-6 text-center" role="status">
            <h1 className="text-xl font-semibold">{t("pageThanksTitle")}</h1>
            <p className="mt-2 whitespace-pre-wrap text-sm text-muted-foreground">
              {doneMessage || t("pageThanksBody")}
            </p>
            {redirecting && <p className="mt-3 text-xs text-muted-foreground">{t("pageRedirecting")}</p>}
            {!redirecting && form && (
              <Button
                type="button"
                variant="outline"
                className="mt-4"
                onClick={() => {
                  setValues(initialValues(form.fields));
                  setErrors({});
                  setNotice(null);
                  setPhase("ready");
                }}
              >
                {t("pageSendAnother")}
              </Button>
            )}
          </section>
        )}

        {phase === "ready" && form && (
          <form
            onSubmit={(e) => void onSubmit(e)}
            noValidate
            className="rounded-xl border border-border bg-card p-5 sm:p-6"
            aria-labelledby="wf-form-title"
          >
            <h1 id="wf-form-title" className="text-xl font-semibold tracking-tight">
              {title}
            </h1>
            {form.description && (
              <p className="mt-2 whitespace-pre-wrap text-sm text-muted-foreground">{form.description}</p>
            )}

            <div className="mt-5 flex flex-col gap-5">
              {form.fields.map((field) => (
                <FieldControl
                  key={field.key}
                  field={field}
                  value={values[field.key]}
                  error={errors[field.key]}
                  disabled={submitting}
                  onChange={(v) => setValue(field.key, v)}
                />
              ))}
            </div>

            {/* Honeypot: off-screen (not display:none, which some bots skip), skipped by keyboard and screen
                readers. A person never fills it; a bot that does gets a normal-looking success and no run. */}
            <div
              aria-hidden="true"
              style={{ position: "absolute", left: "-10000px", top: "auto", width: 1, height: 1, overflow: "hidden" }}
            >
              <input
                type="text"
                name={form.honeypotField}
                tabIndex={-1}
                autoComplete="off"
                value={honeypot}
                onChange={(e) => setHoneypot(e.target.value)}
              />
            </div>

            {notice && (
              <p className="mt-5 text-sm text-destructive" role="alert">
                {notice}
              </p>
            )}

            <Button type="submit" className="mt-6 min-h-11 w-full sm:w-auto" disabled={submitting}>
              {submitting ? t("pageSubmitting") : form.submitLabel || t("pageSubmit")}
            </Button>
          </form>
        )}
      </div>
    </main>
  );
}

function FieldControl({
  field,
  value,
  error,
  disabled,
  onChange,
}: {
  field: PublicField;
  value: string | boolean;
  error?: FieldErrorCode;
  disabled: boolean;
  onChange: (value: string | boolean) => void;
}) {
  const t = useTranslations("workflowForms");
  const id = `wf-field-${field.key}`;
  const errorId = `${id}-error`;
  const common = {
    id,
    name: field.key,
    disabled,
    "aria-invalid": error ? true : undefined,
    "aria-describedby": error ? errorId : undefined,
    "aria-required": field.required || undefined,
  } as const;
  const cls = `${INPUT_CLASS} ${error ? "border-destructive" : "border-border"}`;
  const text = typeof value === "string" ? value : "";

  const label = (
    <label htmlFor={id} className="text-sm font-medium">
      {field.label}
      {field.required && (
        <span className="ms-1 text-destructive" aria-hidden="true">
          *
        </span>
      )}
      {field.required && <span className="sr-only"> ({t("pageRequired")})</span>}
    </label>
  );

  const errorLine = error ? (
    <p id={errorId} className="text-sm text-destructive" role="alert">
      {t(`error_${error}`)}
    </p>
  ) : null;

  if (field.type === "checkbox") {
    return (
      <div className="flex flex-col gap-1.5">
        <div className="flex items-start gap-3">
          <input
            {...common}
            type="checkbox"
            className="mt-0.5 size-5 shrink-0 accent-primary"
            checked={value === true}
            onChange={(e) => onChange(e.target.checked)}
          />
          {label}
        </div>
        {errorLine}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1.5">
      {label}
      {field.type === "long_text" ? (
        <textarea
          {...common}
          rows={5}
          className={cls}
          maxLength={field.maxLength ?? undefined}
          placeholder={field.placeholder || undefined}
          value={text}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : field.type === "select" ? (
        <select {...common} className={cls} value={text} onChange={(e) => onChange(e.target.value)}>
          <option value="">{t("pageChoose")}</option>
          {field.options.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      ) : (
        <input
          {...common}
          className={cls}
          type={
            field.type === "number"
              ? "number"
              : field.type === "email"
                ? "email"
                : field.type === "date"
                  ? "date"
                  : "text"
          }
          inputMode={field.type === "number" ? "decimal" : undefined}
          autoComplete={field.type === "email" ? "email" : "off"}
          maxLength={field.type === "text" ? (field.maxLength ?? undefined) : undefined}
          min={field.type === "number" && field.min != null ? field.min : undefined}
          max={field.type === "number" && field.max != null ? field.max : undefined}
          step={field.type === "number" ? "any" : undefined}
          placeholder={field.placeholder || undefined}
          value={text}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
      {errorLine}
    </div>
  );
}
