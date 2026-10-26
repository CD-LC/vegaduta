"use client";

import type { Dispatch, SetStateAction } from "react";
import Link from "next/link";
import { GitBranch } from "lucide-react";
import { Button } from "../ui/button";
import { cn } from "../../lib/utils";
import { CollapsibleSection } from "./AgentWorkbench/CollapsibleSection";
import { Notice } from "./AgentWorkbench/Notice";
import { CHIP, CHIP_DASHED, SUB_TEXT } from "./AgentWorkbench/styles";

/**
 * The "Code intelligence" section of the agent workbench: which registered code repos an
 * agent with the code-workspace capability may touch (deny-all until one is granted).
 * Extracted from AgentWorkbench.tsx unchanged; all state and the save handler stay in the
 * workbench, which owns the agent record and the load/save requests.
 */
export default function CodeIntelligenceSection({
  t,
  isDraft,
  locked,
  isFreePlan,
  agent,
  codeIntelToolsAvailable,
  fetchCodeIntelTools,
  editingRepos,
  setEditingRepos,
  repos,
  repoDraft,
  setRepoDraft,
  toggleRepoDraft,
  saveRepoAccess,
  reposBusy,
}: {
  t: (key: string, values?: any) => string;
  isDraft: boolean;
  locked: boolean;
  isFreePlan: boolean;
  agent: any;
  codeIntelToolsAvailable: boolean | "loading" | "error" | undefined;
  fetchCodeIntelTools: () => void;
  editingRepos: boolean;
  setEditingRepos: Dispatch<SetStateAction<boolean>>;
  repos: any;
  repoDraft: Set<string>;
  setRepoDraft: (v: Set<string>) => void;
  toggleRepoDraft: (repoId: string) => void;
  saveRepoAccess: () => void;
  reposBusy: boolean;
}) {
  const selectedAgent = agent;
  return (
    <CollapsibleSection icon={<GitBranch size={13} />} label={t("codeIntelligenceLabel")} cat="var(--cat-creative)">
      <p className={SUB_TEXT}>{t("codeIntelReadOnlyHint")}</p>{" "}
      <p className={cn("mt-1", SUB_TEXT)}>{t("codeWorkspaceExplainer")}</p>
      <p className={cn("mt-1", SUB_TEXT)}>{t("codeIntelToolsHint")}</p>
      {isDraft && <div className={cn("mt-3 italic", SUB_TEXT)}>{t("codeWorkspaceDraftHint")}</div>}
      {!isDraft && !selectedAgent.codeWorkspaceEnabled && (
        <div className={cn("mt-3 italic", SUB_TEXT)}>{t("codeWorkspaceEnableHint")}</div>
      )}
      {!isDraft && codeIntelToolsAvailable === false && (
        <div className="mt-3">
          <Notice>{t("codeIntelUnavailableBanner")}</Notice>
        </div>
      )}
      {!isDraft && codeIntelToolsAvailable === "error" && (
        <div className="mt-3">
          <Notice>
            {t("codeIntelCheckFailedBanner")}{" "}
            <button
              type="button"
              onClick={() => fetchCodeIntelTools()}
              className="font-medium underline underline-offset-2"
            >
              {t("retry")}
            </button>
          </Notice>
        </div>
      )}
      {!isDraft && codeIntelToolsAvailable === true && (
        <div className="mt-3">
          <div className="flex flex-wrap items-center gap-2">
            {selectedAgent.codeWorkspaceEnabled &&
              (selectedAgent.allowedCodeIntelRepoIds || []).length === 0 && (
              <span className={SUB_TEXT}>{t("noRepoGrantedWarning")}</span>
            )}
            {(selectedAgent.allowedCodeIntelRepoIds || []).map((repoId: string) => (
              <span className={CHIP} key={repoId}>
                <span className="text-muted-foreground">▤</span>
                {repoId}
              </span>
            ))}
            <button
              type="button"
              className={CHIP_DASHED}
              disabled={locked}
              title={locked ? t("editLockedTooltip") : undefined}
              onClick={() =>
                setEditingRepos((v) => {
                  const next = !v;
                  if (!next) {
                    // Closing without an explicit save - re-hydrate the
                    // draft from the agent's real saved repo access so an
                    // abandoned edit never survives a close.
                    setRepoDraft(new Set(selectedAgent?.allowedCodeIntelRepoIds || []));
                  }
                  return next;
                })
              }
            >
              {t("manageRepoAccess")}
            </button>
          </div>

          {editingRepos && (
            <div className="mt-3">
              {repos === "loading" && <p className={SUB_TEXT}>{t("loadingRepos")}</p>}
              {repos === "error" && <p className={SUB_TEXT}>{t("loadingReposError")}</p>}
              {Array.isArray(repos) && repos.length === 0 && (
                <p className={SUB_TEXT}>
                  {t("noRegisteredReposPrefix")}
                  <Link href="/code-intel" className="font-medium text-primary underline-offset-4 hover:underline">
                    {t("codeIntelPageLink")}
                  </Link>
                  .
                </p>
              )}
              {Array.isArray(repos) && repos.length > 0 && (
                <div className="flex flex-wrap gap-x-4 gap-y-2">
                  {repos.map((r) => (
                    <label key={r.repo_id} className="flex items-center gap-1.5 text-sm text-foreground">
                      <input
                        type="checkbox"
                        checked={repoDraft.has(r.repo_id)}
                        onChange={() => toggleRepoDraft(r.repo_id)}
                        disabled={locked}
                        className="accent-primary"
                      />
                      {r.repo_id}
                    </label>
                  ))}
                </div>
              )}
              <p className="mt-2 text-xs text-muted-foreground">
                {selectedAgent.codeWorkspaceEnabled ? t("denyAllRepoHint") : t("readOnlyRepoHint")}
              </p>
              <Button
                type="button"
                size="sm"
                className="mt-3"
                onClick={saveRepoAccess}
                disabled={reposBusy || locked}
                upgradeLocked={isFreePlan && locked}
                upgradeReason={t("changeModelFreePlanTooltip")}
                upgradeLabel={t("freeTierUpgradeButton")}
                title={locked ? t("editLockedTooltip") : undefined}
              >
                {reposBusy ? t("saving") : t("saveRepoAccess")}
              </Button>
            </div>
          )}
        </div>
      )}
    </CollapsibleSection>
  );
}
