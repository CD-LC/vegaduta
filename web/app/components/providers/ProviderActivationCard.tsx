"use client";

import type { FormEvent } from "react";
import { KeyRound } from "lucide-react";
import CredentialHelp from "../CredentialHelp";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useTranslations } from "../../lib/i18n";
import LocalServerPresets from "./LocalServerPresets";
import { MULTI_FIELD_PROVIDERS, PROVIDER_INFO, VERTEX_SPECIAL_CASED, type ProviderName } from "./providerCatalog";

export interface ProviderActivationCardProps {
  providerName: ProviderName;
  guide: any;
  busy: boolean;
  onSubmit: (e: FormEvent) => void;
  onCancel: () => void;
  apiKey: string;
  setApiKey: (v: string) => void;
  vertexProjectId: string;
  setVertexProjectId: (v: string) => void;
  vertexLocation: string;
  setVertexLocation: (v: string) => void;
  vertexServiceAccountJson: string;
  setVertexServiceAccountJson: (v: string) => void;
  multiFieldValues: Record<string, string>;
  setMultiFieldValue: (key: string, value: string) => void;
}

/** The below-grid activation form: Vertex (3 fields), multi-field JSON providers, or a single API key. */
export default function ProviderActivationCard({
  providerName,
  guide,
  busy,
  onSubmit,
  onCancel,
  apiKey,
  setApiKey,
  vertexProjectId,
  setVertexProjectId,
  vertexLocation,
  setVertexLocation,
  vertexServiceAccountJson,
  setVertexServiceAccountJson,
  multiFieldValues,
  setMultiFieldValue,
}: ProviderActivationCardProps) {
    const tHC = useTranslations("hcComponentsProvidersProviderActivationCard");
  const t = useTranslations("providersPanel");
  return (
    <Card className="gap-4 p-6">
      <div className="space-y-1">
        <div className="text-sm font-semibold text-foreground">
          {t("activateHeader", { label: t(PROVIDER_INFO[providerName].labelKey) })}
        </div>
        {guide && (
          <div className="text-sm text-muted-foreground">
            <CredentialHelp {...guide} />
          </div>
        )}
      </div>
      <form onSubmit={onSubmit} className="space-y-3">
        {providerName === VERTEX_SPECIAL_CASED ? (
          <>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="vertex-project-id">{t("googleVertexProjectIdLabel")}</Label>
                <Input
                  id="vertex-project-id"
                  type="text"
                  placeholder={t("googleVertexProjectIdPlaceholder")}
                  value={vertexProjectId}
                  onChange={(e) => setVertexProjectId(e.target.value)}
                  autoFocus
                  required
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="vertex-location">{t("googleVertexLocationLabel")}</Label>
                <Input
                  id="vertex-location"
                  type="text"
                  placeholder={t("googleVertexLocationPlaceholder")}
                  value={vertexLocation}
                  onChange={(e) => setVertexLocation(e.target.value)}
                  required
                />
              </div>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="vertex-service-account">{t("googleVertexServiceAccountLabel")}</Label>
              <textarea
                id="vertex-service-account"
                placeholder={t("googleVertexServiceAccountPlaceholder")}
                value={vertexServiceAccountJson}
                onChange={(e) => setVertexServiceAccountJson(e.target.value)}
                rows={6}
                className="w-full resize-y rounded-md border border-input bg-background px-3 py-2 font-mono text-xs shadow-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/40"
                required
              />
              <p className="text-xs text-muted-foreground">{t("googleVertexServiceAccountHelp")}</p>
            </div>
          </>
        ) : MULTI_FIELD_PROVIDERS[providerName] ? (
          // Multi-field providers (OLLAMA/AZURE_OPENAI/AWS_BEDROCK) - see
          // MULTI_FIELD_PROVIDERS' comment for the JSON-encoding pattern
          // this feeds into on submit.
          MULTI_FIELD_PROVIDERS[providerName].map((field, i) => (
            <div key={field.key} className="space-y-1.5">
              {providerName === "LOCAL_OPENAI" && i === 0 && (
                <LocalServerPresets
                  baseUrl={multiFieldValues[field.key] || ""}
                  onPick={(url) => setMultiFieldValue(field.key, url)}
                />
              )}
              <Label htmlFor={`provider-field-${field.key}`}>
                {t(field.labelKey)}
                {!field.required && <span className="text-muted-foreground"> ({t("optionalFieldNote")})</span>}
              </Label>
              <Input
                id={`provider-field-${field.key}`}
                type={field.type}
                placeholder={t(field.placeholderKey)}
                value={multiFieldValues[field.key] || ""}
                onChange={(e) => setMultiFieldValue(field.key, e.target.value)}
                className="max-w-md"
                autoFocus={i === 0}
                required={field.required}
                aria-describedby={i === 0 ? "provider-first-field-help" : undefined}
              />
              {i === 0 && (
                <p id="provider-first-field-help" className="text-xs text-muted-foreground">
                  {tHC("s2")}
                </p>
              )}
            </div>
          ))
        ) : (
          <div className="space-y-1.5">
            <Label htmlFor="provider-api-key">{t("apiKeyPlaceholder")}</Label>
            <Input
              id="provider-api-key"
              type="password"
              placeholder={t("apiKeyPlaceholder")}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              className="max-w-md"
              aria-describedby="provider-api-key-help"
              autoFocus
              required
            />
            <p id="provider-api-key-help" className="text-xs text-muted-foreground">
              {tHC("s1")}
            </p>
          </div>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" size="sm" disabled={busy}>
            <KeyRound aria-hidden="true" size={14} /> {busy ? t("activatingButton") : t("activateButton")}
          </Button>
          <Button type="button" variant="outline" size="sm" onClick={onCancel}>
            {t("cancelButton")}
          </Button>
        </div>
      </form>
    </Card>
  );
}
