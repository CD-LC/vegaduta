"use client";

import { CheckCircle2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import PoweredByChip, { inferSource } from "../PoweredByChip";
import TaskIdChip from "./TaskIdChip";
import type { RunPower } from "./model";

/** Quick Task's DONE card: the answer, its delivery note, and "Start a new task". */
export default function QuickTaskResultCard({
  result,
  runPower,
  taskId,
  onReset,
}: {
  result: { resultText: string; deliveryNote?: string };
  runPower: RunPower | null;
  taskId: string | null;
  onReset: () => void;
}) {
  const t = useTranslations("simpleTaskRunner");
  return (
    <Card className="gap-3 border-emerald-500/30 bg-emerald-500/5 p-5">
      <Badge className="w-fit gap-1.5 border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
        <CheckCircle2 aria-hidden="true" size={12} /> {t("doneBadge")}
      </Badge>
      <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-foreground">
        {result.resultText}
      </p>
      {result.deliveryNote && <p className="text-sm text-muted-foreground">{result.deliveryNote}</p>}
      <div className="flex flex-wrap items-center gap-2 pt-1">
        <Button variant="outline" onClick={onReset}>
          {t("startNewTask")}
        </Button>
        {(runPower?.providerName || runPower?.model) && (
          <PoweredByChip
            source={inferSource(runPower?.providerName)}
            provider={runPower?.providerName}
            model={runPower?.model}
          />
        )}
        {taskId && <TaskIdChip taskId={taskId} />}
      </div>
    </Card>
  );
}
