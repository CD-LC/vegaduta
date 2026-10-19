"use client";

import { Wrench, X, Check } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import type { ToolCallEntry } from "./types";

/** The post-hoc tool-call log for a turn (fallback for a core that sent no live step events). */
export function ToolCallCard({ trace }: { trace: ToolCallEntry[] }) {
  const t = useTranslations("agentPlayground");
  return (
          <div className="w-full max-w-[85%] space-y-1 rounded-lg border border-border bg-secondary/30 p-2.5">
            {trace.map((call) => (
              <div className="flex items-center gap-2 text-xs" key={call.id}>
                <span className="inline-flex items-center gap-1 font-medium text-foreground">
                  <Wrench className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {call.toolName}
                </span>
                <span className="text-muted-foreground">
                  {call.errorMessage ? t("traceError") : t("traceOk")}
                </span>
                {call.errorMessage ? (
                  <span className="inline-flex items-center gap-1 text-destructive">
                    <X className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                    {call.errorMessage}
                  </span>
                ) : (
                  <Check
                    className="h-3.5 w-3.5 shrink-0"
                    style={{ color: "var(--state-success)" }}
                    aria-hidden="true"
                  />
                )}
              </div>
            ))}
          </div>
  );
}
