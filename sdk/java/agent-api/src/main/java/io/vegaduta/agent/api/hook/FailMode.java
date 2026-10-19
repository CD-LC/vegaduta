package io.vegaduta.agent.api.hook;

/** What happens when a hook throws or times out. */
public enum FailMode {
  /** Continue as if the hook had said {@code CONTINUE}. */
  OPEN,
  /** Treat it as {@code BLOCK}. */
  CLOSED
}
