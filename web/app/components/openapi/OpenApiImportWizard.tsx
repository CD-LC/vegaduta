"use client";

import type { usePagination } from "../../lib/usePagination";
import type { EMPTY_SETTINGS, EMPTY_SOURCE_FORM, Preview, PreviewOperation } from "./shared";

import { X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import PaginationControls from "../PaginationControls";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import OpenApiAuthFields from "./OpenApiAuthFields";
import { MethodBadge } from "./shared";

export default function OpenApiImportWizard({
  closeImport,
  handleImport,
  handlePreview,
  importError,
  importing,
  preview,
  previewPagination,
  previewing,
  selectAll,
  selectedKeys,
  setSetting,
  setSource,
  setStep,
  settings,
  sourceForm,
  step,
  toggleKey,
}: {
  closeImport: () => void;
  handleImport: (e: React.FormEvent<HTMLFormElement>) => void | Promise<void>;
  handlePreview: (e: React.FormEvent<HTMLFormElement>) => void | Promise<void>;
  importError: string | null;
  importing: boolean;
  preview: Preview | null;
  previewPagination: ReturnType<typeof usePagination<PreviewOperation>>;
  previewing: boolean;
  selectAll: (select: boolean) => void;
  selectedKeys: Set<string>;
  setSetting: (field: keyof typeof EMPTY_SETTINGS, value: string) => void;
  setSource: (field: keyof typeof EMPTY_SOURCE_FORM, value: string) => void;
  setStep: (step: "source" | "review") => void;
  settings: typeof EMPTY_SETTINGS;
  sourceForm: typeof EMPTY_SOURCE_FORM;
  step: "source" | "review";
  toggleKey: (key: string) => void;
}) {
  const t = useTranslations("openApiTools");
  return (
    <Card className="gap-4 p-6">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-sm font-semibold text-foreground">{t("importTitle")}</div>
          <div className="mt-0.5 text-xs text-muted-foreground">
            {step === "source" ? t("stepSource") : t("stepReview")}
          </div>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="size-8"
          onClick={closeImport}
          aria-label={t("closeButton")}
        >
          <X aria-hidden="true" size={15} />
        </Button>
      </div>

      {importError && (
        <div
          role="alert"
          className="rounded-lg border border-[var(--danger-border)] bg-[var(--danger-bg)] px-3 py-2 text-sm text-[var(--danger-text)]"
        >
          {importError}
        </div>
      )}

      {step === "source" && (
        <form onSubmit={handlePreview} className="space-y-3">
          <p className="text-sm text-muted-foreground">{t("importIntro")}</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="oas-name">{t("nameLabel")}</Label>
              <Input
                id="oas-name"
                value={sourceForm.name}
                onChange={(e) => setSource("name", e.target.value)}
                placeholder={t("namePlaceholder")}
                aria-describedby="oas-name-help"
              />
              <p id="oas-name-help" className="text-xs text-muted-foreground">
                How this API shows up in your tools list, for example “Store API”.
              </p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="oas-prefix">{t("prefixLabel")}</Label>
              <Input
                id="oas-prefix"
                value={sourceForm.toolPrefix}
                onChange={(e) => setSource("toolPrefix", e.target.value)}
                placeholder={t("prefixPlaceholder")}
                className="font-mono"
              />
              <p className="text-xs text-muted-foreground">{t("prefixHelp")}</p>
            </div>
          </div>
          <div className="flex gap-2">
            <Button
              type="button"
              size="sm"
              variant={sourceForm.mode === "url" ? "default" : "outline"}
              aria-pressed={sourceForm.mode === "url"}
              onClick={() => setSource("mode", "url")}
            >
              {t("modeUrl")}
            </Button>
            <Button
              type="button"
              size="sm"
              variant={sourceForm.mode === "paste" ? "default" : "outline"}
              aria-pressed={sourceForm.mode === "paste"}
              onClick={() => setSource("mode", "paste")}
            >
              {t("modePaste")}
            </Button>
          </div>
          {sourceForm.mode === "url" ? (
            <div className="space-y-1.5">
              <Label htmlFor="oas-url">{t("specUrlLabel")}</Label>
              <Input
                id="oas-url"
                value={sourceForm.specUrl}
                onChange={(e) => setSource("specUrl", e.target.value)}
                placeholder="https://api.example.com/openapi.json"
                className="font-mono"
              />
              <p className="text-xs text-muted-foreground">{t("specUrlHelp")}</p>
            </div>
          ) : (
            <div className="space-y-1.5">
              <Label htmlFor="oas-text">{t("specTextLabel")}</Label>
              <Textarea
                id="oas-text"
                value={sourceForm.specText}
                onChange={(e) => setSource("specText", e.target.value)}
                rows={10}
                className="font-mono"
                placeholder={t("specTextPlaceholder")}
              />
            </div>
          )}
          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={previewing}>
              {previewing ? t("previewingEllipsis") : t("previewButton")}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={closeImport}>
              {t("cancelButton")}
            </Button>
          </div>
        </form>
      )}

      {step === "review" && preview && (
        <form onSubmit={handleImport} className="space-y-4">
          <div className="rounded-lg border border-border bg-secondary/30 p-3 text-sm">
            <div className="font-medium text-foreground">
              {preview.title || sourceForm.name}
              {preview.version ? <span className="ms-2 text-xs text-muted-foreground">v{preview.version}</span> : null}
            </div>
            {preview.description && (
              <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{preview.description}</p>
            )}
            <p className="mt-1 text-xs text-muted-foreground">
              {t("operationsFound", { count: preview.operations.length, prefix: preview.toolPrefix })}
            </p>
          </div>

          <div className="space-y-1.5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Label>{t("operationsLabel", { selected: selectedKeys.size, total: preview.operations.length })}</Label>
              <div className="flex gap-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-xs"
                  onClick={() => selectAll(true)}
                >
                  {t("selectAll")}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-xs"
                  onClick={() => selectAll(false)}
                >
                  {t("selectNone")}
                </Button>
              </div>
            </div>
            <ul className="max-h-80 divide-y divide-border overflow-auto rounded-lg border border-border">
              {previewPagination.pageItems.map((op) => {
                const checked = selectedKeys.has(op.operationKey);
                return (
                  <li key={op.operationKey}>
                    <label className="flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-accent">
                      <input
                        type="checkbox"
                        className="size-4 accent-primary"
                        checked={checked}
                        onChange={() => toggleKey(op.operationKey)}
                      />
                      <MethodBadge method={op.method} />
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="truncate font-mono text-xs font-medium text-foreground">
                            {op.suggestedToolName}
                          </span>
                          <span className="truncate font-mono text-xs text-muted-foreground">{op.path}</span>
                          {op.deprecated && (
                            <Badge variant="outline" className="text-xs uppercase">
                              {t("deprecatedBadge")}
                            </Badge>
                          )}
                        </div>
                        {(op.summary || op.description) && (
                          <p className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">
                            {op.summary || op.description}
                          </p>
                        )}
                      </div>
                    </label>
                  </li>
                );
              })}
            </ul>
            <PaginationControls
              page={previewPagination.page}
              totalPages={previewPagination.totalPages}
              totalItems={previewPagination.totalItems}
              pageSize={previewPagination.pageSize}
              onPageChange={previewPagination.setPage}
              onPageSizeChange={previewPagination.setPageSize}
              itemLabel={t("itemLabelOperations")}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="oas-base">{t("baseUrlLabel")}</Label>
            <Input
              id="oas-base"
              value={settings.baseUrl}
              onChange={(e) => setSetting("baseUrl", e.target.value)}
              placeholder="https://api.example.com/v1"
              className="font-mono"
            />
            <p className="text-xs text-muted-foreground">{t("baseUrlHelp")}</p>
          </div>

          <OpenApiAuthFields
            form={settings}
            onChange={(field, value) => setSetting(field, value)}
            idPrefix="oas-import"
          />

          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={importing}>
              {importing ? t("importingEllipsis") : t("importCountButton", { count: selectedKeys.size })}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setStep("source")} disabled={importing}>
              {t("backButton")}
            </Button>
          </div>
        </form>
      )}
    </Card>
  );
}
