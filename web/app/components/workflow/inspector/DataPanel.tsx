import type { ReactNode } from "react";
import { Braces } from "lucide-react";
import type { Payload } from "./payload";
import { PayloadSection } from "./PayloadSection";

export function DataPanel({ input, output, inputTruncated, outputTruncated, copied, onCopy, pinControl }: {
  input: Payload; output: Payload; inputTruncated: boolean; outputTruncated: boolean; copied: string | null;
  onCopy: (id: string, text: string) => void; pinControl?: ReactNode;
}) {
  if (input.kind === "none" && output.kind === "none") {
    return (
      <div className="ri-soon">
        <Braces size={18} aria-hidden="true" />
        <p>Input/output capture arrives with the next core update</p>
        <span>Until then, the summary and logs show what this step did.</span>
      </div>
    );
  }
  return (
    <div className="ri-data">
      <PayloadSection id="in" title="Input" payload={input} truncated={inputTruncated} copied={copied} onCopy={onCopy} />
      <PayloadSection id="out" title="Output" payload={output} truncated={outputTruncated} copied={copied} onCopy={onCopy} />
      {pinControl}
    </div>
  );
}
