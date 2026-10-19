"use client";

import { useCallback, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { api } from "../../../lib/api";
import { toast } from "../../../lib/toastBus";
import { requestExpressionFieldFocus } from "../ExpressionFocus";
import type { UnifiedLintIssue } from "../lintMerge";
import { NODE_WIDTH } from "../canvasOps";
import { existingApprovalBefore, insertApprovalBefore } from "../workflowPlan";
import { DEFAULT_EDGE_OPTIONS } from "../workflowCanvasHelpers";
import { NODE_HEIGHT_GUESS, PLACE_GAP_X, PLACE_GAP_Y } from "./constants";

type Setter = Dispatch<SetStateAction<any[]>>;

/**
 * The one-click fixes the lint chip offers. Graph fixes are one undo entry
 * each (addNode / deleteEdge / the commit below each push exactly one).
 */
export function useLintFixes(opts: {
  nodesRef: MutableRefObject<any[]>;
  edgesRef: MutableRefObject<any[]>;
  observingRef: MutableRefObject<boolean>;
  setNodes: Setter;
  setEdges: Setter;
  commitHistory: (coalesceMs?: number) => void;
  deleteEdge: (edgeId: string) => void;
  addNode: (type: any, position: any) => string | null;
  focusNodeFromLint: (nodeId: string) => void;
  setInsertEdgeId: (id: string | null) => void;
  setPendingConnection: (c: any) => void;
  setCmdOpen: (open: boolean) => void;
  bumpLint: () => void;
  push: (href: string) => void;
  tDescribe: (key: string) => string;
}) {
  const {
    nodesRef,
    edgesRef,
    observingRef,
    setNodes,
    setEdges,
    commitHistory,
    deleteEdge,
    addNode,
    focusNodeFromLint,
    setInsertEdgeId,
    setPendingConnection,
    setCmdOpen,
    bumpLint,
    push,
    tDescribe,
  } = opts;

  return useCallback(
    (issue: UnifiedLintIssue) => {
      const fix = issue.fix;
      if (!fix || observingRef.current) return;
      switch (fix.kind) {
        case "delete-edge":
          if (edgesRef.current.some((e) => e.id === fix.edgeId)) deleteEdge(fix.edgeId);
          break;
        case "add-node": {
          const list = nodesRef.current;
          const minX = list.length ? Math.min(...list.map((n) => n.position.x)) : 80 + NODE_WIDTH + PLACE_GAP_X;
          const y = list.length ? list.reduce((s, n) => s + n.position.y, 0) / list.length : 160;
          addNode(fix.type, { x: minX - NODE_WIDTH - PLACE_GAP_X, y });
          break;
        }
        case "add-error-route": {
          // Same flow as dragging off the red error port onto empty canvas:
          // the palette picks the handler, which lands wired to "error".
          const src = nodesRef.current.find((n) => n.id === fix.nodeId);
          if (!src) break;
          setInsertEdgeId(null);
          setPendingConnection({
            source: src.id,
            sourceHandle: "error",
            position: {
              x: src.position.x,
              y: src.position.y + (src.measured?.height ?? NODE_HEIGHT_GUESS) + PLACE_GAP_Y * 2,
            },
          });
          setCmdOpen(true);
          break;
        }
        case "set-config": {
          if (fix.value === undefined || fix.value === null || fix.value === "") {
            focusNodeFromLint(fix.nodeId);
            break;
          }
          const key = fix.path.slice("config.".length);
          commitHistory();
          setNodes((nds) =>
            nds.map((n) =>
              n.id === fix.nodeId
                ? { ...n, data: { ...n.data, config: { ...(n.data?.config || {}), [key]: fix.value } } }
                : n
            )
          );
          break;
        }
        case "open-config":
          // The drawer mounts after this; it consumes the request when the matching field appears.
          if (fix.field) requestExpressionFieldFocus(fix.nodeId, fix.field);
          focusNodeFromLint(fix.nodeId);
          break;
        case "enable-approval-policy":
          // Tenant-wide policy, not a graph edit: no undo entry. 403 = the
          // caller lacks MANAGE_APPROVALS, which is a "who to ask", not a failure.
          void api
            .setApprovalPolicy(fix.actionType, true, [403])
            .then(() => {
              toast.success("Approval is now required for this step type across your workspace.");
              bumpLint();
            })
            .catch((err: { status?: number; message?: string }) => {
              if (err?.status === 403)
                toast.info("Only a workspace admin can turn on approval for this step. Ask an admin.");
              else toast.error(err?.message || "Couldn't update the approval policy.");
            });
          break;
        case "insert-approval": {
          // Server lint "send-without-approval": put an approval step in front of the send.
          const gated = insertApprovalBefore(nodesRef.current, edgesRef.current, fix.insertBeforeNodeIds, {
            label: tDescribe("autoApprovalLabel"),
            message: tDescribe("autoApprovalMessage"),
            edgeDefaults: DEFAULT_EDGE_OPTIONS,
          });
          if (!gated.changed) {
            // Clicked again before the server lint caught up: the step is already gated.
            const already = existingApprovalBefore(nodesRef.current, edgesRef.current, fix.insertBeforeNodeIds);
            toast.info(tDescribe(already ? "lintApprovalAlready" : "lintApprovalNothing"));
            focusNodeFromLint(already ?? fix.nodeId);
            if (already) bumpLint();
            break;
          }
          commitHistory();
          setNodes(gated.nodes);
          setEdges(gated.edges);
          bumpLint();
          toast.success(tDescribe("lintApprovalAdded"));
          break;
        }
        case "open-url": {
          const external = /^https?:\/\//i.test(fix.url) && !fix.url.startsWith(`${window.location.origin}/`);
          if (external) window.open(fix.url, "_blank", "noopener,noreferrer");
          else push(fix.url);
          break;
        }
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refs and state setters are stable
    [deleteEdge, addNode, focusNodeFromLint, commitHistory, push, tDescribe]
  );
}
