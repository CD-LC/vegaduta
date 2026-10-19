"use client";

import { useCallback, useEffect, useState } from "react";
import { ssoApi, type SsoProviderType, type SsoStatusDto, type SsoUpsertRequest } from "../../lib/api/sso";
import { useTranslations } from "../../lib/i18n";
import { Section } from "../ax/page-kit";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Skeleton } from "../ui/skeleton";
import { Switch } from "../ui/switch";
import { Textarea } from "../ui/textarea";

const SELECT_CLASS =
  "flex h-10 w-full rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow] focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40";

type Form = {
  providerType: SsoProviderType;
  displayName: string;
  enabled: boolean;
  oidcDiscoveryUrl: string;
  oidcAuthorizationUrl: string;
  oidcTokenUrl: string;
  oidcUserInfoUrl: string;
  oidcJwksUrl: string;
  oidcIssuer: string;
  oidcClientId: string;
  clientSecret: string;
  samlEntityId: string;
  samlSsoUrl: string;
  samlX509Cert: string;
  samlNameIdFormat: string;
};

const EMPTY: Form = {
  providerType: "OIDC",
  displayName: "",
  enabled: true,
  oidcDiscoveryUrl: "",
  oidcAuthorizationUrl: "",
  oidcTokenUrl: "",
  oidcUserInfoUrl: "",
  oidcJwksUrl: "",
  oidcIssuer: "",
  oidcClientId: "",
  clientSecret: "",
  samlEntityId: "",
  samlSsoUrl: "",
  samlX509Cert: "",
  samlNameIdFormat: "",
};

const MAX_URL = 2048;

/** Client-side checks that mirror TenantSsoUpsertRequest sizes; per-protocol rules stay with the server. */
export function validateSso(f: Form, secretAlreadySet: boolean): string | null {
  if (!f.displayName.trim()) return "errDisplayName";
  if (f.displayName.length > 255) return "errDisplayNameLength";
  const urls = [f.oidcDiscoveryUrl, f.oidcAuthorizationUrl, f.oidcTokenUrl, f.oidcUserInfoUrl, f.oidcJwksUrl, f.oidcIssuer, f.samlEntityId, f.samlSsoUrl];
  if (urls.some((u) => u.length > MAX_URL)) return "errUrlLength";
  if (f.providerType === "OIDC") {
    if (!f.oidcClientId.trim()) return "errClientId";
    if (!f.oidcDiscoveryUrl.trim() && !(f.oidcAuthorizationUrl.trim() && f.oidcTokenUrl.trim())) return "errOidcEndpoints";
    if (!f.clientSecret && !secretAlreadySet) return "errClientSecret";
  } else {
    if (!f.samlEntityId.trim() || !f.samlSsoUrl.trim()) return "errSamlRequired";
  }
  return null;
}

