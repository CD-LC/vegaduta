"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { PlaygroundTurn } from "./types";
import { savePlaygroundState } from "./persistence";

// "The playground is becoming big - it should not show history but save
// history" (2026-07-29). The transcript array is the SAVED history and is
// never trimmed: savePlaygroundState still writes every turn, and the
// backend's own SessionMemoryService still holds the real conversation. What
// is bounded is only what gets RENDERED - a trailing window over `turns`,
// with a "show earlier messages" control that walks backwards through the
// rest. Windowing (rather than virtualising) is right here because each turn
// is a variable-height composite, so there is no row height to virtualise
// against, and a windowed slice needs no new dependency.
export const INITIAL_VISIBLE_TURNS = 12; // ~6 exchanges; `turns` holds user and assistant entries separately
export const LOAD_MORE_STEP = 12;
// How close to the bottom still counts as "following the conversation". Above
// this, the person is reading something further up and auto-scroll backs off.
export const STICK_TO_BOTTOM_PX = 120;

/**
 * The transcript: turns, the session id, the render window over the turns,
 * scroll-follow behaviour and persistence of the saved conversation.
 *
 * @param speakingTurnIndex  the turn being read aloud; it never falls out of the window
 */
export function useTranscript(speakingTurnIndex: number | null) {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [turns, setTurns] = useState<PlaygroundTurn[]>([]);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  // How many trailing turns to render. Purely a view concern - `turns` itself
  // is never trimmed, so nothing that has not been persisted can be lost by
  // changing this.
  const [visibleCount, setVisibleCount] = useState(INITIAL_VISIBLE_TURNS);
  // Auto-scroll follows the conversation only while the person is already at
  // the bottom. A ref, not state, because this updates on every scroll event
  // and must not re-render a streaming transcript.
  const stickToBottomRef = useRef(true);
  // Set just before a visibleCount change so the layout effect below can put
  // the viewport back where it was (expanding prepends content ABOVE the
  // viewport, which would otherwise yank the reader down the page).
  // "bottom" pins to the newest turn (collapse); "anchor" restores the exact
  // pre-expansion offset (show earlier).
  const restoreScrollRef = useRef<
    { mode: "bottom" } | { mode: "anchor"; scrollHeight: number; scrollTop: number } | null
  >(null);

  // The window slides forward as turns arrive, which drops the oldest rendered
  // turn off the top. That is invisible when you're at the bottom, but if
  // you're parked further up re-reading something, deleting content ABOVE your
  // viewport shifts everything you're looking at. So: while the reader isn't
  // following the bottom, widen the window by exactly as many turns as arrived.
  const prevTurnCountRef = useRef(0);
  useEffect(() => {
    const grew = turns.length - prevTurnCountRef.current;
    prevTurnCountRef.current = turns.length;
    if (grew > 0 && !stickToBottomRef.current) {
      setVisibleCount((count) => count + grew);
    }
  }, [turns]);

  // Never let the turn currently being read aloud fall out of the rendered
  // window: its play/stop/skip transport lives in that row. Widening the
  // window (rather than stopping playback) means a reply arriving mid-listen
  // doesn't cut the person off.
  useEffect(() => {
    if (speakingTurnIndex == null) return;
    const needed = turns.length - speakingTurnIndex;
    setVisibleCount((count) => (count < needed ? needed : count));
  }, [speakingTurnIndex, turns.length]);

  // Auto-scroll to the newest turn, but only while the person is actually at
  // the bottom - scrolling up to re-read an earlier answer used to be fought
  // by every streamed chunk yanking the view back down.
  useEffect(() => {
    if (restoreScrollRef.current) return; // a window expansion is mid-flight; that owns the scroll position
    if (!stickToBottomRef.current) return;
    bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight, behavior: "smooth" });
  }, [turns]);

  // Deliberately keyed on visibleCount ONLY, so "show earlier"/"collapse"
  // never runs the scroll-to-latest effect above: growing the window adds
  // content above the viewport, and the correct behaviour is to leave the
  // reader looking at the same message.
  useLayoutEffect(() => {
    const el = bodyRef.current;
    const saved = restoreScrollRef.current;
    restoreScrollRef.current = null;
    if (!el || !saved) return;
    if (saved.mode === "bottom") {
      el.scrollTop = el.scrollHeight;
      return;
    }
    el.scrollTop = saved.scrollTop + (el.scrollHeight - saved.scrollHeight);
  }, [visibleCount]);

  function handleBodyScroll() {
    const el = bodyRef.current;
    if (!el) return;
    stickToBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_TO_BOTTOM_PX;
  }

  function showEarlier() {
    const el = bodyRef.current;
    restoreScrollRef.current = el ? { mode: "anchor", scrollHeight: el.scrollHeight, scrollTop: el.scrollTop } : null;
    setVisibleCount((count) => count + LOAD_MORE_STEP);
  }

  function collapseEarlier() {
    // Collapsing must not hide a reply that is currently being read aloud -
    // keep enough of a window to leave its transport controls on screen.
    const keepForAudio = speakingTurnIndex == null ? 0 : turns.length - speakingTurnIndex;
    const next = Math.max(INITIAL_VISIBLE_TURNS, keepForAudio);
    if (next === visibleCount) return; // nothing to collapse; don't strand restoreScrollRef
    restoreScrollRef.current = { mode: "bottom" };
    stickToBottomRef.current = true;
    setVisibleCount(next);
  }

  /** Back to the collapsed tail, scrolled to the latest turn (agent switch, new session). */
  function resetWindow() {
    setVisibleCount(INITIAL_VISIBLE_TURNS);
    stickToBottomRef.current = true;
    restoreScrollRef.current = null;
  }

  // The rendered slice. `windowStart` is also the absolute index of the first
  // rendered turn, which every per-turn identity in the list is keyed on
  // (speakingTurnIndex compares against absolute indices).
  const windowStart = Math.max(0, turns.length - visibleCount);
  const visibleTurns = turns.slice(windowStart);
  const hiddenCount = windowStart;
  const canCollapse = hiddenCount === 0 && visibleCount > INITIAL_VISIBLE_TURNS && turns.length > INITIAL_VISIBLE_TURNS;

  return {
    sessionId,
    setSessionId,
    turns,
    setTurns,
    bodyRef,
    stickToBottomRef,
    windowStart,
    visibleTurns,
    hiddenCount,
    canCollapse,
    handleBodyScroll,
    showEarlier,
    collapseEarlier,
    resetWindow,
  };
}

/**
 * Saves the transcript to localStorage once a turn settles. A separate hook
 * (not part of useTranscript) because effect order matters: the shell's
 * agent-switch effect must restore the saved transcript BEFORE this runs, or a
 * fresh mount would overwrite the saved conversation with an empty one.
 */
export function usePersistTranscript(
  agentId: string | undefined,
  sessionId: string | null,
  turns: PlaygroundTurn[],
  sending: boolean
) {
  // Gated on `!sending` deliberately - `turns` also updates on every streamed
  // chunk, and re-serializing the whole turns array to localStorage per token
  // would turn a long reply into O(n^2) main-thread work. Persisting once the
  // turn settles still covers the goal (survive a refresh between messages).
  useEffect(() => {
    if (sending) return;
    savePlaygroundState(agentId, sessionId, turns);
  }, [agentId, sessionId, turns, sending]);
}
