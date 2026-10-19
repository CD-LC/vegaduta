import type { ComponentType } from "react";

/**
 * Everything a channel inspector needs from the panel: shared flags (busy, error/success setters), the agent
 * list, translators, and the per-channel state the panel owns. Typed loosely on purpose: each adapter reads
 * only the keys it uses, and adding a channel must not require editing a shared interface.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type ChannelCtx = Record<string, any>;

/** One channel's inspector (the right-hand "connect this channel" pane). `keys` are the channel keys it serves. */
export interface ChannelAdapter {
  keys: string[];
  Inspector: ComponentType<{ ctx: ChannelCtx }>;
}
