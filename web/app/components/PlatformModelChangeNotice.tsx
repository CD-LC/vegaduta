"use client";

import { useEffect, useRef } from "react";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import { visibleInterval } from "../lib/visibleInterval";
import { useSignedIn } from "./useSignedIn";
import { isPersonalInstall } from "../lib/personalInstall";

// "If required, show an alert to free users saying, 'We are changing the
// platform model based on your use case.'" - direct platform-owner feedback,
// 2026-07-26.
//
// There is no per-turn "this response was actually served by a different
// model" signal today: /chat is a bare SSE text stream with zero envelope
// (AgentController.chat returns Flux<String> - see AgentChatService's
// attemptHostedChain/HostedChainLink, which already knows which pool row
// served a turn but only ever logs it / feeds HostedProviderHealth, never
// threads it into the response body). Building a true per-message notice
// would mean a real wire-format change across the whole streaming chat path
// (a new SSE event type or trailing metadata frame, api.js's streamChat
// parser, and AgentPlayground's turn state) - not something to rush under
// time pressure just to tick this box.
//
// Instead, this reuses GET /api/providers/platform-llm - the same
// "what's actively serving FREE-tier traffic right now" read ProvidersPanel
// already calls (api.platformLlm()) - polled at a light cadence and diffed
// against the last value seen in localStorage. Any admin-side change to what
// serves FREE-tier chat (a hand-edited model string after a vendor
// deprecation - the exact incident this feature answers - a new provider row
// promoted to top priority, an automatic failover after the previous top row
// started 429ing) surfaces here as a one-time toast, with zero changes to the
// chat streaming wire format. Mounted the same way as SystemHealthProvider -
// inside <AuthProvider>, so it only ever runs once a session actually exists.
const POLL_MS = 60000;
const STORAGE_KEY = "vegaduta.platformLlm.lastSeen";

export function PlatformModelChangeNotice(): null {
  const cancelledRef = useRef(false);
  // A real session, not the "vd_authed" localStorage hint the probe used to
  // trust: the hint only says this browser signed in once. On a PUBLIC route
  // (/templates, /directory, /plugins, /code, /forms/<token>) there is no
  // Keycloak lifecycle and so no token, and on /features and /help the token
  // is not ready yet at mount - a returning user got a 401 on
  // /api/tenant/settings on every one of those pages (prod pass bug 8).
  const signedIn = useSignedIn();

  useEffect(() => {
    // On a personal install the "platform model" is the person's own local model, which they
    // change themselves (wizard, /troubleshoot, CLI): a "we are changing the platform model"
    // toast would be wrong on both counts.
    if (!signedIn || isPersonalInstall()) return undefined;
    cancelledRef.current = false;

    async function probe() {
      try {
        // The header comment's "only ever runs once a session actually
        // exists" was aspirational, not true: AuthProvider passes PUBLIC
        // routes (/try, /login, /signup...) straight through, so this mounted
        // and fired an authenticated GET /api/tenant/settings as a logged-out
        // visitor — a guaranteed 401 in the console every poll (seen live on
        // /try, 2026-07-28). The gate is the `signedIn` check at the top of
        // this effect (useSignedIn): no session, no probe, no interval.
        const settings = await api.getTenantSettings();
        // Paid tenants pick their own model (or BYOK) - this notice is
        // specifically for FREE-tier tenants, who never choose a model
        // themselves and so are the ones actually surprised by a change.
        if (cancelledRef.current || settings?.plan !== "FREE") return;

        const info = await api.platformLlm();
        if (cancelledRef.current || !info?.available) return;

        const signature = `${info.label || ""}::${info.model || ""}`;
        const lastSeen = window.localStorage.getItem(STORAGE_KEY);
        // Only notify on a genuine change from a previously-observed value -
        // the very first observation in a browser (lastSeen === null) is not
        // a "change," just the tenant's normal first look.
        if (lastSeen && lastSeen !== signature) {
          toast.info(
            `We are changing the platform model based on your use case - now using ${info.label || info.model}.`
          );
        }
        window.localStorage.setItem(STORAGE_KEY, signature);
      } catch {
        // Silent, same posture as SystemHealthProvider's probe - a failed
        // check here should never itself raise a user-facing error toast.
      }
    }

    void probe();
    const stop = visibleInterval((): void => void probe(), POLL_MS);
    return () => {
      cancelledRef.current = true;
      stop();
    };
  }, [signedIn]);

  return null;
}

export default PlatformModelChangeNotice;
