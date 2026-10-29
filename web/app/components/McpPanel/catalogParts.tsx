"use client";

import { useState } from "react";
import { ExternalLink, BookOpen, X, Plus } from "lucide-react";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Card, CardContent } from "../ui/card";
import { credentialGuideFor, type McpCredentialGuideField } from "../../lib/credentialGuides";
import { useTranslations } from "../../lib/i18n";
import Link from "next/link";
import { placeholderVars } from "./helpers";
import { McpCredentialGuideAlert, isSecretCredentialField } from "./parts";

/* eslint-disable @typescript-eslint/no-explicit-any */
// The warn-before-enable step (product owner, 2026-07-25: "warn the customer
// BEFORE enabling, and test before agents can use it"). Shown in the
// inspector when an OFF server's toggle/Enable is clicked, instead of
// flipping silently. Includes the credential prompt: the read DTO never
// echoes env (it's the secret), so we can't know whether entries already
// exist - the form is always offered for dynamic servers and never blocks
// ("Skip — no credentials needed" is a first-class path, many servers need
// nothing).
export function EnableConfirmCard({
  server,
  busy,
  usageGated,
  onConfirm,
  onCancel,
}: {
  server: any;
  busy: any;
  usageGated: boolean;
  onConfirm: any;
  onCancel: any;
}) {
    const tHC = useTranslations("hcComponentsMcpPanelCatalogParts");
  const guide = credentialGuideFor(server.name);
  const knownFields: McpCredentialGuideField[] = guide?.fields || [];
  const hasKnownFields = knownFields.length > 0;

  // Known-field servers (atlassian, github, slack, ...) get one labeled row
  // per documented field (value only - the key is fixed to the real field
  // name) plus optional freeform "extra" rows for anything beyond what's
  // documented (e.g. an optional Atlassian host-URL var). Anything without a
  // guide/fields keeps the original generic freeform key+value UI unchanged.
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [rows, setRows] = useState<{ key: string; value: string }[]>(hasKnownFields ? [] : [{ key: "", value: "" }]);
  const isRemote = server.type === "SSE" || server.type === "STREAMABLE_HTTP";
  const isDynamic = Boolean(server.command || server.url);
  const entries = hasKnownFields
    ? [
        ...knownFields
          .filter((f) => (fieldValues[f.name] || "").trim())
          .map((f) => ({ key: f.name, value: fieldValues[f.name] })),
        ...rows.filter((r) => r.key.trim()),
      ]
    : rows.filter((r) => r.key.trim());

  function setRow(i: number, field: "key" | "value", v: string) {
    setRows((prev) => prev.map((r, idx) => (idx === i ? { ...r, [field]: v } : r)));
  }

  const confirmLabel = busy
    ? "Enabling…"
    : !isDynamic
      ? "Enable server"
      : entries.length > 0
        ? "Save credentials & enable"
        : "Skip — no credentials needed";

  return (
    <Card className="border-[color-mix(in_srgb,var(--state-warning)_40%,transparent)]">
      <CardContent className="space-y-3 pt-6">
        <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tHC("hcComponentsMcpPanelCatalogParts.s6")}</div>
        <p className="text-sm text-muted-foreground">
          {tHC("hcComponentsMcpPanelCatalogParts.s5")}{server.name}{tHC("hcComponentsMcpPanelCatalogParts.s4")}
          {usageGated
            ? " It won't be used by your agents until it passes a connection test (or you mark it trusted)."
            : ""}
        </p>
        <Link
          href="/help/docs#tools"
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex w-fit items-center gap-1 text-xs font-medium text-primary hover:underline"
        >
          <BookOpen size={12} aria-hidden="true" /> {tHC("hcComponentsMcpPanelCatalogParts.s3")}
        </Link>
        {isDynamic && (
          <div className="flex flex-col gap-2">
            {/* First-contact credential guide, right where the fields are -
             * #wire-credential-guides. */}
            <McpCredentialGuideAlert name={server.name} />
            <div className="text-xs font-semibold">{tHC("hcComponentsMcpPanelCatalogParts.s2")}</div>
            <p className="text-xs text-muted-foreground">
              {isRemote
                ? "Entries are sent as HTTP headers — e.g. Authorization: Bearer <token>."
                : "Entries are passed as environment variables to the server process (API keys etc.)."}
              {" Anything you add here replaces this server's previously stored credentials."}
            </p>
            {hasKnownFields && (
              <div className="flex flex-col gap-3">
                {knownFields.map((field) => {
                  const fieldId = `enable-confirm-${server.name}-${field.name}`;
                  return (
                    <div key={field.name} className="flex flex-col gap-1">
                      <Label htmlFor={fieldId} className="text-xs font-medium">
                        {field.name}
                        {field.required ? <span className="text-destructive"> *</span> : null}
                      </Label>
                      {field.description && <p className="text-xs text-muted-foreground">{field.description}</p>}
                      <Input
                        id={fieldId}
                        type={isSecretCredentialField(field) ? "password" : "text"}
                        placeholder="Value"
                        value={fieldValues[field.name] || ""}
                        onChange={(e) => setFieldValues((prev) => ({ ...prev, [field.name]: e.target.value }))}
                      />
                    </div>
                  );
                })}
              </div>
            )}
            {rows.map((row, i) => (
              <div key={i} className="flex items-center gap-2">
                <Input
                  type="text"
                  placeholder={
                    hasKnownFields
                      ? "Extra header/variable name"
                      : isRemote
                        ? "Header name (e.g. Authorization)"
                        : "Variable name (e.g. API_KEY)"
                  }
                  value={row.key}
                  onChange={(e) => setRow(i, "key", e.target.value)}
                  className="flex-1"
                />
                <Input
                  type={/token|key|secret|auth|password/i.test(row.key) ? "password" : "text"}
                  placeholder="Value"
                  value={row.value}
                  onChange={(e) => setRow(i, "value", e.target.value)}
                  className="flex-1"
                />
                {(hasKnownFields || rows.length > 1) && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={tHC("hcComponentsMcpPanelCatalogParts.s1")}
                    onClick={() => setRows((prev) => prev.filter((_, idx) => idx !== i))}
                  >
                    <X size={13} aria-hidden="true" />
                  </Button>
                )}
              </div>
            ))}
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="self-start gap-1"
              onClick={() => setRows((prev) => [...prev, { key: "", value: "" }])}
            >
              <Plus size={14} aria-hidden="true" /> {hasKnownFields ? "Add another" : "Add entry"}
            </Button>
          </div>
        )}
        <div className="flex gap-2">
          <Button type="button" size="sm" disabled={busy} onClick={() => onConfirm(server, entries)}>
            {confirmLabel}
          </Button>
          <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