export default function SsoConnectionForm() {
  const t = useTranslations("privacySso");
  const [status, setStatus] = useState<SsoStatusDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [form, setForm] = useState<Form>(EMPTY);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);

  const load = useCallback(() => {
    setLoadError(null);
    setStatus(null);
    ssoApi
      .status()
      .then((s) => {
        setStatus(s);
        setForm((f) => ({
          ...f,
          providerType: s.providerType ?? f.providerType,
          displayName: s.displayName ?? f.displayName,
          clientSecret: "",
        }));
      })
      .catch((e: unknown) => setLoadError(e instanceof Error ? e.message : String(e)));
  }, []);
  useEffect(load, [load]);

  const set = <K extends keyof Form>(key: K, value: Form[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    setSaved(false);
  };

  const text = (key: keyof Form, label: string, opts: { type?: string; hint?: string; placeholder?: string } = {}) => (
    <div className="grid gap-1.5">
      <Label htmlFor={`sso-${key}`}>{label}</Label>
      <Input
        id={`sso-${key}`}
        type={opts.type ?? "text"}
        autoComplete="off"
        placeholder={opts.placeholder}
        value={form[key] as string}
        onChange={(e) => set(key, e.target.value as never)}
      />
      {opts.hint && <p className="text-xs text-muted-foreground">{opts.hint}</p>}
    </div>
  );

  const onSave = async () => {
    const problem = validateSso(form, !!status?.clientSecretSet);
    if (problem) {
      setFormError(t(problem));
      return;
    }
    setFormError(null);
    setSaving(true);
    try {
      const body: SsoUpsertRequest = { providerType: form.providerType, displayName: form.displayName.trim(), enabled: form.enabled };
      if (form.providerType === "OIDC") {
        Object.assign(body, {
          oidcDiscoveryUrl: form.oidcDiscoveryUrl.trim(),
          oidcAuthorizationUrl: form.oidcAuthorizationUrl.trim(),
          oidcTokenUrl: form.oidcTokenUrl.trim(),
          oidcUserInfoUrl: form.oidcUserInfoUrl.trim(),
          oidcJwksUrl: form.oidcJwksUrl.trim(),
          oidcIssuer: form.oidcIssuer.trim(),
          oidcClientId: form.oidcClientId.trim(),
          clientSecret: form.clientSecret,
        });
      } else {
        Object.assign(body, {
          samlEntityId: form.samlEntityId.trim(),
          samlSsoUrl: form.samlSsoUrl.trim(),
          samlX509Cert: form.samlX509Cert.trim(),
          samlNameIdFormat: form.samlNameIdFormat.trim(),
        });
      }
      const next = await ssoApi.save(body);
      setStatus(next);
      setForm((f) => ({ ...f, clientSecret: "" }));
      setSaved(true);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const onRemove = async () => {
    setConfirmRemove(false);
    try {
      await ssoApi.remove();
      setForm(EMPTY);
      setSaved(false);
      load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : String(e));
    }
  };

  if (loadError) {
    return (
      <Alert variant="destructive">
        <AlertTitle>{t("loadFailedTitle")}</AlertTitle>
        <AlertDescription>
          <p>{loadError}</p>
          <Button type="button" variant="outline" size="sm" className="mt-2" onClick={load}>
            {t("retry")}
          </Button>
        </AlertDescription>
      </Alert>
    );
  }
  if (!status) return <Skeleton className="h-64 w-full" aria-label={t("loading")} />;

  return (
    <Section
      title={t("ssoConnectionTitle")}
      description={t("ssoConnectionIntro")}
      actions={
        status.configured ? (
          <Button type="button" variant="outline" size="sm" onClick={() => setConfirmRemove(true)}>
            {t("ssoRemove")}
          </Button>
        ) : undefined
      }
    >
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <Badge variant={status.configured ? "default" : "secondary"}>
          {status.configured ? t("ssoConfigured") : t("ssoNotConfigured")}
        </Badge>
        {status.configured && status.realmEnabled === null && <Badge variant="secondary">{t("ssoNotPushed")}</Badge>}
        {status.configured && status.realmEnabled === false && <Badge variant="secondary">{t("ssoRealmDisabled")}</Badge>}
        {status.configured && status.realmEnabled && <Badge variant="default">{t("ssoRealmEnabled")}</Badge>}
        {status.alias && <span className="text-muted-foreground">{t("ssoAlias", { alias: status.alias })}</span>}
      </div>

      {status.brokerRedirectUri && (
        <div className="grid gap-1.5">
          <Label htmlFor="sso-redirect">{t("ssoRedirectUri")}</Label>
          <Input id="sso-redirect" readOnly value={status.brokerRedirectUri} onFocus={(e) => e.currentTarget.select()} />
          <p className="text-xs text-muted-foreground">{t("ssoRedirectHint")}</p>
        </div>
      )}

      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          void onSave();
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="grid gap-1.5">
            <Label htmlFor="sso-providerType">{t("ssoProtocol")}</Label>
            <select
              id="sso-providerType"
              className={SELECT_CLASS}
              value={form.providerType}
              onChange={(e) => set("providerType", e.target.value as SsoProviderType)}
            >
              <option value="OIDC">OpenID Connect (OIDC)</option>
              <option value="SAML">SAML 2.0</option>
            </select>
          </div>
          {text("displayName", t("ssoDisplayName"), { placeholder: "Acme SSO" })}
        </div>

        {form.providerType === "OIDC" ? (
          <div className="grid gap-3 sm:grid-cols-2">
            {text("oidcDiscoveryUrl", t("oidcDiscovery"), { hint: t("oidcDiscoveryHint"), placeholder: "https://idp.example.com/.well-known/openid-configuration" })}
            {text("oidcIssuer", t("oidcIssuer"))}
            {text("oidcAuthorizationUrl", t("oidcAuthorization"))}
            {text("oidcTokenUrl", t("oidcToken"))}
            {text("oidcUserInfoUrl", t("oidcUserInfo"))}
            {text("oidcJwksUrl", t("oidcJwks"))}
            {text("oidcClientId", t("oidcClientId"))}
            {text("clientSecret", t("oidcClientSecret"), {
              type: "password",
              hint: status.clientSecretSet ? t("oidcSecretKept") : t("oidcSecretRequired"),
            })}
          </div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            {text("samlEntityId", t("samlEntityId"))}
            {text("samlSsoUrl", t("samlSsoUrl"))}
            {text("samlNameIdFormat", t("samlNameId"), { placeholder: "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress" })}
            <div className="grid gap-1.5 sm:col-span-2">
              <Label htmlFor="sso-samlX509Cert">{t("samlCert")}</Label>
              <Textarea
                id="sso-samlX509Cert"
                rows={5}
                value={form.samlX509Cert}
                onChange={(e) => set("samlX509Cert", e.target.value)}
              />
            </div>
          </div>
        )}

        <div className="flex items-center gap-3">
          <Switch id="sso-enabled" checked={form.enabled} onCheckedChange={(v) => set("enabled", v)} aria-label={t("ssoEnabled")} />
          <Label htmlFor="sso-enabled">{t("ssoEnabled")}</Label>
        </div>

        {formError && (
          <Alert variant="destructive">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        )}
        {saved && (
          <Alert variant="success">
            <AlertDescription>{t("ssoSaved")}</AlertDescription>
          </Alert>
        )}
        <Button type="submit" disabled={saving}>
          {saving ? t("saving") : t("ssoSave")}
        </Button>
      </form>

      <AlertDialog open={confirmRemove} onOpenChange={setConfirmRemove}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("ssoRemoveTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("ssoRemoveBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={() => void onRemove()}>{t("ssoRemoveAction")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Section>
  );
}
