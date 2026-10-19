"use client";

import type { EMPTY_SETTINGS, OpenApiToolSource } from "./shared";

import { X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Card } from "../ui/card";
import OpenApiAuthFields from "./OpenApiAuthFields";

export default function OpenApiEditPanel({
  editBusy,
  editError,
  editForm,
  editing,
  handleSaveSettings,
  setEditForm,
  setEditing,
}: {
  editBusy: boolean;
  editError: string | null;
  editForm: { name: string } & typeof EMPTY_SETTINGS;
  editing: OpenApiToolSource;
  handleSaveSettings: (e: React.FormEvent<HTMLFormElement>) => void | Promise<void>;
  setEditForm: React.Dispatch<React.SetStateAction<{ name: string } & typeof EMPTY_SETTINGS>>;
  setEditing: (source: OpenApiToolSource | null) => void;
}) {
  const t = useTranslations("openApiTools");
  return (
    <Card className="gap-4 p-6">
      <div className="flex items-start justify-between gap-3">
        <div className="text-sm font-semibold text-foreground">{t("settingsTitle", { name: editing.name })}</div>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="size-8"
          onClick={() => setEditing(null)}
          aria-label={t("closeButton")}
        >
          <X aria-hidden="true" size={15} />
        </Button>
      </div>
      {editError && (
        <div
          role="alert"
          className="rounded-lg border border-[var(--danger-border)] bg-[var(--danger-bg)] px-3 py-2 text-sm text-[var(--danger-text)]"
        >
          {editError}
        </div>
      )}
      <form onSubmit={handleSaveSettings} className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="oas-edit-name">{t("nameLabel")}</Label>
            <Input
              id="oas-edit-name"
              value={editForm.name}
              onChange={(e) => setEditForm((p) => ({ ...p, name: e.target.value }))}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="oas-edit-base">{t("baseUrlLabel")}</Label>
            <Input
              id="oas-edit-base"
              value={editForm.baseUrl}
              onChange={(e) => setEditForm((p) => ({ ...p, baseUrl: e.target.value }))}
              placeholder="https://api.example.com/v1"
            />
          </div>
        </div>
        <OpenApiAuthFields
          form={editForm}
          onChange={(field, value) => setEditForm((p) => ({ ...p, [field]: value }))}
          idPrefix="oas-edit"
          stored={editing}
        />
        <p className="text-xs text-muted-foreground">{t("prefixImmutableNote", { prefix: editing.toolPrefix })}</p>
        <div className="flex gap-2">
          <Button type="submit" size="sm" disabled={editBusy}>
            {editBusy ? t("savingEllipsis") : t("saveSettingsButton")}
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={() => setEditing(null)}>
            {t("cancelButton")}
          </Button>
        </div>
      </form>
    </Card>
  );
}
