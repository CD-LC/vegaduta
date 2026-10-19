"use client";

import { FastForward, Loader2, Maximize2, Square, Volume2 } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";

/** Play / stop / skip-ahead transport and the "view in full screen" trigger for one reply. */
export function TurnAudioControls({
  index,
  content,
  speakingTurnIndex,
  speakPhase,
  speakSeekable,
  onPlay,
  onStop,
  onSkip,
  onExpand,
}: {
  index: number;
  content: string;
  speakingTurnIndex: number | null;
  speakPhase: "idle" | "loading" | "playing";
  speakSeekable: boolean;
  onPlay: (index: number, text: string) => void;
  onStop: () => void;
  onSkip: () => void;
  /** Opens the full-screen reader; the trigger is handed back so focus can return to it. */
  onExpand: (index: number, trigger: HTMLButtonElement) => void;
}) {
  const t = useTranslations("agentPlayground");
  const i = index;
  return (
    <>
                    {/* Play / stop / skip-ahead transport for this reply. The
                      primary button is a toggle - it turns into Stop while
                      this turn is the one being spoken - and the skip control
                      only appears once there is actually audio running, so an
                      idle row stays a single quiet icon.
                      p-0/border-0/bg-transparent/shadow-none are required:
                      globals.css styles every bare <button> with padding, a
                      border and a card background, which turns a 12px icon
                      into a chunky box. Same neutralization the other icon
                      buttons in this app use. */}
                    {(() => {
                      const isActiveTurn = speakingTurnIndex === i;
                      const isLoading = isActiveTurn && speakPhase === "loading";
                      const isPlaying = isActiveTurn && speakPhase === "playing";
                      const iconButtonClass =
                        "flex size-6 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-muted-foreground";
                      const primaryLabel = isLoading
                        ? t("preparingReply")
                        : isPlaying
                          ? t("stopReply")
                          : t("playReply");
                      return (
                        <div className="ml-auto flex shrink-0 items-center gap-0.5">
                          <button
                            type="button"
                            onClick={() => (isActiveTurn ? onStop() : onPlay(i, content))}
                            className={iconButtonClass}
                            aria-label={primaryLabel}
                            title={primaryLabel}
                          >
                            {isLoading ? (
                              <Loader2 size={12} className="animate-spin" />
                            ) : isPlaying ? (
                              <Square size={12} />
                            ) : (
                              <Volume2 size={12} />
                            )}
                          </button>
                          {isPlaying && (
                            <button
                              type="button"
                              onClick={onSkip}
                              disabled={!speakSeekable}
                              className={iconButtonClass}
                              aria-label={speakSeekable ? t("skipForward") : t("skipForwardUnavailable")}
                              title={speakSeekable ? t("skipForward") : t("skipForwardUnavailable")}
                            >
                              <FastForward size={12} />
                            </button>
                          )}
                          {/* "View in full screen" - only worth showing for a
                            reply long enough that the inline bubble's max-width
                            actually clips/scrolls it; a one-line reply has
                            nothing to gain from the overlay. */}
                          {content.length > 400 && (
                            <button
                              type="button"
                              onClick={(e) => {
                                onExpand(i, e.currentTarget);
                              }}
                              className={iconButtonClass}
                              aria-label={t("expandReply")}
                              title={t("expandReply")}
                            >
                              <Maximize2 size={12} />
                            </button>
                          )}
                        </div>
                      );
                    })()}
    </>
  );
}
