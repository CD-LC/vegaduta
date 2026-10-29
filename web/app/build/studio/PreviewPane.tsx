"use client";

import { MonitorSmartphone, RefreshCw, RotateCcw, Smartphone, Tablet, Moon, WifiOff } from "lucide-react";
import { Button } from "../../components/ui/button";
import SandboxFrame from "../SandboxFrame";
import Select from "../../components/Select";
import type { ProjectSession } from "./useProjectSession";
import { useTranslations } from "../../lib/i18n";

/** Centre column: the device preview (sandboxed iframe) with its toolbar and preview-only environment toggles. */
export default function PreviewPane({ s }: { s: ProjectSession }) {
    const tHC = useTranslations("hcBuildStudioPreviewPane");
  const {
    busy, device, editable, env, frameRef, inspect, nonce, project, scale, setDevice, setEnv, setNonce, size, srcDoc, stageRef, syncEnv, syncInspect, undo, undoTarget
  } = s;
  return (
    <div className="order-1 flex min-w-0 flex-col gap-2 lg:order-2">
      <div className="flex items-center justify-center gap-1">
        {(
          [
            ["phone", Smartphone, "Phone"],
            ["tablet", Tablet, "Tablet"],
            ["desktop", MonitorSmartphone, "Desktop"],
          ] as const
        ).map(([d, Icon, label]) => (
          <Button key={d} variant={device === d ? "secondary" : "ghost"} size="sm" onClick={() => setDevice(d)} aria-label={label} aria-pressed={device === d} title={label}>
            <Icon size={14} />
          </Button>
        ))}
        <span className="mx-1 h-4 w-px bg-border" />
        <Button variant="ghost" size="sm" onClick={() => setNonce((n) => n + 1)} aria-label={tHC("hcBuildStudioPreviewPane.s7")} title={tHC("hcBuildStudioPreviewPane.s7")}>
          <RefreshCw aria-hidden="true" size={14} />
        </Button>
        <Button variant="ghost" size="sm" onClick={undo} disabled={undoTarget() < 0 || !!busy || !editable} aria-label="Undo" title={tHC("hcBuildStudioPreviewPane.s6")}>
          <RotateCcw aria-hidden="true" size={14} />
        </Button>
      </div>
      <div className="flex flex-wrap items-center justify-center gap-1.5" role="group" aria-label={tHC("hcBuildStudioPreviewPane.s5")}>
        <Button
          variant={env.dark ? "secondary" : "ghost"}
          size="sm"
          aria-pressed={env.dark}
          onClick={() => setEnv((e) => ({ ...e, dark: !e.dark }))}
          title={tHC("hcBuildStudioPreviewPane.s4")}
          className="gap-1 text-xs"
        >
          <Moon aria-hidden="true" size={13} /> Dark
        </Button>
        <Button
          variant={env.offline ? "secondary" : "ghost"}
          size="sm"
          aria-pressed={env.offline}
          onClick={() => setEnv((e) => ({ ...e, offline: !e.offline }))}
          title={tHC("hcBuildStudioPreviewPane.s3")}
          className="gap-1 text-xs"
        >
          <WifiOff aria-hidden="true" size={13} /> Offline
        </Button>
        <Select
          value={env.delayMs}
          onChange={(e) => setEnv((v) => ({ ...v, delayMs: Number(e.target.value) }))}
          aria-label={tHC("hcBuildStudioPreviewPane.s2")}
          className="rounded-md border border-border bg-background px-2 py-1 text-xs text-muted-foreground"
          options={[
            { value: 0, label: "Network: normal" },
            { value: 1500, label: "Network: slow (1.5 s)" },
            { value: 4000, label: "Network: very slow (4 s)" },
          ]}
        />
      </div>
      <div ref={stageRef} className="relative flex min-h-[560px] flex-1 items-start justify-center overflow-hidden rounded-3xl border border-border bg-gradient-to-b from-primary/[0.07] to-transparent p-4 lg:min-h-[720px]">
        <div style={{ width: size.w * scale, height: size.h * scale }} className="relative shrink-0">
          <div
            style={{ width: size.w, height: size.h, transform: `scale(${scale})` }}
            className={`absolute left-0 top-0 origin-top-left overflow-hidden bg-white shadow-2xl ${
              device === "desktop" ? "rounded-xl border border-border" : "rounded-[2.4rem] border-[10px] border-neutral-900"
            }`}
          >
            <SandboxFrame
              ref={frameRef}
              key={nonce}
              html={srcDoc}
              onLoad={() => {
                syncInspect();
                syncEnv();
              }}
              sandbox="allow-scripts allow-forms allow-modals allow-popups"
              allow="camera *; microphone *; geolocation *; bluetooth *"
              title={`${project.name} preview`}
              className="size-full border-0 bg-white"
            />
            {busy && (
              <div className="pointer-events-none absolute inset-x-0 top-0 h-1 overflow-hidden bg-primary/20">
                <div className="h-full w-1/3 animate-[studioSweep_1.2s_ease-in-out_infinite] bg-primary" />
              </div>
            )}
          </div>
        </div>
        {inspect && (
          <div className="pointer-events-none absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full bg-orange-500 px-3 py-1 text-xs font-medium text-white shadow">
            {tHC("hcBuildStudioPreviewPane.s1")}
          </div>
        )}
      </div>
      <style>{`@keyframes studioSweep{0%{transform:translateX(-100%)}100%{transform:translateX(300%)}}`}</style>
    </div>
  );
}
