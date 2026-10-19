import type { MutableRefObject } from "react";
import { api } from "../../lib/api";
import { addVersion, currentHtml, titleOf, type StudioAiConfig, type StudioAuthMode, type StudioConnection, type StudioProject } from "./store";
import { writeConnections } from "./connect";
import { aliasOf } from "./payload";
import { withAliases, type LogEntry } from "./sessionTypes";

/**
 * Connected services, sign-in / AI settings and publishing for one studio
 * project (split out of useProjectSession). Every change re-splices the
 * runtime connections block so the app, the preview and the publish payload
 * always carry the same config.
 */
export function useProjectPublishing({
  project,
  projectRef,
  update,
  isOwner,
  pushLog,
}: {
  project: StudioProject;
  projectRef: MutableRefObject<StudioProject>;
  update: (fn: (p: StudioProject) => StudioProject) => void;
  isOwner: boolean;
  pushLog: (e: Omit<LogEntry, "id">) => void;
}) {
  // Connect panel: attach an agent/workflow by minting (or reusing) its
  // PUBLIC share link, then splice the connections block into the app so the
  // floating panel and window.vd.* exist with no AI round-trip at all.
  const attachConnection = async (kind: "agent" | "workflow", id: string, name: string) => {
    const status = kind === "agent" ? await api.getAgentShareStatus(id) : await api.getWorkflowShareStatus(id);
    const share =
      status?.active && status.shareId
        ? status
        : kind === "agent"
          ? await api.createAgentShare(id)
          : await api.createWorkflowShare(id);
    if (!share?.shareId) throw new Error("Couldn't get a share link for it.");
    // The share lookup above awaited - build the new list from the project as
    // it is NOW (inside the updater), not from the render this click started in.
    update((p) => {
      const others = (p.connections || []).filter((c) => !(c.kind === kind && c.id === id));
      const existing = (p.connections || []).find((c) => c.kind === kind && c.id === id);
      const taken = new Set(others.map((c) => c.alias).filter(Boolean) as string[]);
      // Re-attaching keeps the alias and the contract the person already configured.
      const cxn: StudioConnection = {
        ...existing,
        kind, id, shareId: String(share.shareId), name,
        alias: existing?.alias || aliasOf(name, taken),
      };
      const conns = [...others, cxn];
      return addVersion({ ...p, connections: conns }, writeConnections(currentHtml(p), conns, { publish: p.publish, auth: p.auth, ai: p.ai }), `Connected ${kind} "${name}"`, "connect");
    });
    pushLog({
      kind: "result",
      text: `Connected "${name}" - tap the ✦ button in the app to use it, or ask me to wire it into the UI.`,
    });
  };

  const detachConnection = (kind: "agent" | "workflow", id: string) => {
    const c = (projectRef.current.connections || []).find((x) => x.kind === kind && x.id === id); // for the message only
    update((p) => {
      const conns = (p.connections || []).filter((x) => !(x.kind === kind && x.id === id));
      return addVersion({ ...p, connections: conns }, writeConnections(currentHtml(p), conns, { publish: p.publish, auth: p.auth, ai: p.ai }), `Disconnected ${c?.name || "connection"}`, "connect");
    });
    pushLog({
      kind: "result",
      text: `Disconnected ${c?.name || "it"}. Its public share link still exists - revoke it on the ${c?.kind === "workflow" ? "workflow" : "agent"}'s own share settings if you want it fully off.`,
    });
  };

  // Per-connection contract editing (Connect panel → Configure): what to
  // send (request template), what to show (response path), who may call it
  // (requires sign-in). Re-splices the runtime block so the app + publish
  // payload always carry the same config.
  const updateConnection = (kind: "agent" | "workflow", id: string, patch: Partial<StudioConnection>) => {
    update((p) => {
      const conns = (p.connections || []).map((c) => (c.kind === kind && c.id === id ? { ...c, ...patch } : c));
      return addVersion({ ...p, connections: conns }, writeConnections(currentHtml(p), conns, { publish: p.publish, auth: p.auth, ai: p.ai }), `Updated ${patch.alias ? `"${patch.alias}"` : "a connection"}`, "connect");
    });
  };

  // Sign-in + AI config live on the PROJECT, not the HTML - they're re-spliced
  // into the runtime block on every write, and they become the publish payload.
  const setAuthMode = (mode: StudioAuthMode) => {
    if (!isOwner) return;
    update((p) => ({ ...p, auth: mode === "off" ? null : { mode } }));
  };
  const setAiConfig = (cfg: StudioAiConfig | null) => {
    if (!isOwner) return;
    update((p) => ({ ...p, ai: cfg }));
  };
  // Publish: the server-side copy that gives the app a short link, working
  // end-user sign-in, hosted AI and alias calls. The html sent carries the
  // freshly re-spliced runtime block so the published file is self-contained.
  const publishApp = async () => {
    if (!isOwner) throw new Error("Only the app's owner can publish it.");
    // Read from the freshest committed state: this runs across awaits.
    const proj = projectRef.current;
    const html0 = currentHtml(proj);
    // Every connection must carry its alias - it is BOTH the vd.call() name in
    // the runtime block and the server-side call path. Normalize missing ones
    // so the two can never disagree.
    const conns = withAliases(proj.connections || []);
    const authMode = proj.auth?.mode || "off";
    const name = proj.name || titleOf(html0, "My app");
    const body = (htmlOut: string, appId: string | undefined) => ({
      appId,
      name,
      html: htmlOut,
      authMode,
      aiEnabled: !!proj.ai?.enabled,
      aiSystemPrompt: proj.ai?.systemPrompt || undefined,
      connections: conns.map((c) => ({
        alias: c.alias || aliasOf(c.name),
        kind: c.kind.toUpperCase(),
        targetId: c.id,
        shareId: c.shareId,
        displayName: c.name,
        requestTemplate: c.requestTemplate || undefined,
        responsePath: c.responsePath || undefined,
        requiresAuth: !!c.requiresAuth,
      })),
    });
    const publish = {
      appId: String(proj.publish?.appId || ""),
      slug: String(proj.publish?.slug || ""),
      publicUrl: String(proj.publish?.publicUrl || ""),
      publishedAt: Date.now(),
    };
    const res = await api.publishStudioApp(
      body(writeConnections(html0, conns, { publish: proj.publish, auth: proj.auth, ai: proj.ai }), proj.publish?.appId || undefined)
    );
    if (!res?.slug) throw new Error("Publish failed.");
    const next = { ...publish, appId: String(res.appId), slug: String(res.slug), publicUrl: String(res.publicUrl || "") };
    // First publish came back with a slug we couldn't know before - re-upload
    // the HTML with the slug baked into the runtime block so the PUBLISHED
    // copy's vd.call/vd.ai/vd.auth resolve the app (the second publish just
    // bumps the version on the same slug). If THIS step fails the public copy
    // has no slug, so it must not be reported as published.
    if (!proj.publish?.slug) {
      let finalised = false;
      try {
        const r2 = await api.publishStudioApp(
          body(writeConnections(html0, conns, { publish: next, auth: proj.auth, ai: proj.ai }), next.appId)
        );
        finalised = !!r2?.slug;
      } catch {
        finalised = false;
      }
      if (!finalised) {
        // Remember the app id + slug so the retry updates THIS app (and, now
        // that the slug is known, publishes the right HTML in one upload).
        update((p) => ({ ...p, connections: withAliases(p.connections || []), publish: next }));
        throw new Error(
          "The app was uploaded but its link could not be finished, so sign-in, AI and connected agents will not work on the public copy yet. Press Publish again to retry."
        );
      }
    }
    // Stamp the slug into the local copy too - the runtime block must carry
    // it for vd.call/vd.ai/vd.auth to work in preview and shared links.
    update((p) => {
      const cs = withAliases(p.connections || []);
      return addVersion(
        { ...p, connections: cs, publish: next },
        writeConnections(currentHtml(p), cs, { publish: next, auth: p.auth, ai: p.ai }),
        "Published - live at its link",
        "publish"
      );
    });
    pushLog({ kind: "result", text: "Published - your app now has a short link, working sign-in and hosted AI." });
  };

  const unpublishApp = async () => {
    if (!isOwner) throw new Error("Only the app's owner can take it offline.");
    if (!project.publish?.appId) return;
    const ok = await api.disableStudioApp(project.publish.appId);
    if (!ok) throw new Error("Couldn't switch it off.");
    update((p) => ({ ...p, publish: p.publish ? { ...p.publish, offlineAt: Date.now() } : null }));
    pushLog({ kind: "result", text: "Taken offline - the link now 404s. Publish again any time to bring it back." });
  };

  /** The sign-in overlay resolved - drain every queued waiter for that slug. */

  return { attachConnection, detachConnection, updateConnection, setAuthMode, setAiConfig, publishApp, unpublishApp };
}
