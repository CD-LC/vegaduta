#!/usr/bin/env python3
"""Summarise JDK 21 pinned-virtual-thread traces from a core pod log.

Core runs requests on virtual threads. On JDK 21 a virtual thread that blocks while it holds a monitor
(`synchronized`) pins its carrier thread; with about as many carriers as cores, a burst of such threads
stops the whole JVM from running anything else (even the probes), see
docs/architecture/HOT-PATH-PERFORMANCE.md section 4d.

To collect traces on a stage, add `-Djdk.tracePinnedThreads=full` to JAVA_TOOL_OPTIONS (kind/step.sh does it
with PIN_TRACE=1), run the load, then:

    kubectl -n vegaduta logs <core-pod> | python3 tests/load/kind/pinned-threads.py

Prints how many traces were seen, which frame held the monitor (the `synchronized` to fix) and the blocking
call that parked the thread. A healthy pod under load shows none or a handful.
"""
import collections
import sys


def main() -> int:
    src = open(sys.argv[1], errors="replace") if len(sys.argv) > 1 else sys.stdin
    traces, cur = [], None
    for line in src:
        line = line.rstrip("\n")
        if line.startswith("VirtualThread[#") and "reason:" in line:
            cur = [line]
            traces.append(cur)
        elif cur is not None and line.startswith("    "):
            cur.append(line.strip())
        else:
            cur = None
    holders: collections.Counter = collections.Counter()
    blockers: collections.Counter = collections.Counter()
    for t in traces:
        held = [x for x in t if "<== monitors" in x]
        holders[held[0].split(" <==")[0] if held else "?"] += 1
        frames = [
            x.replace("java.base/", "")
            for x in t[1:]
            if not x.startswith(("java.base/java.lang.VirtualThread", "java.base/jdk.internal.vm.Continuation",
                                 "java.base/java.lang.System$2", "java.base/jdk.internal.misc.VirtualThreads"))
        ]
        blockers[" | ".join(frames[:4])[:240]] += 1
    print(f"pinned traces: {len(traces)}")
    for frame, n in holders.most_common(10):
        print(f"{n:6d}  monitor held in {frame}")
    print("blocking calls:")
    for stack, n in blockers.most_common(5):
        print(f"{n:6d}  {stack}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
