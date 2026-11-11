// The adapter contract. A system under test is driven only through this interface, so the same
// task suite can be pointed at any system that someone writes an adapter for.
//
// An adapter module has a default export:
//
//   {
//     name: string,                              // "vegaduta"
//     async init(env): ctx,                      // read config from env (throw a clear Error when
//                                                // something required is missing); return opaque ctx
//     describe(ctx): { target, model? },         // non-secret provenance strings for the report
//     async runTask(task, ctx): RunResult,       // execute ONE task, see below
//     async close(ctx)?: void,                   // optional cleanup
//   }
//
// RunResult (every field optional except text; omit what the system cannot report, never fake it):
//
//   {
//     text: string,                              // the final answer (workflow: the final output)
//     toolCalls?: [{tool: string, args: object|string}],   // in call order
//     approvals?: [{actionType, status, finalStatus}],     // approval requests raised by this task
//     usage?: {promptTokens?, completionTokens?, totalTokens?, costUsd?, unpricedCalls?},
//     latencyMs?: number,   // answer time, when the run also does bookkeeping a user would not wait for; else the runner's wall clock
//     error?: string                             // set when the run failed to execute at all
//   }
//
// Latency is measured by the runner around runTask(); adapters must not report their own.
// Missing usage/cost stays missing and is reported as n/a, it is never counted as zero.

export function validateAdapter(a) {
  const errs = [];
  if (!a || typeof a !== "object") return ["adapter is not an object"];
  if (typeof a.name !== "string" || !a.name) errs.push("adapter.name must be a non-empty string");
  for (const fn of ["init", "describe", "runTask"]) if (typeof a[fn] !== "function") errs.push(`adapter.${fn} must be a function`);
  if (a.close !== undefined && typeof a.close !== "function") errs.push("adapter.close must be a function when present");
  return errs;
}

/** Returns problems with a RunResult (empty when well-formed). */
export function validateRunResult(r) {
  const errs = [];
  if (!r || typeof r !== "object") return ["RunResult is not an object"];
  if (!r.error && typeof r.text !== "string") errs.push("text must be a string when no error is set");
  if (r.toolCalls !== undefined) {
    if (!Array.isArray(r.toolCalls)) errs.push("toolCalls must be an array");
    else if (r.toolCalls.some((c) => !c || typeof c.tool !== "string")) errs.push("every toolCall needs a string `tool`");
  }
  if (r.approvals !== undefined && !Array.isArray(r.approvals)) errs.push("approvals must be an array");
  if (r.latencyMs !== undefined && (typeof r.latencyMs !== "number" || r.latencyMs < 0)) errs.push("latencyMs must be a non-negative number");
  if (r.usage !== undefined) {
    for (const k of ["promptTokens", "completionTokens", "totalTokens", "costUsd", "unpricedCalls"]) {
      if (r.usage[k] !== undefined && typeof r.usage[k] !== "number") errs.push(`usage.${k} must be a number`);
    }
  }
  return errs;
}
