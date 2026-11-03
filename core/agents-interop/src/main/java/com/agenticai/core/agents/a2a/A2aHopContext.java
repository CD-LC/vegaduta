package com.agenticai.core.agents.a2a;

import java.util.concurrent.Callable;

/**
 * The {@code X-A2A-Hop} value of the inbound request the current thread is serving, so an outbound
 * A2A call made inside that turn can send hop+1. A ThreadLocal on purpose: the outbound tool
 * callback captures it on the thread that builds the tool set (the request thread, or the async
 * worker {@code A2aTaskService#runAsync} re-binds it on), and re-binds it around the call.
 */
public final class A2aHopContext {

    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

    private A2aHopContext() {}

    /** The inbound hop of this thread's request, or null when not inside an A2A request. */
    public static Integer current() {
        return CURRENT.get();
    }

    /** Hop to put on an outbound request: inbound + 1, or 0 when this call originates here. */
    static int outboundHop() {
        Integer h = CURRENT.get();
        return h == null ? 0 : h + 1;
    }

    static void set(Integer hop) {
        if (hop == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(hop);
        }
    }

    static <T> T callWith(Integer hop, Callable<T> body) throws Exception {
        Integer previous = CURRENT.get();
        set(hop);
        try {
            return body.call();
        } finally {
            set(previous);
        }
    }

    static void runWith(Integer hop, Runnable body) {
        Integer previous = CURRENT.get();
        set(hop);
        try {
            body.run();
        } finally {
            set(previous);
        }
    }
}
