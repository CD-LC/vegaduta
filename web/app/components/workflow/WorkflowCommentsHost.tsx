"use client";

/**
 * Mounts the canvas comment layer inside the React Flow stage. Render it as a
 * child of the stage element (position: relative) and inside the
 * ReactFlowProvider; it covers the stage, lets pointer events through except
 * on its own controls, and does nothing until `open`.
 *
 * Props: workflowId; accessLevel (optional gate for adding comments); open (the topbar Comments toggle); onClose; nodeLabel
 * (id -> display name); onFocusNode (pan/select a node). The comments API
 * feature-detects: on a core without the route nothing renders.
 */

import { useCallback, useRef, useState } from "react";
import { useReactFlow, useViewport } from "@xyflow/react";
import { List, MessageSquarePlus, X } from "lucide-react";
import { Button } from "../ui/button";
import { useAuth } from "../AuthProvider";
import { useTranslations } from "../../lib/i18n";
import CommentLayer, { useWorkflowComments, type PinTarget, type ScreenPoint } from "./CommentLayer";
import type { NewThreadTarget } from "./comments";
import { NODE_WIDTH } from "./canvasOps";
import { canEditWorkflow } from "./workflowAccess";

export type WorkflowCommentsHostProps = {
  workflowId: string;
  open: boolean;
  onClose: () => void;
  nodeLabel: (nodeId: string) => string;
  onFocusNode: (nodeId: string) => void;
  /** The workflow's accessLevel; anything but EDITOR hides "Add comment" (existing threads stay readable). */
  accessLevel?: string | null;
};

export default function WorkflowCommentsHost({ workflowId, open, onClose, nodeLabel, onFocusNode, accessLevel }: WorkflowCommentsHostProps) {
  const canComment = canEditWorkflow({ accessLevel });
  const t = useTranslations("workflowStudioUi");
  const { username } = useAuth();
  const rf = useReactFlow();
  // Re-render on pan/zoom so pins follow the graph.
  const viewport = useViewport();
  const { threads, setThreads, status } = useWorkflowComments(workflowId, { enabled: open });
  const wrapRef = useRef<HTMLDivElement>(null);
  const [openThreadId, setOpenThreadId] = useState<string | null>(null);
  const [mode, setMode] = useState<"view" | "place">("view");
  const [newThread, setNewThread] = useState<NewThreadTarget | null>(null);
  const [panelOpen, setPanelOpen] = useState(false);

  const pinScreenPos = useCallback(
    (target: PinTarget): ScreenPoint | null => {
      const box = wrapRef.current?.getBoundingClientRect();
      if (!box) return null;
      let flow: { x: number; y: number };
      if (typeof target === "string") {
        const node = rf.getNode(target);
        if (!node) return null;
        flow = { x: node.position.x + (node.measured?.width ?? NODE_WIDTH), y: node.position.y };
      } else {
        flow = target;
      }
      const p = rf.flowToScreenPosition(flow);
      return { x: p.x - box.left, y: p.y - box.top };
    },
    // viewport identity changes on pan/zoom, giving the layer a new function.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [rf, viewport]
  );

  if (!open || status === "unavailable") return null;

  const focusTarget = (target: PinTarget) => {
    if (typeof target === "string") onFocusNode(target);
    else void rf.setCenter(target.x, target.y, { duration: 400 });
  };

  return (
    <div ref={wrapRef} className="pointer-events-none absolute inset-0 z-30">
      <CommentLayer
        workflowId={workflowId}
        threads={threads}
        onThreadsChange={setThreads}
        openThreadId={openThreadId}
        onOpenThread={setOpenThreadId}
        pinScreenPos={pinScreenPos}
        currentUserName={username ?? undefined}
        mode={mode}
        onPlaced={(x, y) => {
          setMode("view");
          setNewThread(rf.screenToFlowPosition({ x, y }));
        }}
        onCancelPlace={() => setMode("view")}
        newThread={newThread}
        onNewThreadDone={() => setNewThread(null)}
        panelOpen={panelOpen}
        onPanelOpenChange={setPanelOpen}
        nodeLabel={nodeLabel}
        onFocusTarget={focusTarget}
      />
      <div className="pointer-events-auto absolute left-1/2 top-2 z-40 flex -translate-x-1/2 items-center gap-1.5 rounded-lg border border-border bg-card p-1 shadow-sm">
        {canComment && (
          <Button type="button" size="sm" variant={mode === "place" ? "default" : "outline"} onClick={() => setMode((m) => (m === "place" ? "view" : "place"))}>
            <MessageSquarePlus className="size-3.5" aria-hidden="true" />
            {t("addComment")}
          </Button>
        )}
        <Button type="button" size="sm" variant="outline" aria-pressed={panelOpen} onClick={() => setPanelOpen((v) => !v)}>
          <List className="size-3.5" aria-hidden="true" />
          {t("commentsList", { count: threads.length })}
        </Button>
        <Button type="button" size="icon" variant="ghost" className="size-8" onClick={onClose} aria-label={t("commentsHide")}>
          <X className="size-4" aria-hidden="true" />
        </Button>
      </div>
    </div>
  );
}
