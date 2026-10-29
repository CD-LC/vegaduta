"use client";

import { BookOpen, GitBranch, MessageCircle, PartyPopper, Play, Wand2 } from "lucide-react";
import Link from "next/link";
import { motion } from "motion/react";
import { Button } from "../../components/ui/button";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import PoweredByChip, { inferSource } from "../../components/PoweredByChip";
import type { Blueprint } from "../../lib/build/blueprint";

/** Stage 4: the thing is built; offer the next steps. */
export default function DoneStage({
  bp,
  builtEngine,
  patchNote,
  briefId,
  projectId,
  onOpenApp,
  onReset: reset,
}: {
  bp: Blueprint;
  builtEngine: { providerName: string | null; model: string | null } | null;
  patchNote: string | null;
  briefId: string | null;
  projectId: string | null;
  onOpenApp: (projectId: string) => void;
  onReset: () => void;
}) {
  const noun = bp.output === "app" ? "app" : "agent";
  return (
    <motion.div
      key="done"
      initial={{ opacity: 0, scale: 0.98 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.25 }}
      className="space-y-5"
    >
      <div className="relative overflow-hidden rounded-2xl border border-emerald-500/30 bg-emerald-500/10 p-5">
        <div className="flex items-center gap-3">
          <motion.span
            initial={{ scale: 0, rotate: -30 }}
            animate={{ scale: 1, rotate: 0 }}
            transition={{ type: "spring", stiffness: 260, damping: 14, delay: 0.1 }}
            className="flex size-12 items-center justify-center rounded-2xl bg-emerald-500 text-white"
          >
            <PartyPopper aria-hidden="true" className="size-6" />
          </motion.span>
          <div>
            <h2 className="text-xl font-semibold tracking-tight text-foreground">
              {bp.output === "agent" ? `${bp.agent.name} is live` : `${bp.app.name} is in your Git`}
            </h2>
            <p className="text-sm text-muted-foreground">
              {bp.output === "agent"
                ? "It starts under watch and earns more freedom as it proves itself."
                : "The starter is committed and a pull request is open."}
            </p>
            {/* What powers the thing just built - the agent's resolved
             * provider/model (best-effort lookup in applyPatches; hidden
             * when the read failed rather than guessed). */}
            {bp.output === "agent" && builtEngine && (builtEngine.providerName || builtEngine.model) && (
              <div className="mt-2">
                <PoweredByChip
                  source={inferSource(builtEngine.providerName)}
                  provider={builtEngine.providerName}
                  model={builtEngine.model}
                  prefix="Runs on"
                />
              </div>
            )}
          </div>
        </div>
      </div>

      {patchNote && (
        <Alert>
          <AlertDescription>{patchNote}</AlertDescription>
        </Alert>
      )}

      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">What next</p>
        <div className="grid gap-2 sm:grid-cols-3">
          {bp.output === "agent" && briefId && (
            <Link
              href={`/build/${briefId}`}
              className="group flex flex-col gap-1 rounded-xl border border-primary/50 bg-primary/10 p-3 transition-all hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <Play aria-hidden="true" className="size-4 text-primary" /> Open your {noun}
              </span>
              <span className="text-xs text-muted-foreground">See it, chat with it and check its track record.</span>
            </Link>
          )}
          {bp.output === "agent" && bp.agent.knowledgeBase && (
            <Link
              href="/knowledge"
              className="group flex flex-col gap-1 rounded-xl border border-border bg-card p-3 transition-all hover:-translate-y-0.5 hover:border-primary/60 hover:shadow-md"
            >
              <span className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <BookOpen aria-hidden="true" className="size-4 text-primary" /> Add your documents
              </span>
              <span className="text-xs text-muted-foreground">So answers come from your own material.</span>
            </Link>
          )}
          {bp.output === "agent" && (
            <Link
              href="/admin/channels"
              className="group flex flex-col gap-1 rounded-xl border border-border bg-card p-3 transition-all hover:-translate-y-0.5 hover:border-primary/60 hover:shadow-md"
            >
              <span className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <MessageCircle aria-hidden="true" className="size-4 text-primary" /> Put it on a channel{" "}
                <Badge variant="outline">optional</Badge>
              </span>
              <span className="text-xs text-muted-foreground">WhatsApp, Telegram or email - when you are ready.</span>
            </Link>
          )}
          {bp.output === "app" && projectId && (
            <button
              type="button"
              onClick={() => onOpenApp(projectId)}
              className="flex flex-col gap-1 rounded-xl border border-primary/50 bg-primary/10 p-3 text-start transition-all hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <GitBranch aria-hidden="true" className="size-4 text-primary" /> Open in Custom apps
              </span>
              <span className="text-xs text-muted-foreground">
                Preview it, chat to change it, review the pull request.
              </span>
            </button>
          )}
        </div>
      </div>

      <Button variant="outline" onClick={reset}>
        <Wand2 aria-hidden="true" className="size-4" /> Build something else
      </Button>
    </motion.div>
  );
}
