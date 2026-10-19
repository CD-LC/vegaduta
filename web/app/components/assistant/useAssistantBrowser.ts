"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { isTakeoverOpen } from "../../lib/browserTakeover";
import { isPersonalInstall } from "../../lib/personalInstall";
import type { BrowserProfile } from "../../lib/api/browserAutomation";
import type { BrowserTakeoverView } from "../../lib/api/browserTakeover";
import { useBrowserCapabilities } from "../agents/useBrowserCapabilities";
import { useBrowserTakeoverCapabilities } from "../agents/useBrowserTakeoverCapabilities";
import { browserAvailability, profileFor, type BrowserAvailability } from "./browserAvailability";

const POLL_MS = 3_000;

export type AssistantBrowser = {
  availability: BrowserAvailability;
  /** The takeover waiting for the person (the agent asked, or they paused) on this conversation, if any. */
  pending: BrowserTakeoverView | null;
  /** The saved login this agent uses; null when none or when saved logins are off. */
  profile: BrowserProfile | null;
  /** Saved logins are switched on in core. */
  profilesOn: boolean;
};

/**
 * Feeds the Assistant's Browser button: whether to offer it, whether the agent is asking for help, and which saved
 * login is in use. The takeover status is polled only while the pane is CLOSED and only where takeover is on - an
 * open pane's BrowserTakeoverPanel does its own polling, so there is never a second poll beside it.
 */
export function useAssistantBrowser(agentId: string | undefined, sessionId: string | null | undefined, paneOpen: boolean): AssistantBrowser {
  const takeoverCaps = useBrowserTakeoverCapabilities();
  const browserCaps = useBrowserCapabilities();
  const availability = browserAvailability(takeoverCaps, browserCaps, isPersonalInstall());
  const [pending, setPending] = useState<BrowserTakeoverView | null>(null);
  const [profile, setProfile] = useState<BrowserProfile | null>(null);
  const profilesOn = availability === "ready" && browserCaps?.profilesEnabled === true;

  useEffect(() => {
    setPending(null);
    if (availability !== "ready" || paneOpen || !agentId || !sessionId) return undefined;
    let stopped = false;
    const read = async () => {
      if (typeof document !== "undefined" && document.hidden) return;
      try {
        const res = await api.getBrowserTakeoverStatus(agentId, sessionId);
        if (!stopped) setPending(isTakeoverOpen(res.takeover) ? res.takeover : null);
      } catch {
        /* a failed read changes nothing; the next tick asks again */
      }
    };
    void read();
    const timer = setInterval(() => void read(), POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [availability, paneOpen, agentId, sessionId]);

  // Re-read when the pane opens: a login saved during the last visit shows up.
  useEffect(() => {
    setProfile(null);
    if (!profilesOn || !agentId || !paneOpen) return undefined;
    let stopped = false;
    api
      .listBrowserProfiles()
      .then((list) => {
        if (!stopped) setProfile(Array.isArray(list) ? profileFor(list, agentId) : null);
      })
      .catch((): void => undefined);
    return () => {
      stopped = true;
    };
  }, [profilesOn, agentId, paneOpen]);

  return { availability, pending, profile, profilesOn };
}
