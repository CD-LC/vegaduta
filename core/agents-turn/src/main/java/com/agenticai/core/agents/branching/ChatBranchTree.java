package com.agenticai.core.agents.branching;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure helpers over a conversation tree - no Spring, no I/O - so the shape rules
 * (what the active path is, who a message's siblings are, which leaf a switch lands
 * on, how the live Redis transcript maps back onto the tree) are unit-testable on
 * their own. See {@link ChatBranchService} for the stateful side.
 *
 * <p>A tree is a set of {@link Node}s. A node's {@code parentId} is null for a root.
 * The children of one parent are its siblings: they are alternative versions of the
 * same message (an edited user message, or a regenerated assistant reply), ordered
 * by {@code siblingIndex}.
 */
public final class ChatBranchTree {

    /** Defensive bound on path length: a corrupt parent cycle must not loop forever. */
    static final int MAX_DEPTH = 10_000;

    private ChatBranchTree() {
    }

    /** One message of the tree. */
    public record Node(UUID id, UUID parentId, MessageRole role, String content, int siblingIndex, Instant createdAt) {
    }

    /** The versions of one message: {@code ids} in order, {@code index} (0-based) of the one asked about. */
    public record SiblingInfo(List<UUID> ids, int index, int count) {
    }

    /** How the live transcript relates to the stored active path - see {@link #align}. */
    public enum Kind {
        /** The live transcript is empty (expired or never written): nothing to learn from it. */
        NONE,
        /** The live transcript is exactly the active path. */
        SAME,
        /** The live transcript is a strict prefix of the active path: the session was rewound. */
        REWIND,
        /** The live transcript has turns the tree does not know yet (new turns, or a new branch). */
        TAIL
    }

    /**
     * @param kind        the relationship
     * @param anchorIndex index into the active path of the node the unknown turns hang under
     *                    (TAIL; -1 = they are new roots) or of the node that is now the leaf (REWIND)
     * @param tailFrom    index into the live transcript of the first turn the tree does not know (TAIL)
     */
    public record Alignment(Kind kind, int anchorIndex, int tailFrom) {
    }

    /**
     * The path from the root down to {@code leafId}, oldest first. Empty when the leaf is
     * null or unknown. Stops (returning what it has) on a missing parent or a cycle.
     */
    public static List<Node> activePath(Map<UUID, Node> byId, UUID leafId) {
        if (leafId == null || byId == null) {
            return List.of();
        }
        List<Node> reversed = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID cursor = leafId;
        while (cursor != null && reversed.size() < MAX_DEPTH) {
            Node node = byId.get(cursor);
            if (node == null || !seen.add(cursor)) {
                break;
            }
            reversed.add(node);
            cursor = node.parentId();
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /** Children of {@code parentId} (null = the roots), in sibling order. */
    public static List<Node> childrenOf(Collection<Node> all, UUID parentId) {
        List<Node> out = new ArrayList<>();
        for (Node n : all) {
            if (Objects.equals(n.parentId(), parentId)) {
                out.add(n);
            }
        }
        out.sort(SIBLING_ORDER);
        return out;
    }

    /** The versions of {@code id}: itself plus every other child of its parent, ordered. Null when {@code id} is unknown. */
    public static SiblingInfo siblingInfo(Collection<Node> all, UUID id) {
        Node self = null;
        for (Node n : all) {
            if (n.id().equals(id)) {
                self = n;
                break;
            }
        }
        if (self == null) {
            return null;
        }
        List<Node> siblings = childrenOf(all, self.parentId());
        List<UUID> ids = new ArrayList<>(siblings.size());
        int index = 0;
        for (int i = 0; i < siblings.size(); i++) {
            ids.add(siblings.get(i).id());
            if (siblings.get(i).id().equals(id)) {
                index = i;
            }
        }
        return new SiblingInfo(List.copyOf(ids), index, ids.size());
    }

    /**
     * The id of the sibling {@code delta} steps away from {@code id} (-1 = previous, +1 = next),
     * or null at either end - navigation is clamped, it does not wrap.
     */
    public static UUID navigate(Collection<Node> all, UUID id, int delta) {
        SiblingInfo info = siblingInfo(all, id);
        if (info == null) {
            return null;
        }
        int target = info.index() + delta;
        if (target < 0 || target >= info.count()) {
            return null;
        }
        return info.ids().get(target);
    }

    /**
     * The leaf a switch to {@code startId} lands on: from that message, keep descending into
     * the most recently created child until there is none. "Most recent" is what chat apps
     * show when you flip to a version you previously continued.
     */
    public static UUID leafUnder(Collection<Node> all, UUID startId) {
        if (startId == null) {
            return null;
        }
        UUID cursor = startId;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Node newest = null;
            for (Node n : all) {
                if (cursor.equals(n.parentId()) && (newest == null || RECENCY.compare(n, newest) > 0)) {
                    newest = n;
                }
            }
            if (newest == null) {
                return cursor;
            }
            cursor = newest.id();
        }
        return cursor;
    }

    /**
     * Maps the live transcript (what Redis holds, oldest first) onto the stored active path.
     *
     * <p>The live transcript is normally the active path, possibly followed by turns chat has
     * appended since ({@link Kind#TAIL}, hanging under the current leaf). It can also be a
     * shorter prefix (the session was rewound: {@link Kind#REWIND}) or share only a prefix and
     * then differ (rewound and then continued: {@link Kind#TAIL} hanging under the last shared
     * node, i.e. a new branch). Redis keeps only the newest entries, so the transcript can also
     * START part-way down the path; every start offset is tried and the one that explains the
     * most turns wins, the lowest offset on a tie.
     */
    public static Alignment align(List<Node> path, List<ChatTurn> live) {
        int m = live == null ? 0 : live.size();
        int n = path == null ? 0 : path.size();
        if (m == 0) {
            return new Alignment(Kind.NONE, -1, 0);
        }
        int bestK = 0;
        int bestC = -1;
        for (int k = 0; k <= Math.max(0, n - 1); k++) {
            int c = commonPrefix(path, k, live);
            if (c > bestC) {
                bestC = c;
                bestK = k;
            }
            if (bestC == m) {
                break;
            }
        }
        if (bestC == m) {
            int end = bestK + m;
            if (end == n) {
                return new Alignment(Kind.SAME, n - 1, m);
            }
            return new Alignment(Kind.REWIND, end - 1, m);
        }
        return new Alignment(Kind.TAIL, bestK + bestC - 1, bestC);
    }

    private static int commonPrefix(List<Node> path, int offset, List<ChatTurn> live) {
        int i = 0;
        while (i < live.size() && offset + i < path.size() && matches(path.get(offset + i), live.get(i))) {
            i++;
        }
        return i;
    }

    /** Same role and same text. */
    public static boolean matches(Node node, ChatTurn turn) {
        return node.role() == turn.role() && Objects.equals(nullToEmpty(node.content()), nullToEmpty(turn.content()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static final Comparator<Node> SIBLING_ORDER = Comparator
        .comparingInt(Node::siblingIndex)
        .thenComparing(Node::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(Node::id);

    private static final Comparator<Node> RECENCY = Comparator
        .comparing(Node::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparingInt(Node::siblingIndex)
        .thenComparing(Node::id);
}
