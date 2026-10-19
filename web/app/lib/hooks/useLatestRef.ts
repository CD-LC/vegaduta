"use client";

import { useEffect, useRef } from "react";

/**
 * A ref that always holds the latest committed `value`.
 *
 * For effects that must read a value WITHOUT re-running when it changes -
 * e.g. a translator used only in an async error branch of a load effect whose
 * re-run would wipe unsaved edits. Read `ref.current` inside the async
 * callback, never during render. The returned ref object is stable, so listing
 * it in a dependency array never re-runs anything.
 */
export function useLatestRef<T>(value: T) {
  const ref = useRef(value);
  useEffect(() => {
    ref.current = value;
  }, [value]);
  return ref;
}
