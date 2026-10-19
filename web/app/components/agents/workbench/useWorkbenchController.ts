import type { WorkbenchProps } from "./types";
import { useAgentDraft } from "./useAgentDraft";
import { useAgentSave } from "./useAgentSave";
import { useAgentValidation } from "./useAgentValidation";
import { useWorkbenchAccess } from "./useWorkbenchAccess";
import { useWorkbenchBase } from "./useWorkbenchBase";
import { useWorkbenchEditors } from "./useWorkbenchEditors";
import { useWorkbenchModel } from "./useWorkbenchModel";
import { useWorkbenchRouting } from "./useWorkbenchRouting";
import { useWorkbenchTenant } from "./useWorkbenchTenant";
import { useWorkbenchTuning } from "./useWorkbenchTuning";
import { useWorkbenchView } from "./useWorkbenchView";

/** Composes the workbench feature hooks in dependency order into one controller object. */
export function useWorkbenchController(props: WorkbenchProps) {
  const g0 = useWorkbenchBase(props);
  const g1 = useWorkbenchTenant(props, { ...g0 });
  const g2 = useAgentDraft(props);
  const g3 = useWorkbenchAccess(props, { ...g0, ...g1, ...g2 });
  const g4 = useWorkbenchRouting(props, { ...g0, ...g1, ...g2, ...g3 });
  const g5 = useAgentValidation(props, { ...g0, ...g1, ...g2, ...g3, ...g4 });
  const g6 = useAgentSave(props, { ...g0, ...g1, ...g2, ...g3, ...g4, ...g5 });
  const g7 = useWorkbenchEditors(props, { ...g0, ...g1, ...g2, ...g3, ...g4, ...g5, ...g6 });
  const g8 = useWorkbenchModel(props, { ...g0, ...g1, ...g2, ...g3, ...g4, ...g5, ...g6, ...g7 });
  const g9 = useWorkbenchTuning(props, { ...g0, ...g1, ...g2, ...g3, ...g4, ...g5, ...g6, ...g7, ...g8 });
  const g10 = useWorkbenchView(props, { ...g0, ...g1, ...g2, ...g3, ...g4, ...g5, ...g6, ...g7, ...g8, ...g9 });
  return { ...g0, ...g1, ...g2, ...g3, ...g4, ...g5, ...g6, ...g7, ...g8, ...g9, ...g10 };
}

export type WorkbenchController = ReturnType<typeof useWorkbenchController>;
