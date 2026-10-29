"use client";

import { useTranslations } from "../../lib/i18n";
import { usePagination } from "../../lib/usePagination";
import PaginationControls from "../PaginationControls";
import { Button, buttonVariants } from "../ui/button";
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
} from "../ui/alert-dialog";
import { ManageMemoryLink } from "../memory/ChatMemoryControls";

/** Tool-call log under the chat: an unbounded record list, paged client-side. */
export function ToolCallsSection({ toolCalls, onRefresh }: { toolCalls: any[]; onRefresh: () => void }) {
  const t = useTranslations("chatPanel");
  const toolCallsPagination = usePagination(toolCalls);
  return (
    <section className="bg-card p-6">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("toolCallsHeading")}
        </h3>
        <Button type="button" variant="ghost" size="sm" onClick={onRefresh}>
          {t("refresh")}
        </Button>
      </div>
      {toolCalls.length === 0 && <p className="text-sm text-muted-foreground">{t("noToolCallsYet")}</p>}
      <div className="space-y-2">
        {toolCallsPagination.pageItems.map((call) => (
          <div className="rounded-lg border border-border bg-secondary/30 px-3 py-2" key={call.id}>
            <div className="flex items-center justify-between gap-2">
              <strong className="text-sm font-medium text-foreground">{call.toolName}</strong>
              <span className="text-xs tabular-nums text-muted-foreground">
                {new Date(call.startedAt).toLocaleTimeString()}
              </span>
            </div>
            {call.errorMessage ? (
              <div className="mt-1 break-words text-xs text-destructive">
                {t("errorPrefix")}
                {call.errorMessage}
              </div>
            ) : (
              <div className="mt-1 break-words text-xs text-muted-foreground">
                {t("inputPrefix")}
                {call.toolInput}
              </div>
            )}
          </div>
        ))}
      </div>
      <PaginationControls
        page={toolCallsPagination.page}
        totalPages={toolCallsPagination.totalPages}
        totalItems={toolCallsPagination.totalItems}
        pageSize={toolCallsPagination.pageSize}
        onPageChange={toolCallsPagination.setPage}
        onPageSizeChange={toolCallsPagination.setPageSize}
        itemLabel={t("itemLabelCalls")}
      />
    </section>
  );
}

/** Remembered facts for this subject, with per-row forget. */
export function MemoriesSection({
  memories,
  onRefresh,
  onForget,
}: {
  memories: any[];
  onRefresh: () => void;
  onForget: (memoryId: string) => void;
}) {
  const t = useTranslations("chatPanel");
  const memoriesPagination = usePagination(memories);
  return (
    <section className="bg-card p-6">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{t("memoryHeading")}</h3>
        <ManageMemoryLink className="ms-3 me-auto" />
        <Button type="button" variant="ghost" size="sm" onClick={onRefresh}>
          {t("refresh")}
        </Button>
      </div>
      {memories.length === 0 && <p className="text-sm text-muted-foreground">{t("noMemoriesYet")}</p>}
      <div className="space-y-2">
        {memoriesPagination.pageItems.map((memory) => (
          <div
            className="flex items-center justify-between gap-2 rounded-lg border border-border bg-secondary/30 px-3 py-2"
            key={memory.id}
          >
            <span className="text-sm text-foreground">{memory.content}</span>
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button type="button" variant="ghost" size="sm" className="text-destructive hover:text-destructive">
                  {t("forget")}
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>{t("forgetMemoryTitle")}</AlertDialogTitle>
                  <AlertDialogDescription>{t("forgetMemoryDescription")}</AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
                  <AlertDialogAction
                    className={buttonVariants({ variant: "destructive" })}
                    onClick={() => onForget(memory.id)}
                  >
                    {t("forget")}
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          </div>
        ))}
      </div>
      <PaginationControls
        page={memoriesPagination.page}
        totalPages={memoriesPagination.totalPages}
        totalItems={memoriesPagination.totalItems}
        pageSize={memoriesPagination.pageSize}
        onPageChange={memoriesPagination.setPage}
        onPageSizeChange={memoriesPagination.setPageSize}
        itemLabel={t("itemLabelMemories")}
      />
    </section>
  );
}
