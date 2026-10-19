import { useEffect, type MutableRefObject } from "react";
import { API_BASE } from "../../lib/api";
import type { PreviewEnv, SelectedElement } from "./runtime";
import type { StudioProject } from "./store";
import type { RuntimeErrorReport } from "./scope";
import type { SidePanel } from "./sessionTypes";
import type { Updater } from "./sessionReducer";
import { handleAppCall } from "./appBridge";

/**
 * The studio side of the preview iframe's postMessage channel (split out of
 * useProjectSession): saved app data, runtime errors, tap-to-select, and the
 * vd.* service calls the sandboxed app cannot make itself. Only messages from
 * THIS preview frame are honoured.
 */
export function usePreviewBridge({
  frameRef,
  envRef,
  publishSlugRef,
  updateRaw,
  setRuntimeError,
  setSelected,
  setPanel,
  setPendingAuth,
}: {
  frameRef: MutableRefObject<HTMLIFrameElement | null>;
  envRef: MutableRefObject<PreviewEnv>;
  publishSlugRef: MutableRefObject<string>;
  updateRaw: (fn: (p: StudioProject) => StudioProject) => void;
  setRuntimeError: (r: RuntimeErrorReport | null) => void;
  setSelected: (v: Updater<SelectedElement | null>) => void;
  setPanel: (v: Updater<SidePanel>) => void;
  setPendingAuth: (v: { slug: string } | null) => void;
}) {
  useEffect(() => {
    const onMsg = (e: MessageEvent) => {
      if (!frameRef.current || e.source !== frameRef.current.contentWindow) return;
      const d = e.data;
      if (!d || typeof d !== "object") return;
      // Simulated conditions for vd.* calls: offline fails them, a slow
      // network delays the reply (the app's own fetch() is handled in the harness).
      const gate = (win: Window, q: number, run: () => void) => {
        const cur = envRef.current;
        if (cur.offline) {
          win.postMessage({ vdStudio: "vdReply", q, ok: false, text: "You're offline (simulated in the preview)." }, "*");
          return;
        }
        if (cur.delayMs > 0) setTimeout(run, cur.delayMs);
        else run();
      };
      if (d.vdStudio === "storage" && d.data && typeof d.data === "object") {
        const json = JSON.stringify(d.data);
        // Mirrors even for viewers: it is this browser's own scratch copy, never pushed.
        if (json.length < 200_000) updateRaw((p) => ({ ...p, appData: d.data }));
      } else if (d.vdStudio === "error" && typeof d.message === "string") {
        setRuntimeError({
          message: d.message,
          line: typeof d.line === "number" ? d.line : 0,
          stack: typeof d.stack === "string" ? d.stack : "",
        });
      } else if (d.vdStudio === "select" && Array.isArray(d.path)) {
        setSelected({
          path: d.path.filter((n: unknown) => typeof n === "number"),
          tag: String(d.tag || ""),
          text: String(d.text || ""),
          html: String(d.html || ""),
          color: String(d.color || ""),
          background: String(d.background || ""),
          fontSize: String(d.fontSize || ""),
        });
        setPanel("element");
      } else if (d.vdStudio === "vdCall" && typeof d.id === "string" && typeof d.q === "number") {
        // Connected-services bridge. The app is sandboxed (opaque origin,
        // connect-src 'none' on the share host) so it cannot call the API
        // itself - it posts here and we call from this allowlisted origin.
        // Only the public share endpoints are reachable; the shareId the app
        // carries is the whole credential, same as the /agent/[shareId] pages.
        const win = e.source as Window | null;
        if (!win) return;
        const wf = d.kind === "workflow";
        gate(win, d.q, () => {
          fetch(
            `${API_BASE}/api/share/${wf ? "workflows" : "agents"}/${encodeURIComponent(d.id)}/${wf ? "run" : "chat"}`,
            {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ message: String(d.msg ?? "") }),
            }
          )
            .then(async (r): Promise<void> => {
              const j = await r.json().catch((): null => null);
              const text =
                typeof j?.reply === "string" && j.reply ? j.reply
                : typeof j?.resultText === "string" && j.resultText ? j.resultText
                : typeof j?.errorMessage === "string" && j.errorMessage ? `It reported: ${j.errorMessage}`
                : `The service answered HTTP ${r.status}.`;
              win.postMessage({ vdStudio: "vdReply", q: d.q, ok: r.ok, text }, "*");
            })
            .catch(() =>
              win.postMessage({ vdStudio: "vdReply", q: d.q, ok: false, text: "Couldn't reach the platform API." }, "*")
            );
        });
      } else if (d.vdStudio === "vdCall" && typeof d.q === "number" && (d.kind === "call" || d.kind === "ai" || d.kind === "auth")) {
        // Published-app bridge: vd.call(alias)/vd.ai.chat/vd.auth.*. Same
        // postMessage hop as the share calls above, but against
        // /api/public/apps/{slug}/... - and the app's end-user token rides
        // as a Bearer header the app itself never sees.
        const win = e.source as Window | null;
        if (!win) return;
        gate(win, d.q, () => {
          void handleAppCall({ ...d, slug: publishSlugRef.current }, (res) => win.postMessage({ vdStudio: "vdReply", q: d.q, ...res }, "*"), {
            requestSignIn: (slug) => setPendingAuth({ slug }),
          });
        });
      }
    };
    window.addEventListener("message", onMsg);
    return () => window.removeEventListener("message", onMsg);
  }, [updateRaw, setPanel, setSelected]); // eslint-disable-line react-hooks/exhaustive-deps -- refs and stable setters, same as before the split
}
