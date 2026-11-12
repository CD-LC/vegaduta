package com.agenticai.core.knowledge;

import java.util.List;

/**
 * The turn's untrusted-content taint as {@link AddToKnowledgeBaseToolCallback} needs it. This
 * module cannot see agents-turn's {@code TurnTaintView}, so the agent tool resolver adapts one to
 * this. Unreadable taint (no supplier, a null view, an exception) is treated as tainted: fail
 * closed.
 */
public interface KnowledgeWriteTaint {

    boolean isTainted();

    List<String> sources();
}
