// The canvas "Run" button's call, kept out of WorkflowCanvas so a test can pin what it sends.
// background: true - the POST returns the RUNNING row at once and the run continues server-side
// (a blocking request used to hit Cloudflare's 524 on long runs).
// mode "draft": the engine then runs the live graph and honours data pins. Dropping it silently turns
// every canvas run into a production-snapshot run that ignores the edit being tested, with no error.
export const CANVAS_RUN_MODE = "draft" as const;

type RunWorkflow = (
  workflowId: string,
  input: string | undefined,
  requestedBy: string | undefined,
  clientRunId: string,
  background?: boolean,
  mode?: "draft",
  simulateSends?: boolean,
) => Promise<any>;

// A draft run needs EDITOR on the server (viewers and runners get a 403), so the Run controls are hidden for
// them. accessLevel is absent from an older core: show the controls then, the server still enforces.
export function canRunDraft(workflow: { accessLevel?: string | null } | null | undefined): boolean {
  const level = workflow?.accessLevel;
  return level == null || level === "EDITOR";
}

export function startCanvasRun(
  runWorkflow: RunWorkflow,
  workflowId: string,
  input: string | undefined,
  clientRunId: string,
  simulateSends: boolean = false,
): Promise<any> {
  // Test run (nlflow W7): same draft run, but core simulates the sends. The extra argument is only passed
  // when asked for, so an ordinary Run sends exactly what it always did.
  if (simulateSends) return runWorkflow(workflowId, input, undefined, clientRunId, true, CANVAS_RUN_MODE, true);
  return runWorkflow(workflowId, input, undefined, clientRunId, true, CANVAS_RUN_MODE);
}
