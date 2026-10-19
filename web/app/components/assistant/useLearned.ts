"use client";

import { useCallback, useEffect, useState } from "react";
import { assistantApi, type LearningProposal } from "../../lib/api/assistant";
import { memoryApi } from "../../lib/api/memory";
import { normalizePage, skillLibraryApi, type LibrarySkill } from "../../lib/api/skillLibrary";
import type { MemoryFact } from "../../memory/memoryTypes";

/** How many remembered facts the rail lists; the Memory page has the rest. */
export const LEARNED_FACTS_SHOWN = 6;

export interface Learned {
  /** null until the first read. */
  loaded: boolean;
  /** Skills the assistant drafted from its own work, waiting for approval. */
  pending: LibrarySkill[];
  /** Approved skills the assistant wrote itself (origin LEARNED), most recently changed first. */
  active: LibrarySkill[];
  /** Facts remembered about the person, newest first. */
  facts: MemoryFact[];
  /** Lessons, preferences and the like the assistant proposes to learn, waiting for a yes. */
  proposals: LearningProposal[];
  /** Approves one, with the person's edit of its text when given. Rejects with the server's reason (409). */
  approveProposal: (id: string, body?: string) => Promise<void>;
  rejectProposal: (id: string) => Promise<void>;
  approve: (id: string) => Promise<void>;
  reject: (id: string) => Promise<void>;
  forgetFact: (fact: MemoryFact) => Promise<void>;
  editFact: (fact: MemoryFact, content: string) => Promise<void>;
}

const byRecent = (a: LibrarySkill, b: LibrarySkill) => Date.parse(b.updatedAt || "") - Date.parse(a.updatedAt || "");

/**
 * What the assistant has learned, for the "Learned" section of the work rail: proposals to learn lessons and preferences (stored PENDING by the learning-proposal store), skills it drafted (the
 * skill-learning loop stores them PENDING; nothing reaches a prompt before the person approves), the
 * ones already approved, and the facts it remembered (remember_fact). Re-read when a turn settles,
 * since that is when a lesson can have been written. Every read is quiet and an outage leaves the
 * lists empty rather than raising a toast.
 */
export function useLearned(enabled: boolean, refreshKey: unknown): Learned {
  const [state, setState] = useState<Pick<Learned, "loaded" | "pending" | "active" | "facts" | "proposals">>({
    loaded: false,
    pending: [],
    active: [],
    facts: [],
    proposals: [],
  });

  const read = useCallback(async () => {
    const [pending, active, memory, proposals] = await Promise.all([
      assistantApi.listSkillsByStatusQuiet("PENDING").catch((): null => null),
      assistantApi.listSkillsByStatusQuiet("ACTIVE").catch((): null => null),
      memoryApi.getMemoryQuiet().catch((): null => null),
      assistantApi.listLearningProposalsQuiet().catch((): LearningProposal[] => []),
    ]);
    return {
      loaded: true,
      pending: normalizePage(pending).items.filter((s) => s.status === "PENDING").sort(byRecent),
      active: normalizePage(active)
        .items.filter((s) => s.status === "ACTIVE" && s.origin === "LEARNED")
        .sort(byRecent),
      facts: (memory?.facts ?? []).slice(0, LEARNED_FACTS_SHOWN),
      proposals,
    };
  }, []);

  useEffect(() => {
    if (!enabled) return undefined;
    let live = true;
    void read().then((next) => {
      if (live) setState(next);
    });
    return () => {
      live = false;
    };
  }, [enabled, refreshKey, read]);

  const refresh = useCallback(async () => setState(await read()), [read]);

  return {
    ...state,
    approve: async (id) => {
      await skillLibraryApi.approveLibrarySkill(id);
      await refresh();
    },
    approveProposal: async (id, body) => {
      await assistantApi.approveLearningProposal(id, body);
      await refresh();
    },
    rejectProposal: async (id) => {
      await assistantApi.rejectLearningProposal(id);
      await refresh();
    },
    reject: async (id) => {
      await skillLibraryApi.rejectLibrarySkill(id);
      await refresh();
    },
    forgetFact: async (fact) => {
      await memoryApi.deleteMemoryFact(fact.scope, fact.id);
      await refresh();
    },
    editFact: async (fact, content) => {
      await memoryApi.editMemoryFact(fact.scope, fact.id, content);
      await refresh();
    },
  };
}
