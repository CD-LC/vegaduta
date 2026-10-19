"use client";

import { useState } from "react";
import { AlertTriangle, Download, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { triggerBrowserDownload } from "../lib/api/core";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "../components/ui/alert-dialog";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { Button, buttonVariants } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { useTranslations } from "../lib/i18n";
import { confirmationMatches } from "./memoryFilters";
import type { ForgetAllResult } from "./memoryTypes";

/**
 * Export everything, or erase everything. Only mounted when the server
 * reports selfServiceEnabled; if either call still answers 404 (the flag was
 * switched off after the page loaded) the card asks the page to hide it.
 */
export default function DangerZone({
  onForgotten,
  onUnavailable,
}: {
  /** Called after a successful erase so the page can reload its (now empty) data. */
  onForgotten: () => void;
  /** Called when the endpoints turn out to be switched off. */
  onUnavailable: () => void;
}) {
  const t = useTranslations("memoryPage");
  const [exporting, setExporting] = useState(false);
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const [erasing, setErasing] = useState(false);
  const [result, setResult] = useState<ForgetAllResult | null>(null);

  const confirmWord = t("forgetAllConfirmWord");
  const confirmed = confirmationMatches(typed, confirmWord);

  const doExport = async () => {
    setExporting(true);
    try {
      const { blob, filename } = await api.exportMemory();
      triggerBrowserDownload(blob, filename);
    } catch (err: any) {
      if (err?.status === 404) onUnavailable();
      /* a 404 is quiet (switched off); any other failure was already shown by toJson */
    } finally {
      setExporting(false);
    }
  };

  const doForget = async () => {
    if (!confirmed || erasing) return;
    setErasing(true);
    try {
      const outcome: ForgetAllResult | null = await api.forgetAllMemory();
      setResult(outcome || { agentFacts: 0, sharedAndBlocks: 0, graphEntities: 0, graphErased: true });
      onForgotten();
    } catch (err: any) {
      if (err?.status === 404) onUnavailable();
    } finally {
      setErasing(false);
      setTyped("");
      setOpen(false);
    }
  };

  return (
    <Card elevation="raised" data-testid="memory-danger-zone">
      <CardHeader>
        <CardTitle>{t("dangerHeading")}</CardTitle>
        <CardDescription>{t("dangerIntro")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {result && (
          <Alert variant={result.graphErased ? "success" : "warning"}>
            {!result.graphErased && <AlertTriangle aria-hidden="true" />}
            <AlertTitle>{t("forgetAllDoneTitle")}</AlertTitle>
            <AlertDescription>
              <p>
                {t("forgetAllDoneBody", {
                  facts: result.agentFacts,
                  shared: result.sharedAndBlocks,
                  graph: result.graphEntities,
                })}
              </p>
              {!result.graphErased && <p className="mt-1">{t("forgetAllGraphWarning")}</p>}
            </AlertDescription>
          </Alert>
        )}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="text-sm font-medium text-foreground">{t("exportTitle")}</p>
            <p className="text-xs text-muted-foreground">{t("exportHint")}</p>
          </div>
          <Button variant="outline" size="sm" disabled={exporting} onClick={doExport}>
            <Download size={14} aria-hidden="true" /> {exporting ? t("exporting") : t("exportButton")}
          </Button>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
          <div className="min-w-0">
            <p className="text-sm font-medium text-foreground">{t("forgetAllTitle")}</p>
            <p className="text-xs text-muted-foreground">{t("forgetAllHint")}</p>
          </div>
          <AlertDialog
            open={open}
            onOpenChange={(next) => {
              setOpen(next);
              if (!next) setTyped("");
            }}
          >
            <AlertDialogTrigger asChild>
              <Button variant="destructive" size="sm">
                <Trash2 size={14} aria-hidden="true" /> {t("forgetAllButton")}
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>{t("forgetAllDialogTitle")}</AlertDialogTitle>
                <AlertDialogDescription>{t("forgetAllDialogBody")}</AlertDialogDescription>
              </AlertDialogHeader>
              <div className="space-y-1.5">
                <p className="text-sm text-foreground">{t("forgetAllTypePrompt", { word: confirmWord })}</p>
                <Input
                  value={typed}
                  onChange={(e) => setTyped(e.target.value)}
                  aria-label={t("forgetAllTypePrompt", { word: confirmWord })}
                  autoComplete="off"
                />
              </div>
              <AlertDialogFooter>
                <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
                <AlertDialogAction
                  className={buttonVariants({ variant: "destructive" })}
                  disabled={!confirmed || erasing}
                  onClick={(e) => {
                    // Keep the dialog under this component's control: it closes
                    // once the erase has settled, not on the click itself.
                    e.preventDefault();
                    void doForget();
                  }}
                >
                  {t("forgetAllConfirmButton")}
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      </CardContent>
    </Card>
  );
}
