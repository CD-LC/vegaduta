"use client";

import { AlertTriangle } from "lucide-react";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { Skeleton } from "../ui/skeleton";
import { useTranslations } from "../../lib/i18n";

/** Placeholder rows shown while a list loads for the first time. */
export function ListSkeleton({ rows = 5, label }: { rows?: number; label?: string }) {
  const t = useTranslations("tenantLists");
  return (
    <div role="status" aria-busy="true" aria-label={label ?? t("loading")} className="space-y-2">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-12 w-full" />
      ))}
    </div>
  );
}

/** A failed list fetch: the message plus a Retry. Never fall through to an empty state after a failure. */
export function ListError({ message, onRetry }: { message: string; onRetry?: () => void }) {
  const t = useTranslations("tenantLists");
  return (
    <Alert variant="destructive" className="flex items-start gap-3">
      <AlertTriangle aria-hidden="true" />
      <AlertDescription className="flex flex-1 flex-wrap items-center justify-between gap-2">
        <span>{message}</span>
        {onRetry && (
          <Button type="button" size="sm" variant="outline" onClick={onRetry}>
            {t("retry")}
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