// #424 - the credential form for a not-yet-registered, credential-gated
// catalog entry. A separate component (not inline JSX in InspectorPanel) so
// its own useState hooks are safe regardless of what InspectorPanel's other
// branches do - see CatalogCardInspector's comment for why that separation
// matters here specifically.
export function CatalogConnectForm({ entry, connecting, onConnect }: { entry: any; connecting: any; onConnect: any }) {
  const t = useTranslations("mcpPanel");
  // Record<string, string>, not the bare `{}` inference useState({}) would
  // give - TS7053, values is indexed by an arbitrary requiredEnvVars/
  // placeholder key below.
  const [values, setValues] = useState<Record<string, string>>({});
  const placeholders = placeholderVars(entry);

  function submit(e: any) {
    e.preventDefault();
    const env: Record<string, string> = {};
    entry.requiredEnvVars.forEach((v: string) => {
      if (!placeholders.has(v)) env[v] = values[v] || "";
    });
    if (entry.dynamicCommand) {
      const args = (entry.dynamicArgs || []).map((a: string) => {
        const m = /^\{\{(.+)\}\}$/.exec(a);
        return m ? values[m[1]] || "" : a;
      });
      onConnect(entry, entry.dynamicCommand, args, env, null);
    } else {
      // Remote entry (e.g. Atlassian) - a fixed, well-known URL, no
      // subprocess command/args; only the credential(s) above are collected.
      onConnect(entry, null, null, env, entry.dynamicUrl);
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-2">
      {/* Right where the person is actually typing the credential, not just
       * on the after-the-fact tools/credentials editor - #wire-credential-guides. */}
      <McpCredentialGuideAlert name={entry.name} />
      {entry.requiredEnvVars.map((v: string) => {
        const fieldId = `catalog-connect-${entry.name}-${v}`;
        return (
          <div key={v} className="flex flex-col gap-1">
            <Label htmlFor={fieldId} className="text-xs font-medium">
              {v}
            </Label>
            <Input
              id={fieldId}
              type={/token|key|secret/i.test(v) ? "password" : "text"}
              placeholder={v}
              value={values[v] || ""}
              onChange={(e) => setValues((prev) => ({ ...prev, [v]: e.target.value }))}
              required
            />
          </div>
        );
      })}
      <Button type="submit" size="sm" disabled={connecting}>
        {connecting ? t("connectingEllipsis") : t("saveAndConnect")}
      </Button>
    </form>
  );
}

// #424 - everything the inspector shows for a catalog entry that's NOT yet
// registered for this tenant: either a one-click Activate (no credential
// needed) or the credential form above, plus the static-YAML alternative
// for whoever'd rather set it up once for every tenant. Kept as its own
// component (rather than inline in InspectorPanel's "catalog" branch) so its
// useState calls (showYaml/copied) don't run into React's rules-of-hooks:
// a catalog card and its now-registered counterpart intentionally share the
// same selKey (see the `cards` useMemo's comment) so selection carries over
// across activation, which means the SAME InspectorPanel render can flip
// from the "catalog" branch to the "mcp" branch without a remount - hooks
// can't live directly in a branch that comes and goes like that, but they're
// fine inside a child component that only gets rendered while that branch
// is the one showing.
export function CatalogCardInspector({
  entry,
  onAdd,
  addBusy,
  onConnect,
  connecting,
  onConnectOAuth,
}: {
  entry: any;
  onAdd: any;
  addBusy: any;
  onConnect: any;
  connecting: any;
  onConnectOAuth: any;
}) {
  const t = useTranslations("mcpPanel");
  // Either a local subprocess (dynamicCommand) or a fixed remote endpoint
  // (dynamicUrl, e.g. Atlassian's hosted MCP server) counts as a real
  // self-service connect target - without dynamicUrl here, a ready=false
  // remote entry with no dynamicCommand fell through to the plain one-click
  // Add button below (same path a ready=true entry uses), silently
  // registering a credential-less, unconfigurable reference instead of
  // offering this form.
  const canConnect = Boolean(entry.dynamicCommand || entry.dynamicUrl) && entry.requiredEnvVars.length > 0;

  return (
    <div className="space-y-4">
      <Card>
        <CardContent className="space-y-2 pt-6">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("aboutLabel")}</div>
          <p className="text-sm text-muted-foreground">{entry.description}</p>
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">{t("typeLabel")}</span>
            <span className="font-medium">{entry.type}</span>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-2 pt-6">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {entry.ready ? t("activateLabel") : t("connectLabel")}
          </div>
          {entry.ready ? (
            <>
              <p className="text-sm text-muted-foreground">{t("noCredentialsNeededHint")}</p>
              <Button type="button" size="sm" disabled={addBusy} onClick={() => onAdd(entry)}>
                {addBusy ? t("activatingEllipsis") : t("activateButton")}
              </Button>
            </>
          ) : entry.requiresOAuth ? (
            // Checked BEFORE canConnect - an OAuth-backed entry may still
            // carry a non-empty dynamicUrl (informational only, see
            // McpCatalogEntry.requiresOAuth's javadoc), which must never
            // route it into the credential-paste form below.
            <>
              <p className="text-sm text-muted-foreground">{t("connectViaOAuthHint", { name: entry.name })}</p>
              <Button type="button" size="sm" disabled={connecting} onClick={() => onConnectOAuth(entry)}>
                {connecting ? t("connectingEllipsis") : t("connectViaOAuthButton", { name: entry.name })}
              </Button>
            </>
          ) : canConnect ? (
            <>
              <p className="text-sm text-muted-foreground">{t("enterCredentialHint", { name: entry.name })}</p>
              <CatalogConnectForm entry={entry} connecting={connecting} onConnect={onConnect} />
            </>
          ) : (
            <Button type="button" size="sm" disabled={addBusy} onClick={() => onAdd(entry)}>
              {addBusy ? t("addingEllipsis") : t("addButton")}
            </Button>
          )}
        </CardContent>
      </Card>

      <Button type="button" variant="outline" size="sm" className="w-full" asChild>
        <a href={entry.docsUrl} target="_blank" rel="noreferrer" className="flex items-center justify-center gap-2">
          {t("documentationLink")} <ExternalLink size={12} aria-hidden="true" />
        </a>
      </Button>
    </div>
  );
}
