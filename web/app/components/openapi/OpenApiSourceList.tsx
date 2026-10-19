"use client";

import type { usePagination } from "../../lib/usePagination";
import type { OpenApiToolSource, OpenApiTool, AuthType } from "./shared";

import { ChevronDown, ChevronRight, Globe, KeyRound, Pencil, Play, RefreshCw, Trash2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import PaginationControls from "../PaginationControls";
import { Button } from "../ui/button";
import { Textarea } from "../ui/textarea";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Switch, MethodBadge, formatWhen } from "./shared";

export default function OpenApiSourceList({
  authLabel,
  busyId,
  busyToolId,
  canManageTools,
  expandedId,
  handleRefresh,
  handleTest,
  handleToggleSource,
  handleToggleTool,
  openEdit,
  openTest,
  setDeleteTarget,
  setTestInput,
  setTestTool,
  sourcesPagination,
  testInput,
  testResult,
  testTool,
  testing,
  toggleExpanded,
  toolsBySource,
  toolsLoading,
  toolsPagination,
}: {
  authLabel: (type: AuthType) => string;
  busyId: string | null;
  busyToolId: string | null;
  canManageTools: boolean;
  expandedId: string | null;
  handleRefresh: (source: OpenApiToolSource) => void | Promise<void>;
  handleTest: () => void | Promise<void>;
  handleToggleSource: (source: OpenApiToolSource) => void | Promise<void>;
  handleToggleTool: (tool: OpenApiTool) => void | Promise<void>;
  openEdit: (source: OpenApiToolSource) => void;
  openTest: (tool: OpenApiTool) => void;
  setDeleteTarget: (source: OpenApiToolSource | null) => void;
  setTestInput: (value: string) => void;
  setTestTool: (tool: OpenApiTool | null) => void;
  sourcesPagination: ReturnType<typeof usePagination<OpenApiToolSource>>;
  testInput: string;
  testResult: string | null;
  testTool: OpenApiTool | null;
  testing: boolean;
  toggleExpanded: (source: OpenApiToolSource) => void;
  toolsBySource: Record<string, OpenApiTool[]>;
  toolsLoading: string | null;
  toolsPagination: ReturnType<typeof usePagination<OpenApiTool>>;
}) {
  const t = useTranslations("openApiTools");
  return (
    <div className="space-y-3">
      {sourcesPagination.pageItems.map((source) => {
        const expanded = expandedId === source.id;
        const tools = toolsBySource[source.id];
        const refreshed = formatWhen(source.lastRefreshedAt);
        return (
          <Card key={source.id} className={cn("gap-0 p-0", !source.enabled && "opacity-70")}>
            <div className="flex items-start gap-3 p-5">
              <button
                type="button"
                onClick={() => toggleExpanded(source)}
                aria-expanded={expanded}
                aria-label={t("expandAriaLabel", { name: source.name })}
                className="mt-1 flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors hover:bg-primary/20"
              >
                {expanded ? (
                  <ChevronDown aria-hidden="true" size={16} />
                ) : (
                  <ChevronRight aria-hidden="true" size={16} />
                )}
              </button>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="truncate text-sm font-medium text-foreground">{source.name}</span>
                  {source.specTitle && (
                    <span className="truncate text-xs text-muted-foreground">
                      {source.specTitle}
                      {source.specVersion ? ` v${source.specVersion}` : ""}
                    </span>
                  )}
                  <Badge variant="outline" className="font-mono text-[0.6875rem]">
                    {source.toolPrefix}_*
                  </Badge>
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                  <span className="inline-flex items-center gap-1">
                    <Globe aria-hidden="true" size={12} /> <span className="truncate font-mono">{source.baseUrl}</span>
                  </span>
                  <span className="inline-flex items-center gap-1">
                    <KeyRound aria-hidden="true" size={12} /> {authLabel(source.authType)}
                    {source.hasSecret && source.secretPreview ? (
                      <span className="font-mono">{source.secretPreview}</span>
                    ) : null}
                  </span>
                  <span>{t("toolCountLabel", { enabled: source.enabledToolCount, total: source.toolCount })}</span>
                  {refreshed && <span>{t("refreshedAtLabel", { when: refreshed })}</span>}
                  <span>{source.enabled ? t("statusEnabled") : t("statusDisabled")}</span>
                </div>
                {source.lastError && (
                  <p className="mt-2 rounded-md border border-[var(--danger-border)] bg-[var(--danger-bg)] px-2 py-1 text-xs text-[var(--danger-text)]">
                    {t("lastErrorPrefix")} {source.lastError}
                  </p>
                )}
              </div>
              {canManageTools && (
                <div className="flex shrink-0 items-center gap-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="size-8"
                    disabled={busyId === source.id}
                    onClick={() => handleRefresh(source)}
                    aria-label={t("refreshAriaLabel", { name: source.name })}
                    title={t("refreshTitle")}
                  >
                    <RefreshCw aria-hidden="true" size={14} className={cn(busyId === source.id && "animate-spin")} />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="size-8"
                    disabled={busyId === source.id}
                    onClick={() => openEdit(source)}
                    aria-label={t("editAriaLabel", { name: source.name })}
                    title={t("editTitle")}
                  >
                    <Pencil aria-hidden="true" size={14} />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="size-8 text-muted-foreground hover:text-destructive"
                    disabled={busyId === source.id}
                    onClick={() => setDeleteTarget(source)}
                    aria-label={t("deleteAriaLabel", { name: source.name })}
                    title={t("deleteTitle")}
                  >
                    <Trash2 aria-hidden="true" size={14} />
                  </Button>
                  <Switch
                    checked={source.enabled}
                    disabled={busyId === source.id}
                    label={t("toggleAriaLabel", { name: source.name })}
                    onToggle={() => handleToggleSource(source)}
                  />
                </div>
              )}
            </div>

            {expanded && (
              <div className="border-t border-border bg-secondary/20 px-5 py-4">
                {toolsLoading === source.id && !tools && (
                  <p className="text-xs text-muted-foreground">{t("loadingTools")}</p>
                )}
                {tools && tools.length === 0 && <p className="text-xs text-muted-foreground">{t("noTools")}</p>}
                {tools && tools.length > 0 && (
                  <ul className="divide-y divide-border">
                    {toolsPagination.pageItems.map((tool) => (
                      <li key={tool.id} className={cn("py-2.5", !tool.enabled && "opacity-60")}>
                        <div className="flex items-center gap-3">
                          <MethodBadge method={tool.httpMethod} />
                          <div className="min-w-0 flex-1">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="truncate font-mono text-xs font-medium text-foreground">
                                {tool.toolName}
                              </span>
                              <span className="truncate font-mono text-xs text-muted-foreground">
                                {tool.pathTemplate}
                              </span>
                              {tool.deprecated && (
                                <Badge variant="outline" className="text-xs uppercase">
                                  {t("deprecatedBadge")}
                                </Badge>
                              )}
                            </div>
                            {(tool.summary || tool.description) && (
                              <p className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">
                                {tool.summary || tool.description}
                              </p>
                            )}
                          </div>
                          {canManageTools && (
                            <>
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                className="h-7 px-2 text-xs"
                                onClick={() => openTest(tool)}
                                aria-label={t("tryAriaLabel", { name: tool.toolName })}
                              >
                                <Play aria-hidden="true" size={12} /> {t("tryButton")}
                              </Button>
                              <Switch
                                checked={tool.enabled}
                                disabled={busyToolId === tool.id}
                                label={t("toggleAriaLabel", { name: tool.toolName })}
                                onToggle={() => handleToggleTool(tool)}
                              />
                            </>
                          )}
                        </div>
                        {testTool?.id === tool.id && (
                          <div className="mt-3 rounded-lg border border-border bg-background p-3">
                            <div className="mb-2 font-mono text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                              {t("tryItLabel")}
                            </div>
                            <p className="mb-2 text-xs text-muted-foreground">{t("tryItHelp")}</p>
                            <Textarea
                              value={testInput}
                              onChange={(e) => setTestInput(e.target.value)}
                              rows={4}
                              className="font-mono"
                            />
                            <div className="mt-2 flex items-center gap-2">
                              <Button type="button" variant="outline" size="sm" onClick={handleTest} disabled={testing}>
                                <Play aria-hidden="true" size={13} /> {testing ? t("runningEllipsis") : t("runButton")}
                              </Button>
                              <Button type="button" variant="ghost" size="sm" onClick={() => setTestTool(null)}>
                                {t("closeButton")}
                              </Button>
                            </div>
                            {testResult && (
                              <pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-md bg-secondary/40 p-3 font-mono text-xs text-muted-foreground">
                                {testResult}
                              </pre>
                            )}
                          </div>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
                <PaginationControls
                  page={toolsPagination.page}
                  totalPages={toolsPagination.totalPages}
                  totalItems={toolsPagination.totalItems}
                  pageSize={toolsPagination.pageSize}
                  onPageChange={toolsPagination.setPage}
                  onPageSizeChange={toolsPagination.setPageSize}
                  itemLabel={t("itemLabelTools")}
                />
              </div>
            )}
          </Card>
        );
      })}
      <PaginationControls
        page={sourcesPagination.page}
        totalPages={sourcesPagination.totalPages}
        totalItems={sourcesPagination.totalItems}
        pageSize={sourcesPagination.pageSize}
        onPageChange={sourcesPagination.setPage}
        onPageSizeChange={sourcesPagination.setPageSize}
        itemLabel={t("itemLabelSources")}
      />
    </div>
  );
}
