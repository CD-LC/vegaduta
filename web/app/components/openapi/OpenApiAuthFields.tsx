"use client";

import Select from "../Select";
import { useTranslations } from "../../lib/i18n";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { type OpenApiToolSource, type AuthType } from "./shared";

export type AuthForm = {
  authType: AuthType;
  authHeaderName: string;
  secret: string;
  oauth2TokenUrl: string;
  oauth2ClientId: string;
  oauth2Scope: string;
  oauth2TokenStyle: string;
};

export default function OpenApiAuthFields({
  form,
  onChange,
  idPrefix,
  stored,
}: {
  form: AuthForm;
  onChange: (field: keyof AuthForm, value: string) => void;
  idPrefix: string;
  stored?: OpenApiToolSource | null;
}) {
  const t = useTranslations("openApiTools");
  const oauth2 = form.authType === "OAUTH2_CLIENT_CREDENTIALS";
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <div className="space-y-1.5">
        <Label htmlFor={`${idPrefix}-auth`}>{t("authLabel")}</Label>
        <Select
          id={`${idPrefix}-auth`}
          value={form.authType}
          onChange={(e) => onChange("authType", e.target.value)}
          options={[
            { value: "NONE", label: t("authNone") },
            { value: "API_KEY_HEADER", label: t("authApiKeyHeader") },
            { value: "BEARER", label: t("authBearer") },
            { value: "OAUTH2_CLIENT_CREDENTIALS", label: t("authOauth2") },
          ]}
        />
      </div>
      {form.authType === "API_KEY_HEADER" && (
        <div className="space-y-1.5">
          <Label htmlFor={`${idPrefix}-header`}>{t("headerNameLabel")}</Label>
          <Input
            id={`${idPrefix}-header`}
            value={form.authHeaderName}
            onChange={(e) => onChange("authHeaderName", e.target.value)}
            placeholder="X-API-Key"
          />
        </div>
      )}
      {oauth2 && (
        <>
          <div className="space-y-1.5 sm:col-span-2">
            <Label htmlFor={`${idPrefix}-tokenurl`}>{t("oauth2TokenUrlLabel")}</Label>
            <Input
              id={`${idPrefix}-tokenurl`}
              className="font-mono"
              value={form.oauth2TokenUrl}
              onChange={(e) => onChange("oauth2TokenUrl", e.target.value)}
              placeholder="https://api.availity.com/v1/token"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor={`${idPrefix}-clientid`}>{t("oauth2ClientIdLabel")}</Label>
            <Input
              id={`${idPrefix}-clientid`}
              className="font-mono"
              value={form.oauth2ClientId}
              onChange={(e) => onChange("oauth2ClientId", e.target.value)}
              placeholder={t("oauth2ClientIdPlaceholder")}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor={`${idPrefix}-scope`}>{t("oauth2ScopeLabel")}</Label>
            <Input
              id={`${idPrefix}-scope`}
              className="font-mono"
              value={form.oauth2Scope}
              onChange={(e) => onChange("oauth2Scope", e.target.value)}
              placeholder={t("oauth2ScopePlaceholder")}
            />
          </div>
          <div className="space-y-1.5 sm:col-span-2">
            <Label htmlFor={`${idPrefix}-style`}>{t("oauth2StyleLabel")}</Label>
            <Select
              id={`${idPrefix}-style`}
              value={form.oauth2TokenStyle}
              onChange={(e) => onChange("oauth2TokenStyle", e.target.value)}
              options={[
                { value: "FORM", label: t("oauth2StyleForm") },
                { value: "JSON", label: t("oauth2StyleJson") },
              ]}
            />
            <p className="text-xs text-muted-foreground">{t("oauth2Help")}</p>
          </div>
        </>
      )}
      {form.authType !== "NONE" && (
        <div className="space-y-1.5 sm:col-span-2">
          <Label htmlFor={`${idPrefix}-secret`}>
            {oauth2
              ? t("oauth2ClientSecretLabel")
              : form.authType === "BEARER"
                ? t("bearerTokenLabel")
                : t("apiKeyLabel")}
          </Label>
          <Input
            id={`${idPrefix}-secret`}
            type="password"
            autoComplete="off"
            value={form.secret}
            onChange={(e) => onChange("secret", e.target.value)}
            placeholder={
              stored?.hasSecret && stored.authType === form.authType
                ? t("secretKeepPlaceholder", { preview: stored.secretPreview || "" })
                : t("secretPlaceholder")
            }
          />
          <p className="text-xs text-muted-foreground">{t("secretHelp")}</p>
        </div>
      )}
    </div>
  );
}
