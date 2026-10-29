"use client";

// An <iframe> that renders an HTML string the way `srcDoc` would, but inside
// public/sandbox-frame.html instead of an about:srcdoc document.
//
// Why not srcDoc: an about:srcdoc document inherits the embedding page's
// Content-Security-Policy. The console enforces a nonce-based script policy
// (lib/csp.ts), so a generated app's inline scripts would all be blocked in a
// srcdoc preview. sandbox-frame.html is served with its own, separate policy
// (proxy.js) and is always sandboxed WITHOUT allow-same-origin, so the
// generated code runs in an opaque origin and cannot reach this page's
// cookies, storage or tokens.
//
// The HTML is delivered by postMessage once the shell says it is ready; see
// the protocol note in public/sandbox-frame.html. `onLoad` fires when the
// written document is in place (the element's own load event fires for the
// empty shell, which is too early).

import { useEffect, useMemo, useRef, type IframeHTMLAttributes, type Ref } from "react";
import { useTranslations } from "../lib/i18n";

export const SANDBOX_FRAME_SRC = "/sandbox-frame.html";

type Props = Omit<IframeHTMLAttributes<HTMLIFrameElement>, "src" | "srcDoc" | "onLoad" | "sandbox"> & {
  /** The document to render. A change reloads the frame (a fresh JS realm, as a new srcdoc would). */
  html: string;
  /** Must not contain allow-same-origin: the opaque origin is the isolation. */
  sandbox?: string;
  onLoad?: () => void;
  ref?: Ref<HTMLIFrameElement>;
};

/** Cheap, stable string hash (djb2) so the frame key changes with the document. */
function hashOf(s: string): string {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return `${s.length}:${h >>> 0}`;
}

export function sandboxWithoutSameOrigin(sandbox: string): string {
  return sandbox
    .split(/\s+/)
    .filter((token) => token && token.toLowerCase() !== "allow-same-origin")
    .join(" ");
}

export default function SandboxFrame({ html, sandbox = "allow-scripts", onLoad, ref, ...rest }: Props) {
    const tHC = useTranslations("hcBuildSandboxFrame");
  const own = useRef<HTMLIFrameElement | null>(null);
  const htmlRef = useRef(html);
  const onLoadRef = useRef(onLoad);
  useEffect(() => {
    htmlRef.current = html;
    onLoadRef.current = onLoad;
  }, [html, onLoad]);

  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      const win = own.current?.contentWindow;
      if (!win || e.source !== win) return;
      const d = e.data as { vdSandbox?: unknown } | null;
      if (!d || typeof d !== "object") return;
      if (d.vdSandbox === "ready") {
        // The frame's origin is opaque, so "*" is the only target that can
        // match; the e.source check above is what pins the recipient.
        win.postMessage({ vdSandbox: "render", html: htmlRef.current }, "*");
      } else if (d.vdSandbox === "loaded") {
        onLoadRef.current?.();
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, []);

  const frameKey = useMemo(() => hashOf(html), [html]);

  return (
    <iframe
      title={tHC("hcBuildSandboxFrame.s1")}
      {...rest}
      key={frameKey}
      ref={(el) => {
        own.current = el;
        if (typeof ref === "function") ref(el);
        else if (ref) ref.current = el;
      }}
      src={SANDBOX_FRAME_SRC}
      sandbox={sandboxWithoutSameOrigin(sandbox)}
    />
  );
}
