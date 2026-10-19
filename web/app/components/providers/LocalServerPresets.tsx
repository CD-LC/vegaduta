"use client";

import { useState } from "react";
import { Button } from "../ui/button";
import { Checkbox } from "../ui/checkbox";
import { Label } from "../ui/label";
import { useTranslations } from "../../lib/i18n";
import { LOCAL_SERVER_PRESETS, allowListEntryFor, localServerUrl } from "./localServer";

/**
 * Above the base-URL field of the "Local / OpenAI-compatible server" form: one-click presets for the servers the
 * provider is for, a Docker switch (a platform in a container reaches the host at host.docker.internal, not
 * localhost), and - once a URL is typed - the exact allow-list entry the platform operator has to add, because the
 * platform refuses private and loopback addresses unless they are listed.
 */
export default function LocalServerPresets({
  baseUrl,
  onPick,
}: {
  baseUrl: string;
  onPick: (url: string) => void;
}) {
  const t = useTranslations("providersPanel");
  const [inDocker, setInDocker] = useState(false);
  const entry = baseUrl.trim() ? allowListEntryFor(baseUrl) : null;
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2" role="group" aria-label={t("localOpenaiPresetsLabel")}>
        <span className="text-xs text-muted-foreground">{t("localOpenaiPresetsLabel")}</span>
        {LOCAL_SERVER_PRESETS.map((preset) => (
          <Button
            key={preset.id}
            type="button"
            size="sm"
            variant="outline"
            onClick={() => onPick(localServerUrl(preset, inDocker))}
          >
            {preset.label}
          </Button>
        ))}
      </div>
      <div className="flex items-center gap-2">
        <Checkbox id="local-openai-in-docker" checked={inDocker} onCheckedChange={setInDocker} />
        <Label htmlFor="local-openai-in-docker" className="text-xs font-normal text-muted-foreground">
          {t("localOpenaiInDockerLabel")}
        </Label>
      </div>
      {entry && <p className="text-xs text-muted-foreground">{t("localOpenaiAllowListNote", { entry })}</p>}
    </div>
  );
}
