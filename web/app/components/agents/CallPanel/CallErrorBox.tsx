import { useTranslations } from "../../../lib/i18n";
"use client";



interface CallErrorBoxProps {
  callError: string | null;
  pipelineError: string | null;
  setShowMicTest: (show: boolean) => void;
}

export default function CallErrorBox({ callError, pipelineError, setShowMicTest }: CallErrorBoxProps) {
    const tHC = useTranslations("hcComponentsAgentsCallPanelCallErrorBox");
  return (
    <>
      {(callError || pipelineError) && (
        <div className="rounded-lg border border-destructive/20 bg-destructive/5 p-2.5 text-xs text-destructive space-y-1">
          <p>{callError || pipelineError}</p>
          {pipelineError?.toLowerCase().includes("microphone") && (
            <button
              onClick={() => setShowMicTest(true)}
              className="text-primary hover:underline font-medium text-xs"
            >
              {tHC("hcComponentsAgentsCallPanelCallErrorBox.s1")}
            </button>
          )}
        </div>
      )}
    </>
  );
}
