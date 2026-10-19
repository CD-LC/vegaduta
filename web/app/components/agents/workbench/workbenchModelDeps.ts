// The state and setters the workbench model hooks read: everything the earlier workbench hooks
// return (see useWorkbenchController), passed in as one `prev` bag.

import type { useAgentDraft } from "./useAgentDraft";
import type { useAgentSave } from "./useAgentSave";
import type { useAgentValidation } from "./useAgentValidation";
import type { useWorkbenchAccess } from "./useWorkbenchAccess";
import type { useWorkbenchBase } from "./useWorkbenchBase";
import type { useWorkbenchEditors } from "./useWorkbenchEditors";
import type { useWorkbenchRouting } from "./useWorkbenchRouting";
import type { useWorkbenchTenant } from "./useWorkbenchTenant";

export type WorkbenchModelDeps = ReturnType<typeof useWorkbenchBase> &
  ReturnType<typeof useWorkbenchTenant> &
  ReturnType<typeof useAgentDraft> &
  ReturnType<typeof useWorkbenchAccess> &
  ReturnType<typeof useWorkbenchRouting> &
  ReturnType<typeof useAgentValidation> &
  ReturnType<typeof useAgentSave> &
  ReturnType<typeof useWorkbenchEditors>;
