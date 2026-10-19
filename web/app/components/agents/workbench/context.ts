import { createContext, useContext } from "react";
import type { WorkbenchController } from "./useWorkbenchController";

export const WorkbenchContext = createContext<WorkbenchController | null>(null);

/** The workbench controller (all draft/edit state + handlers) for section components. */
export function useWorkbench(): WorkbenchController {
  const ctx = useContext(WorkbenchContext);
  if (!ctx) throw new Error("useWorkbench must be used inside <WorkbenchContext.Provider>");
  return ctx;
}
