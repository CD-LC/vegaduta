package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link Node#children}: the exact child set of every node variant. */
class NodeTest {

    private final Node.Lit lit = new Node.Lit(1L, 0, 1);
    private final Node.Ident ident = new Node.Ident("x", 0, 1);

    @Test
    void leavesHaveNoChildren() {
        assertTrue(Node.children(lit).isEmpty());
        assertTrue(Node.children(ident).isEmpty());
    }

    @Test
    void memberAndIndexExposeTheirTarget() {
        Node.Member member = new Node.Member(ident, "p", false, 0, 3);
        assertEquals(List.of(ident), Node.children(member));

        Node.Index index = new Node.Index(ident, lit, false, 0, 4);
        assertEquals(List.of(ident, lit), Node.children(index));

        Node.Index optional = new Node.Index(ident, lit, true, 0, 4);
        assertEquals(List.of(ident, lit), Node.children(optional));
    }

    @Test
    void callsExposeArgumentsAndMethodCallsTheirTargetToo() {
        Node.Call call = new Node.Call("f", List.of(lit, ident), 0, 6);
        assertEquals(List.of(lit, ident), Node.children(call));

        Node.MethodCall method = new Node.MethodCall(ident, "m", List.of(lit), true, 0, 8);
        assertEquals(List.of(ident, lit), Node.children(method));
    }

    @Test
    void unaryBinaryAndTernaryExposeTheirOperands() {
        Node.Unary unary = new Node.Unary("-", lit, 0, 2);
        assertEquals(List.of(lit), Node.children(unary));

        Node.Binary binary = new Node.Binary("+", lit, ident, 0, 5);
        assertEquals(List.of(lit, ident), Node.children(binary));

        Node.Ternary ternary = new Node.Ternary(ident, lit, lit, 0, 9);
        assertEquals(List.of(ident, lit, lit), Node.children(ternary));
    }

    @Test
    void literalsAndLambdasExposeTheirParts() {
        Node.ArrayLit array = new Node.ArrayLit(List.of(lit, ident), 0, 5);
        assertEquals(List.of(lit, ident), Node.children(array));

        Node.ObjectLit object = new Node.ObjectLit(List.of("a", "b"), List.of(lit, ident), 0, 9);
        assertEquals(List.of(lit, ident), Node.children(object), "keys are not children");

        Node.Lambda lambda = new Node.Lambda("x", ident, 0, 6);
        assertEquals(List.of(ident), Node.children(lambda));
    }
}
