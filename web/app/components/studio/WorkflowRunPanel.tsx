"use client";

import { motion, AnimatePresence } from "motion/react";
import { Bot, Send, ThumbsDown, ThumbsUp, UserCheck } from "lucide-react";
import { Button } from "../ui/button";
import TraceView from "./TraceView";
import type { WorkflowDefinition } from "./studioClient";
import type { RunState } from "./workflowRun";
import { useTranslations } from "../../lib/i18n";

// What a studio workflow run shows below the canvas: the real approval pause
// (Approve / Reject) and, per agent step, its tool trace and output, then the
// delivered result.

export default function WorkflowRunPanel({
  run,
  def,
  deciding,
  agentName,
  decide,
}: {
  run: RunState;
  def: WorkflowDefinition;
  deciding: boolean;
  agentName: (id: unknown) => string;
  decide: (approved: boolean) => void;
}) {
    const tHC = useTranslations("hcComponentsStudioWorkflowRunPanel");
  return (
    <>
      {/* Approval: the real pause */}
      <AnimatePresence>
        {run.approval && (
          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 8 }}
            className="rounded-xl border-2 border-amber-500/60 bg-amber-500/5 p-4"
          >
            <div className="mb-1 flex items-center gap-2 font-medium">
              <UserCheck aria-hidden="true" className="h-4 w-4 text-amber-600 shrink-0" /> {tHC("s4")}
            </div>
            <p className="mb-3 text-sm text-muted-foreground">
              {run.approval.message} {tHC("s3")}
            </p>
            {run.approval.preview && (
              <pre className="mb-3 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded bg-background p-3 text-xs">
                {run.approval.preview}
              </pre>
            )}
            <div className="flex gap-2">
              <Button onClick={() => decide(true)} disabled={deciding}>
                <ThumbsUp aria-hidden="true" className="me-2 h-4 w-4 shrink-0" /> Approve
              </Button>
              <Button variant="outline" onClick={() => decide(false)} disabled={deciding}>
                <ThumbsDown aria-hidden="true" className="me-2 h-4 w-4 shrink-0" /> Reject
              </Button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Step detail: trace + output per agent step */}
      {Object.keys(run.steps).length > 0 && (
        <div className="space-y-3">
          {def.steps
            .filter((s) => s.type === "agent")
            .map((s) => {
              const st = run.steps[s.id];
              if (!st || st.status === "idle") return null;
              return (
                <div key={s.id} className="rounded-xl border border-border/60 p-4">
                  <div className="mb-2 flex min-w-0 flex-wrap items-center gap-2 text-sm font-medium">
                    <Bot aria-hidden="true" className="h-4 w-4 shrink-0" />{" "}
                    <span className="min-w-0 truncate">{s.label}</span>{" "}
                    <span className="min-w-0 truncate text-xs font-normal text-muted-foreground">
                      · {agentName(s.config.agentId)}
                    </span>
                    {st.ms != null && (
                      <span className="ms-auto text-xs text-muted-foreground">{(st.ms / 1000).toFixed(1)} s</span>
                    )}
                  </div>
                  <div className="grid gap-3 md:grid-cols-5">
                    <div className="md:col-span-2">
                      <TraceView
                        events={st.events}
                        running={st.status === "running"}
                        emptyHint="No tool calls in this step."
                      />
                    </div>
                    <div className="md:col-span-3">
                      {st.output ? (
                        <div className="whitespace-pre-wrap break-all rounded-lg bg-muted/40 p-3 text-sm leading-relaxed">
                          {st.output}
                        </div>
                      ) : st.status === "running" ? (
                        <div className="rounded-lg bg-muted/40 p-3 text-sm text-muted-foreground animate-pulse">
                          {tHC("s2")}
                        </div>
                      ) : null}
                    </div>
                  </div>
                </div>
              );
            })}
          {run.status === "COMPLETED" && run.output && (
            <div className="rounded-xl border border-primary/40 bg-primary/5 p-4">
              <div className="mb-1 flex items-center gap-2 text-sm font-medium">
                <Send aria-hidden="true" className="h-4 w-4 shrink-0" /> Delivered
              </div>
              <div className="whitespace-pre-wrap break-all text-sm leading-relaxed">{run.output}</div>
              <p className="mt-2 text-xs text-muted-foreground">
                {tHC("s1")}
              </p>
            </div>
          )}
        </div>
      )}
    </>
  );
}
