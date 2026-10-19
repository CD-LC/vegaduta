"use client";

// Store-readiness panel for capacitor-app (mobile) projects: a checklist
// derived from the project's file set plus a form that commits the store
// listing (fastlane metadata, capacitor.config, checklist docs) through
// app-builder's POST /projects/{id}/mobile/store-config.
//
// Publishing to Google Play / App Store is human-only: this panel never asks
// for a store credential and never uploads a build.

import { cloneElement, isValidElement, useCallback, useEffect, useId, useMemo, useState } from "react";
import { CheckCircle2, Circle, Hand, Loader2, Smartphone } from "lucide-react";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Textarea } from "../components/ui/textarea";
import { Label } from "../components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { appBuilder, type AppBuilderAsset } from "./lib";
import {
  STORE_LIMITS,
  checklistProgress,
  deriveChecklist,
  validateStoreForm,
  type StoreConfig,
} from "./storeChecklist";

interface Props {
  project: { project_id: string; template_id: string };
  assets: AppBuilderAsset[];
  onError: (message: string) => void;
}

export default function StoreReadiness({ project, assets, onError }: Props) {
  const [paths, setPaths] = useState<string[]>([]);
  const [hasStoreConfig, setHasStoreConfig] = useState(false);
  const [saved, setSaved] = useState<StoreConfig | null>(null);
  const [form, setForm] = useState<StoreConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const st = await appBuilder.getStoreStatus(project.project_id);
      setPaths(st.paths || []);
      setHasStoreConfig(!!st.has_store_config);
      setSaved(st.config);
      setForm((cur) => cur ?? st.config);
    } catch (e) {
      onError(e instanceof Error ? e.message : "Could not load store status");
    } finally {
      setLoading(false);
    }
  }, [project.project_id, onError]);

  useEffect(() => {
    void load();
  }, [load]);

  const items = useMemo(
    () =>
      saved
        ? deriveChecklist({ paths, config: saved, hasStoreConfig, assets: assets.map((a) => ({ path: a.path, size: a.size })) })
        : [],
    [paths, saved, hasStoreConfig, assets]
  );
  const errs = form ? validateStoreForm(form) : {};
  const valid = form !== null && Object.keys(errs).length === 0;
  const progress = checklistProgress(items);

  const save = async () => {
    if (!form || !valid) return;
    setBusy(true);
    setNote(null);
    try {
      const r = await appBuilder.saveStoreConfig(project.project_id, {
        ...form,
        app_name: form.app_name.trim(),
        package_id: form.package_id.trim(),
        version_name: form.version_name.trim(),
        short_description: form.short_description.trim(),
        full_description: form.full_description.trim(),
      });
      if (r?.success === false) {
        setNote("The build check failed after the change, so nothing was committed.");
      } else {
        setNote("Saved. Store metadata and capacitor.config were committed to your branch.");
        await load();
      }
    } catch (e) {
      onError(e instanceof Error ? e.message : "Could not save store listing");
    } finally {
      setBusy(false);
    }
  };

  const set = (k: keyof StoreConfig) => (e: { target: { value: string } }) =>
    setForm((f) => (f ? { ...f, [k]: e.target.value } : f));

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm">
          <Smartphone aria-hidden="true" size={16} className="text-primary" /> Store readiness
          {items.length > 0 && (
            <span className="ml-auto text-xs font-normal text-muted-foreground">
              {progress.done}/{progress.total} automated checks done
            </span>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent>
        {loading && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 aria-hidden="true" size={14} className="animate-spin" /> Loading
          </div>
        )}

        <ul className="mb-6 space-y-2" aria-label="Store checklist">
          {items.map((it) => (
            <li key={it.id} className="flex items-start gap-2 text-sm">
              {it.state === "done" ? (
                <CheckCircle2 size={16} className="mt-0.5 shrink-0 text-emerald-500" aria-label="done" />
              ) : it.state === "manual" ? (
                <Hand size={16} className="mt-0.5 shrink-0 text-amber-500" aria-label="you do this" />
              ) : (
                <Circle size={16} className="mt-0.5 shrink-0 text-muted-foreground" aria-label="to do" />
              )}
              <div>
                <div className="font-medium text-foreground">{it.label}</div>
                {it.state !== "done" && <div className="text-xs text-muted-foreground">{it.hint}</div>}
              </div>
            </li>
          ))}
        </ul>

        {form && (
          <form
            className="space-y-3"
            onSubmit={(e) => {
              e.preventDefault();
              void save();
            }}
          >
            <Field label={`App name (max ${STORE_LIMITS.title})`} error={errs.app_name}>
              <Input value={form.app_name} onChange={set("app_name")} maxLength={200} />
            </Field>
            <Field label="Package id" error={errs.package_id}>
              <Input value={form.package_id} onChange={set("package_id")} placeholder="com.acme.myapp" />
            </Field>
            <Field label="Version name" error={errs.version_name}>
              <Input value={form.version_name} onChange={set("version_name")} placeholder="1.0.0" />
            </Field>
            <Field label={`Short description (${form.short_description.trim().length}/${STORE_LIMITS.short})`} error={errs.short_description}>
              <Input value={form.short_description} onChange={set("short_description")} />
            </Field>
            <Field label={`Full description (${form.full_description.trim().length}/${STORE_LIMITS.full})`} error={errs.full_description}>
              <Textarea rows={6} value={form.full_description} onChange={set("full_description")} />
            </Field>
            <Button type="submit" size="sm" disabled={busy || !valid}>
              {busy && <Loader2 aria-hidden="true" size={15} className="animate-spin" />} Save store listing
            </Button>
            {note && <p className="text-sm text-muted-foreground">{note}</p>}
            <p className="text-xs text-muted-foreground">
              This commits metadata to your repository only. You upload the signed build and submit the listing
              yourself in Play Console / App Store Connect.
            </p>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  // Tie the label to its control (click-to-focus, screen readers) and the
  // error text to the control it describes.
  const id = useId();
  const errId = `${id}-err`;
  const control = isValidElement(children)
    ? cloneElement(children as React.ReactElement<any>, {
        id,
        "aria-invalid": error ? true : undefined,
        "aria-describedby": error ? errId : undefined,
      })
    : children;
  return (
    <div>
      <Label htmlFor={id} className="mb-1 block text-xs">
        {label}
      </Label>
      {control}
      {error && (
        <p id={errId} className="mt-1 text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
