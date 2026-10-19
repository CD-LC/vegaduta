"use client";

import { Select } from "../../components/ui/select";
import { useCallback, useEffect, useState } from "react";
import { api } from "../../lib/api";
import { PURPOSE_PATTERN, privacyApi, type PurposeResource } from "../../lib/api/privacy";
import { useTranslations } from "../../lib/i18n";
import { Section } from "../../components/ax/page-kit";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Skeleton } from "../../components/ui/skeleton";
import { LoadError, errText } from "./shared";

type Option = { id: string; name: string };

export default function PurposesTab() {
  const t = useTranslations("privacySso");
  const [resource, setResource] = useState<PurposeResource>("agents");
  const [options, setOptions] = useState<Option[] | null>(null);
  const [optionsError, setOptionsError] = useState<string | null>(null);
  const [id, setId] = useState("");
  const [purposes, setPurposes] = useState<string[]>([]);
  const [draft, setDraft] = useState("");
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const loadOptions = useCallback(() => {
    setOptions(null);
    setOptionsError(null);
    setId("");
    setPurposes([]);
    const call: Promise<any[]> = resource === "agents" ? api.listAgents() : api.listCollections();
    call
      .then((list) => setOptions(list.map((o) => ({ id: o.id, name: o.name ?? o.id }))))
      .catch((e: unknown) => setOptionsError(errText(e)));
  }, [resource]);
  useEffect(loadOptions, [loadOptions]);

  const loadPurposes = useCallback(
    (rid: string) => {
      if (!rid) {
        setPurposes([]);
        return;
      }
      setLoading(true);
      setLoadError(null);
      setSaved(false);
      setFormError(null);
      privacyApi
        .getPurposes(resource, rid)
        .then(setPurposes)
        .catch((e: unknown) => setLoadError(errText(e)))
        .finally(() => setLoading(false));
    },
    [resource],
  );

  const add = () => {
    const p = draft.trim().toLowerCase();
    if (!PURPOSE_PATTERN.test(p)) {
      setFormError(t("errPurpose"));
      return;
    }
    setFormError(null);
    if (!purposes.includes(p)) setPurposes([...purposes, p]);
    setDraft("");
    setSaved(false);
  };

  const save = async () => {
    setSaving(true);
    setFormError(null);
    try {
      setPurposes(await privacyApi.putPurposes(resource, id, purposes));
      setSaved(true);
    } catch (e) {
      setFormError(errText(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Section title={t("purposesTitle")} description={t("purposesIntro")}>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="grid gap-1.5">
          <Label htmlFor="purpose-resource">{t("resourceType")}</Label>
          <Select
            id="purpose-resource"
            value={resource}
            onValueChange={(v) => setResource(v as PurposeResource)}
            options={[
              { value: "agents", label: t("resourceAgent") },
              { value: "knowledge-collections", label: t("resourceCollection") },
            ]}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="purpose-target">{t("resource")}</Label>
          {optionsError ? (
            <LoadError message={optionsError} onRetry={loadOptions} />
          ) : options === null ? (
            <Skeleton className="h-10 w-full" aria-label={t("loading")} />
          ) : (
            <Select
              id="purpose-target"
              value={id}
              onValueChange={(v) => {
                setId(v);
                loadPurposes(v);
              }}
              placeholder={options.length ? t("chooseResource") : t("noResources")}
              options={[
                { value: "", label: options.length ? t("chooseResource") : t("noResources") },
                ...options.map((o) => ({ value: o.id, label: o.name })),
              ]}
            />
          )}
        </div>
      </div>

      {id && loading && <Skeleton className="h-20 w-full" aria-label={t("loading")} />}
      {id && loadError && <LoadError message={loadError} onRetry={() => loadPurposes(id)} />}
      {id && !loading && !loadError && (
        <div className="space-y-4">
          <div className="flex flex-wrap gap-2" aria-label={t("currentPurposes")}>
            {purposes.length === 0 && <span className="text-sm text-muted-foreground">{t("untagged")}</span>}
            {purposes.map((p) => (
              <Badge key={p} variant="secondary" className="gap-1.5">
                {p}
                <button
                  type="button"
                  className="rounded-sm px-1 text-xs"
                  aria-label={t("removePurpose", { purpose: p })}
                  onClick={() => {
                    setPurposes(purposes.filter((x) => x !== p));
                    setSaved(false);
                  }}
                >
                  x
                </button>
              </Badge>
            ))}
          </div>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
            <div className="grid flex-1 gap-1.5">
              <Label htmlFor="purpose-new">{t("addPurpose")}</Label>
              <Input
                id="purpose-new"
                value={draft}
                placeholder="support"
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    add();
                  }
                }}
              />
            </div>
            <Button type="button" variant="outline" onClick={add}>
              {t("add")}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">{t("purposeHint")}</p>
          {formError && (
            <Alert variant="destructive">
              <AlertDescription>{formError}</AlertDescription>
            </Alert>
          )}
          {saved && (
            <Alert variant="success">
              <AlertDescription>{t("purposesSaved")}</AlertDescription>
            </Alert>
          )}
          <Button type="button" onClick={() => void save()} disabled={saving}>
            {saving ? t("saving") : t("savePurposes")}
          </Button>
        </div>
      )}
    </Section>
  );
}
