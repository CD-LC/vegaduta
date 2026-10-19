package com.agenticai.core.workflow.expression;

import java.util.ArrayList;
import java.util.List;

/** Parsed expression tree. Immutable; every node knows its source span. */
sealed interface Node {

    int start();

    int end();

    record Lit(Object value, int start, int end) implements Node {}

    record Ident(String name, int start, int end) implements Node {}

    record Member(Node target, String name, boolean optional, int start, int end) implements Node {}

    record Index(Node target, Node index, boolean optional, int start, int end) implements Node {}

    record Call(String name, List<Node> args, int start, int end) implements Node {}

    record MethodCall(
            Node target, String name, List<Node> args, boolean optional, int start, int end)
            implements Node {}

    record Unary(String op, Node operand, int start, int end) implements Node {}

    record Binary(String op, Node left, Node right, int start, int end) implements Node {}

    record Ternary(Node cond, Node then, Node otherwise, int start, int end) implements Node {}

    record ArrayLit(List<Node> items, int start, int end) implements Node {}

    record ObjectLit(List<String> keys, List<Node> values, int start, int end) implements Node {}

    /** Single-parameter arrow function; only legal as an argument to a built-in. */
    record Lambda(String param, Node body, int start, int end) implements Node {}

    static List<Node> children(Node n) {
        return switch (n) {
            case Lit l -> List.of();
            case Ident i -> List.of();
            case Member m -> List.of(m.target());
            case Index x -> List.of(x.target(), x.index());
            case Call c -> c.args();
            case MethodCall m -> {
                List<Node> all = new ArrayList<>();
                all.add(m.target());
                all.addAll(m.args());
                yield all;
            }
            case Unary u -> List.of(u.operand());
            case Binary b -> List.of(b.left(), b.right());
            case Ternary t -> List.of(t.cond(), t.then(), t.otherwise());
            case ArrayLit a -> a.items();
            case ObjectLit o -> o.values();
            case Lambda l -> List.of(l.body());
        };
    }
}
