// Access gating for editor-level workflow actions (history restore/promote/rollback, comments).
// The server enforces EDITOR regardless; this only hides controls a viewer or runner cannot use.
// accessLevel is absent from an older core: show the controls then.
export function canEditWorkflow(workflow: { accessLevel?: string | null } | null | undefined): boolean {
  const level = workflow?.accessLevel;
  return level == null || level === "EDITOR";
}
