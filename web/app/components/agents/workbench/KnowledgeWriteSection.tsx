"use client";

import { useEffect, useState } from "react";
import { Pencil } from "lucide-react";
import { api } from "../../../lib/api";
import { SUB_TEXT } from "../AgentWorkbench/styles";

interface Collection {
  id: string;
  name?: string;
}

interface WriteState {
  enabled: boolean;
  grants: Set<string>;
}

/**
 * The knowledge-WRITE half of the knowledge section: the agent's add_to_knowledge_base opt-in
 * (knowledgeWriteEnabled) plus its deny-by-default writable-collection grant list, backed by
 * GET/PATCH /api/agents/{id}/knowledge-write-access (+ /knowledge-write). Self-contained - it
 * fetches its own state for the selected agent rather than threading through the workbench
 * context, same pattern the capabilities section uses. Existing agents only: a draft has no id to
 * patch, and the flag defaults off (configurable right after creation). Each control persists
 * immediately (toggle -> /knowledge-write, checkbox -> /knowledge-write-access), the same
 * live-save posture the capability toggles take.
 */
export default function KnowledgeWriteSection({
  t,
  locked,
  agentId,
  collections,
}: {
  t: (key: string, values?: any) => string;
  locked: boolean;
  agentId: string | null;
  collections: Collection[];
}) {
  const [state, setState] = useState<undefined | "loading" | "error" | WriteState>(undefined);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!agentId) {
      setState(undefined);
      return;
    }
    setState("loading");
    api
      .getAgentKnowledgeWriteAccess(agentId)
      .then((res) =>
        setState({
          enabled: !!res?.knowledgeWriteEnabled,
          grants: new Set(res?.allowedKnowledgeWriteCollectionIds || []),
        })
      )
      // Older cores without the V198 endpoints: the section shows its error
      // state rather than pretending write is off.
      .catch(() => setState("error"));
  }, [agentId]);

  if (!agentId) return null;

  function save(patch: (s: WriteState) => WriteState, call: () => Promise<unknown>) {
    if (!agentId || busy || typeof state !== "object") return;
    setBusy(true);
    call()
      .then(() => setState(patch(state)))
      .catch(() => setState("error"))
      .finally(() => setBusy(false));
  }

  function toggleEnabled(checked: boolean) {
    save(
      (s) => ({ ...s, enabled: checked }),
      () => api.setAgentKnowledgeWrite(agentId!, checked)
    );
  }

  function toggleGrant(collectionId: string) {
    const next = new Set(typeof state === "object" ? state.grants : []);
    if (next.has(collectionId)) next.delete(collectionId);
    else next.add(collectionId);
    save(
      () => ({ ...(state as WriteState), grants: next }),
      () => api.setAgentKnowledgeWriteAccess(agentId!, Array.from(next))
    );
  }

  const loaded = typeof state === "object";

  return (
    <div className="mt-4 border-t border-border pt-3">
      <label className="flex items-center gap-2 text-sm text-foreground">
        <input
          type="checkbox"
          className="size-4 accent-primary"
          disabled={locked || busy || !loaded}
          checked={loaded ? (state as WriteState).enabled : false}
          onChange={(e) => toggleEnabled(e.target.checked)}
        />
        <Pencil size={12} className="text-muted-foreground" />
        {t("knowledgeWriteLabel")}
      </label>
      {state === "error" && <p className={`mt-2 ${SUB_TEXT}`}>{t("knowledgeWriteLoadError")}</p>}
      {loaded && (state as WriteState).enabled && (
        <div className="mt-2">
          {collections.length > 0 && (
            <div className="flex flex-wrap gap-x-4 gap-y-2">
              {collections.map((c) => (
                <label key={c.id} className="flex items-center gap-1.5 text-sm text-foreground">
                  <input
                    type="checkbox"
                    className="size-4 accent-primary"
                    disabled={locked || busy}
                    checked={(state as WriteState).grants.has(c.id)}
                    onChange={() => toggleGrant(c.id)}
                  />
                  {c.name}
                </label>
              ))}
            </div>
          )}
          <p className="mt-2 text-xs text-muted-foreground">{t("writableCollectionsHint")}</p>
        </div>
      )}
    </div>
  );
}
