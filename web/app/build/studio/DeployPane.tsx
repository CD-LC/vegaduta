"use client";

import CollabPanel from "./CollabPanel";
import ConnectPanel from "./StudioConnectPanel";
import SharePanel from "./StudioSharePanel";
import type { ProjectSession } from "./useProjectSession";

/** Panels that connect and ship the app: share/publish, connected services and the team. */
export default function DeployPane({ s }: { s: ProjectSession }) {
  const { attachConnection, busy, detachConnection, editable, html, isOwner, onOpenApp, panel, project, publishApp, pushLog, setAiConfig, setAuthMode, unpublishApp, updateConnection } = s;
  return (
    <>
            {panel === "share" && (
              <SharePanel
                html={html}
                name={project.name}
                publish={project.publish || null}
                canPublish={isOwner}
                onPublish={publishApp}
                onUnpublish={unpublishApp}
                onError={(m) => pushLog({ kind: "error", text: m })}
                onOpenApp={onOpenApp}
              />
            )}
            {panel === "team" && <CollabPanel projectId={project.id} role={project.role} versions={project.versions} />}
      {panel === "connect" && (
        <fieldset disabled={!editable} className="contents">
              {panel === "connect" && (
                <ConnectPanel
                  connections={project.connections || []}
                  busy={!!busy}
                  published={!!project.publish?.slug}
                  authMode={project.auth?.mode || "off"}
                  ai={project.ai || null}
                  onAttach={attachConnection}
                  onDetach={detachConnection}
                  onConfigure={updateConnection}
                  onSetAuthMode={setAuthMode}
                  onSetAi={setAiConfig}
                  onError={(m) => pushLog({ kind: "error", text: m })}
                />
              )}
        </fieldset>
      )}
    </>
  );
}
