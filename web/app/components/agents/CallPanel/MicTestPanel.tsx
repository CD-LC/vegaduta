"use client";

import { Loader2, AlertCircle, CheckCircle2, X } from "lucide-react";
import { Button } from "../../ui/button";
import type { MicTestResult } from "./useMicTest";
import { useTranslations } from "../../../lib/i18n";

interface MicTestPanelProps {
  showMicTest: boolean;
  micTestResult: MicTestResult | null;
  testingMicrophone: boolean;
  audioLevel: number;
  testMicrophone: () => void;
  setShowMicTest: (show: boolean) => void;
  setMicTestResult: (result: MicTestResult | null) => void;
}

export default function MicTestPanel({
  showMicTest,
  micTestResult,
  testingMicrophone,
  audioLevel,
  testMicrophone,
  setShowMicTest,
  setMicTestResult,
}: MicTestPanelProps) {
  const tHC = useTranslations("hcComponentsAgentsCallPanelMicTestPanel");
  return (
    <>
      {/* Microphone Test Panel */}
      {showMicTest && (
        <div className="space-y-2 rounded-lg border border-border bg-muted/30 p-3">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium">{tHC("s14")}</span>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => setShowMicTest(false)}
              className="h-auto p-1"
              aria-label={tHC("s13")}
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </Button>
          </div>

          {!micTestResult ? (
            <Button type="button" size="sm" onClick={testMicrophone} disabled={testingMicrophone} className="w-full">
              {testingMicrophone ? (
                <>
                  <Loader2 size={12} className="animate-spin me-2" aria-hidden="true" />
                  {tHC("s12")}
                </>
              ) : (
                "Start Microphone Test"
              )}
            </Button>
          ) : (
            <div className="space-y-2">
              <div
                className="flex items-center gap-2 rounded p-2 text-xs"
                style={
                  {
                    ["--state" as any]: micTestResult.success ? "var(--state-success)" : "var(--state-warning)",
                    color: "var(--state)",
                    background: "color-mix(in srgb, var(--state) 12%, transparent)",
                  } as any
                }
              >
                {micTestResult.success ? (
                  <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden="true" />
                ) : (
                  <AlertCircle className="h-4 w-4 shrink-0" aria-hidden="true" />
                )}
                <span>{micTestResult.message}</span>
              </div>

              {testingMicrophone === false && audioLevel > 0 && (
                <div className="space-y-1">
                  <div className="text-xs text-muted-foreground">{tHC("s11")}</div>
                  {/* .state-meter inherits its hue from --state, so the mic
                      level uses the same good/warn/idle scale as everything
                      else on the screen. Its track height is the utility's own
                      (0.25rem) - this was briefly overridden inline to keep the
                      old h-2 thickness, which is exactly the kind of box metric
                      that belongs in the utility, not here. */}
                  <div
                    className="state-meter"
                    style={
                      {
                        ["--state" as any]:
                          audioLevel > 50
                            ? "var(--state-success)"
                            : audioLevel > 20
                              ? "var(--state-warning)"
                              : "var(--state-idle)",
                      } as any
                    }
                  >
                    <span style={{ width: `${audioLevel}%` }} />
                  </div>
                </div>
              )}

              {micTestResult.error && (
                <details className="text-xs cursor-pointer">
                  <summary className="font-medium text-destructive hover:underline">
                    Troubleshooting: {micTestResult.error}
                  </summary>
                  <div className="mt-2 space-y-1 ps-2 border-s-2 border-destructive text-muted-foreground">
                    {micTestResult.solution && (
                      <>
                        <p>{micTestResult.solution}</p>
                        <p className="text-xs mt-2">
                          <strong>{tHC("s10")}</strong>
                        </p>
                        <ul className="list-disc ps-4 space-y-0.5">
                          <li>{tHC("s9")}</li>
                          <li>{tHC("s8")}</li>
                          <li>{tHC("s7")}</li>
                          <li>{tHC("s6")}</li>
                          <li>
                            See{" "}
                            <a
                              href="https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia"
                              target="_blank"
                              rel="noopener noreferrer"
                              className="underline text-primary hover:text-primary/80"
                            >
                              {tHC("s5")}
                            </a>
                          </li>
                        </ul>
                      </>
                    )}
                  </div>
                </details>
              )}

              <div className="text-xs text-muted-foreground space-y-1">
                <div>
                  {tHC("s4")} {micTestResult.mimeType}
                </div>
                <div>
                  {tHC("s3")} {(micTestResult.audioSize / 1024).toFixed(1)} KB
                </div>
                <div>
                  {tHC("s2")} {micTestResult.maxLevel}%
                </div>
              </div>

              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => setMicTestResult(null)}
                className="w-full"
              >
                {tHC("s1")}
              </Button>
            </div>
          )}
        </div>
      )}
    </>
  );
}
