package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.branching.ChatBranchTree.Alignment;
import com.agenticai.core.agents.branching.ChatBranchTree.Kind;
import com.agenticai.core.agents.branching.ChatBranchTree.Node;
import com.agenticai.core.agents.branching.ChatBranchTree.SiblingInfo;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The pure tree rules: active path, siblings, navigation, leaf choice and transcript alignment. */
class ChatBranchTreeTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private int clock = 0;

    private Node node(UUID parent, MessageRole role, String content, int sibling) {
        return new Node(UUID.randomUUID(), parent, role, content, sibling, T0.plusSeconds(clock++));
    }

    private static Map<UUID, Node> byId(List<Node> nodes) {
        Map<UUID, Node> map = new HashMap<>();
        for (Node n : nodes) {
            map.put(n.id(), n);
        }
        return map;
    }

    private static ChatTurn turn(MessageRole role, String content) {
        return new ChatTurn(role, content, T0);
    }

    private static List<ChatTurn> turnsOf(List<Node> path) {
        List<ChatTurn> out = new ArrayList<>();
        for (Node n : path) {
            out.add(turn(n.role(), n.content()));
        }
        return out;
    }

    /**
     * u1 -> a1 -> u2 -> a2   (original)
     *             \-> u2b -> a2b  (edited sibling of u2)
     *       a1 has a second reply a1b (regenerated sibling of a1)
     */
    private record Fixture(Node u1, Node a1, Node a1b, Node u2, Node a2, Node u2b, Node a2b, List<Node> all) {
    }

    private Fixture fixture() {
        Node u1 = node(null, MessageRole.USER, "q1", 0);
        Node a1 = node(u1.id(), MessageRole.ASSISTANT, "r1", 0);
        Node u2 = node(a1.id(), MessageRole.USER, "q2", 0);
        Node a2 = node(u2.id(), MessageRole.ASSISTANT, "r2", 0);
        Node u2b = node(a1.id(), MessageRole.USER, "q2 edited", 1);
        Node a2b = node(u2b.id(), MessageRole.ASSISTANT, "r2b", 0);
        Node a1b = node(u1.id(), MessageRole.ASSISTANT, "r1 again", 1);
        return new Fixture(u1, a1, a1b, u2, a2, u2b, a2b, List.of(u1, a1, u2, a2, u2b, a2b, a1b));
    }

    @Test
    void activePathWalksFromTheRootDownToTheLeaf() {
        Fixture f = fixture();
        List<Node> path = ChatBranchTree.activePath(byId(f.all()), f.a2b().id());
        assertEquals(List.of(f.u1(), f.a1(), f.u2b(), f.a2b()), path);
    }

    @Test
    void activePathOfNullOrUnknownLeafIsEmpty() {
        Fixture f = fixture();
        assertTrue(ChatBranchTree.activePath(byId(f.all()), null).isEmpty());
        assertTrue(ChatBranchTree.activePath(byId(f.all()), UUID.randomUUID()).isEmpty());
    }

    @Test
    void activePathStopsOnAParentCycleInsteadOfLooping() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Node na = new Node(a, b, MessageRole.USER, "x", 0, T0);
        Node nb = new Node(b, a, MessageRole.ASSISTANT, "y", 0, T0);
        List<Node> path = ChatBranchTree.activePath(byId(List.of(na, nb)), a);
        assertEquals(2, path.size());
    }

    @Test
    void aLinearChainWithNullRootIsOneVersionPerMessage() {
        Node u1 = node(null, MessageRole.USER, "q", 0);
        Node a1 = node(u1.id(), MessageRole.ASSISTANT, "r", 0);
        List<Node> all = List.of(u1, a1);
        SiblingInfo info = ChatBranchTree.siblingInfo(all, a1.id());
        assertEquals(1, info.count());
        assertEquals(0, info.index());
        assertNull(ChatBranchTree.navigate(all, a1.id(), 1));
        assertNull(ChatBranchTree.navigate(all, a1.id(), -1));
    }

    @Test
    void siblingInfoListsEveryVersionInOrderWithThePositionOfTheOneAskedAbout() {
        Fixture f = fixture();
        SiblingInfo info = ChatBranchTree.siblingInfo(f.all(), f.u2b().id());
        assertEquals(List.of(f.u2().id(), f.u2b().id()), info.ids());
        assertEquals(1, info.index());
        assertEquals(2, info.count());
        // Roots are siblings of each other too: they share the null parent.
        Node otherRoot = node(null, MessageRole.USER, "q1 edited", 1);
        List<Node> withRoots = new ArrayList<>(f.all());
        withRoots.add(otherRoot);
        assertEquals(2, ChatBranchTree.siblingInfo(withRoots, f.u1().id()).count());
    }

    @Test
    void siblingInfoOfAnUnknownMessageIsNull() {
        assertNull(ChatBranchTree.siblingInfo(fixture().all(), UUID.randomUUID()));
    }

    @Test
    void navigationMovesOneVersionAndIsClampedAtBothEnds() {
        Fixture f = fixture();
        assertEquals(f.u2b().id(), ChatBranchTree.navigate(f.all(), f.u2().id(), 1));
        assertEquals(f.u2().id(), ChatBranchTree.navigate(f.all(), f.u2b().id(), -1));
        assertNull(ChatBranchTree.navigate(f.all(), f.u2b().id(), 1));
        assertNull(ChatBranchTree.navigate(f.all(), f.u2().id(), -1));
    }

    @Test
    void leafUnderFollowsTheMostRecentChildDownToALeaf() {
        Fixture f = fixture();
        // Under a1 the newest child is u2b (created after u2), whose only child is a2b.
        assertEquals(f.a2b().id(), ChatBranchTree.leafUnder(f.all(), f.a1().id()));
        // Switching to the original u2 lands on its own leaf, not on the edited branch.
        assertEquals(f.a2().id(), ChatBranchTree.leafUnder(f.all(), f.u2().id()));
        // A leaf is its own leaf.
        assertEquals(f.a2().id(), ChatBranchTree.leafUnder(f.all(), f.a2().id()));
        assertNull(ChatBranchTree.leafUnder(f.all(), null));
    }

    @Test
    void alignmentOfAnEmptyLiveTranscriptLearnsNothing() {
        Fixture f = fixture();
        List<Node> path = ChatBranchTree.activePath(byId(f.all()), f.a2().id());
        assertEquals(Kind.NONE, ChatBranchTree.align(path, List.of()).kind());
    }

    @Test
    void alignmentOfTheSameTranscriptIsSame() {
        Fixture f = fixture();
        List<Node> path = ChatBranchTree.activePath(byId(f.all()), f.a2().id());
        assertEquals(Kind.SAME, ChatBranchTree.align(path, turnsOf(path)).kind());
    }

    @Test
    void turnsChatAppendedAfterTheLeafAreATailUnderTheLeaf() {
        Fixture f = fixture();
        List<Node> path = ChatBranchTree.activePath(byId(f.all()), f.a1().id());
        List<ChatTurn> live = turnsOf(path);
        live.add(turn(MessageRole.USER, "q3"));
        live.add(turn(MessageRole.ASSISTANT, "r3"));
        Alignment a = ChatBranchTree.align(path, live);
        assertEquals(Kind.TAIL, a.kind());
        assertEquals(path.size() - 1, a.anchorIndex());
        assertEquals(path.size(), a.tailFrom());
    }

    @Test
    void aShorterPrefixIsARewindToThatNode() {
        Fixture f = fixture();
        List<Node> path = ChatBranchTree.activePath(byId(f.all()), f.a2().id());
        Alignment a = ChatBranchTree.align(path, turnsOf(path).subList(0, 2));
        assertEquals(Kind.REWIND, a.kind());
        assertEquals(1, a.anchorIndex());
    }

    @Test
    void divergingAfterASharedPrefixIsATailUnderTheLastSharedNode() {
        Fixture f = fixture();
        List<Node> path = ChatBranchTree.activePath(byId(f.all()), f.a2().id());
        List<ChatTurn> live = new ArrayList<>(turnsOf(path).subList(0, 2));
        live.add(turn(MessageRole.USER, "something else"));
        Alignment a = ChatBranchTree.align(path, live);
        assertEquals(Kind.TAIL, a.kind());
        assertEquals(1, a.anchorIndex());
        assertEquals(2, a.tailFrom());
    }

    @Test
    void aTranscriptWithNothingInCommonHangsOffTheRoot() {
        Fixture f = fixture();
        List<Node> path = ChatBranchTree.activePath(byId(f.all()), f.a2().id());
        Alignment a = ChatBranchTree.align(path, List.of(turn(MessageRole.USER, "brand new")));
        assertEquals(Kind.TAIL, a.kind());
        assertEquals(-1, a.anchorIndex());
        assertEquals(0, a.tailFrom());
    }

    @Test
    void anEmptyPathTurnsTheWholeTranscriptIntoNewRoots() {
        Alignment a = ChatBranchTree.align(List.of(), List.of(turn(MessageRole.USER, "q"), turn(MessageRole.ASSISTANT, "r")));
        assertEquals(Kind.TAIL, a.kind());
        assertEquals(-1, a.anchorIndex());
        assertEquals(0, a.tailFrom());
    }

    @Test
    void aTranscriptWhoseHeadWasTrimmedStillAlignsToTheTailOfThePath() {
        Fixture f = fixture();
        List<Node> path = ChatBranchTree.activePath(byId(f.all()), f.a2().id());
        // Redis kept only the newest entries: the transcript starts at path index 2 and has one extra turn.
        List<ChatTurn> live = new ArrayList<>(turnsOf(path).subList(2, 4));
        live.add(turn(MessageRole.USER, "q3"));
        Alignment a = ChatBranchTree.align(path, live);
        assertEquals(Kind.TAIL, a.kind());
        assertEquals(3, a.anchorIndex());
        assertEquals(2, a.tailFrom());
    }

    @Test
    void aTrimmedTranscriptThatEndsAtTheLeafIsSame() {
        Fixture f = fixture();
        List<Node> path = ChatBranchTree.activePath(byId(f.all()), f.a2().id());
        assertEquals(Kind.SAME, ChatBranchTree.align(path, turnsOf(path).subList(2, 4)).kind());
    }

    @Test
    void roleMattersWhenMatchingATurnToANode() {
        Node n = node(null, MessageRole.USER, "same text", 0);
        assertTrue(ChatBranchTree.matches(n, turn(MessageRole.USER, "same text")));
        assertEquals(false, ChatBranchTree.matches(n, turn(MessageRole.ASSISTANT, "same text")));
    }
}
