"use client";

import { useCallback, useState } from "react";
import { Clapperboard, Loader2, Video } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { createSession, generateVideo, readStoredToken, type DemoVideoResult } from "../demoClient";

type VideoPhase = "idle" | "generating" | "done";

/**
 * Magic Studio's short video generation strip: the button, the plan note, any notice and the
 * generated clip. `unavailableText` is the shared "studio unavailable" copy.
 */
export default function DemoVideoGenerator({ prompt, unavailableText }: { prompt: string; unavailableText: string }) {
  const t = useTranslations("landing");
  // Short, real video generation (DemoVideoController) — a SEPARATE, direct
  // call from the agent-workflow plan/run pipeline above, not routed through
  // it: video is its own tightly-capped feature (~15s, ~1 per sandbox, see
  // that controller's javadoc), not a workflow step. Reuses whatever prompt
  // is currently in the box.
  const [videoPhase, setVideoPhase] = useState<VideoPhase>("idle");
  const [videoResult, setVideoResult] = useState<DemoVideoResult | null>(null);
  const [videoNotice, setVideoNotice] = useState<string | null>(null);

  const handleGenerateVideo = useCallback(async () => {
    const cleaned = prompt.trim();
    if (!cleaned || videoPhase === "generating") return;
    setVideoNotice(null);
    setVideoResult(null);
    setVideoPhase("generating");
    const existing = readStoredToken();
    let token = existing;
    let failureMessage: string | null = null;
    if (!token) {
      const created = await createSession();
      token = created.ok && created.session?.demoToken ? created.session.demoToken : null;
      failureMessage = created.message || null;
    }
    if (!token) {
      setVideoNotice(failureMessage || unavailableText);
      setVideoPhase("idle");
      return;
    }
    const video = await generateVideo(token, cleaned);
    setVideoResult(video);
    if (video.simulated && video.message) setVideoNotice(video.message);
    setVideoPhase("done");
  }, [prompt, videoPhase, unavailableText]);

  return (
    <>
      {/* Real, short video generation — its own capped feature, separate
          from "Build it for me" above (see handleGenerateVideo's comment). */}
      <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-border/40 pt-3">
        <Button
          variant="outline"
          size="sm"
          onClick={handleGenerateVideo}
          disabled={!prompt.trim() || videoPhase === "generating"}
          className="gap-2"
        >
          {videoPhase === "generating" ? (
            <>
              <Loader2 className="h-3.5 w-3.5 animate-spin shrink-0" aria-hidden />
              {t("magic_generatingVideo")}
            </>
          ) : (
            <>
              <Clapperboard className="h-3.5 w-3.5 shrink-0" aria-hidden />
              {t("magic_generateVideoButton")}
            </>
          )}
        </Button>
        <span className="text-xs text-muted-foreground">{t("magic_videoPlanNote")}</span>
      </div>

      {videoNotice ? (
        <p
          role="status"
          className="mt-3 rounded-xl border border-border/50 bg-background/50 px-3 py-2 text-center text-xs text-muted-foreground"
        >
          {videoNotice}
        </p>
      ) : null}

      {videoResult?.videoUrl ? (
        <div className="mt-3 overflow-hidden rounded-xl border border-border/50">
          <video src={videoResult.videoUrl} controls playsInline className="w-full bg-black" />
          <p className="flex items-center gap-1.5 bg-background/60 px-3 py-2 text-xs text-muted-foreground">
            <Video className="h-3 w-3 shrink-0" aria-hidden />
            {t("magic_videoCaption", {
              duration: videoResult.durationSeconds,
              used: videoResult.videosUsed,
              limit: videoResult.videoLimit,
            })}
          </p>
        </div>
      ) : null}
    </>
  );
}
