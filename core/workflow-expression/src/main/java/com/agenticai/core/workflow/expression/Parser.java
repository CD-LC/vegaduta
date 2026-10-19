package com.agenticai.core.workflow.expression;

import com.agenticai.core.workflow.expression.Lexer.Kind;
import com.agenticai.core.workflow.expression.Lexer.Token;
import java.util.ArrayList;
import java.util.List;

/**
 * Pratt parser for the spec 3.3 grammar. Binding powers, low to high: {@code ?:}(10) {@code ??}(20)
 * {@code ||}(30) {@code &&}(40) comparison/equality(50) {@code + -}(60) {@code * / %}(70),
 * unary(80), postfix(access/call).
 */
final class Parser {

    private static final int UNARY_BP = 80;

    private final List<Token> toks;
    private final Limits limits;
    private int pos;
    private int nodeCount;
    private int depth;

    private Parser(List<Token> toks, Limits limits) {
        this.toks = toks;
        this.limits = limits;
    }

    static Node parse(String src, Limits limits) {
        if (src.length() > limits.maxSourceLength()) {
            throw new ExpressionException(
                    "Expression is too long ("
                            + src.length()
                            + " characters, limit "
                            + limits.maxSourceLength()
                            + ")",
                    limits.maxSourceLength(),
                    src.length());
        }
        Parser p = new Parser(Lexer.lex(src), limits);
        Node n = p.parseExpr(0);
        Token t = p.peek();
        if (t.kind() != Kind.EOF) {
            throw unexpected(t);
        }
        return n;
    }

    // ---- token helpers

    private Token peek() {
        return toks.get(pos);
    }

    private Token peekAt(int offset) {
        return toks.get(Math.min(pos + offset, toks.size() - 1));
    }

    private Token next() {
        Token t = toks.get(pos);
        if (t.kind() != Kind.EOF) {
            pos++;
        }
        return t;
    }

    private boolean isPunct(Token t, String text) {
        return t.kind() == Kind.PUNCT && t.text().equals(text);
    }

    private Token expect(String text) {
        Token t = peek();
        if (!isPunct(t, text)) {
            throw new ExpressionException(
                    "Expected '" + text + "' but found " + t.describe(),
                    t.start(),
                    Math.max(t.end(), t.start() + 1));
        }
        return next();
    }

    private static ExpressionException unexpected(Token t) {
        return new ExpressionException(
                "Unexpected " + t.describe(), t.start(), Math.max(t.end(), t.start() + 1));
    }

    private <T extends Node> T mk(T node) {
        if (++nodeCount > limits.maxNodes()) {
            throw new ExpressionException(
                    "Expression is too complex (more than " + limits.maxNodes() + " parts)",
                    node.start(),
                    node.end());
        }
        return node;
    }

    private static int infixBp(Token t) {
        if (t.kind() != Kind.PUNCT) {
            return 0;
        }
        return switch (t.text()) {
            case "?" -> 10;
            case "??" -> 20;
            case "||" -> 30;
            case "&&" -> 40;
            case "==", "!=", "<", ">", "<=", ">=" -> 50;
            case "+", "-" -> 60;
            case "*", "/", "%" -> 70;
            default -> 0;
        };
    }

    // ---- grammar

    private Node parseExpr(int minBp) {
        if (++depth > limits.maxDepth()) {
            Token t = peek();
            throw new ExpressionException(
                    "Expression is nested too deeply (limit " + limits.maxDepth() + ")",
                    t.start(),
                    Math.max(t.end(), t.start() + 1));
        }
        try {
            Node left = parsePrefix();
            while (true) {
                Token t = peek();
                int bp = infixBp(t);
                if (bp <= minBp) {
                    return left;
                }
                next();
                if (t.text().equals("?")) {
                    Node then = parseExpr(0);
                    expect(":");
                    Node otherwise = parseExpr(bp - 1); // right associative
                    left =
                            mk(
                                    new Node.Ternary(
                                            left, then, otherwise, left.start(), otherwise.end()));
                } else {
                    Node right = parseExpr(bp);
                    left = mk(new Node.Binary(t.text(), left, right, left.start(), right.end()));
                }
            }
        } finally {
            depth--;
        }
    }

    private Node parsePrefix() {
        Token t = next();
        Node n;
        switch (t.kind()) {
            case NUM, STR -> n = mk(new Node.Lit(t.value(), t.start(), t.end()));
            case IDENT -> {
                switch (t.text()) {
                    case "true" -> n = mk(new Node.Lit(Boolean.TRUE, t.start(), t.end()));
                    case "false" -> n = mk(new Node.Lit(Boolean.FALSE, t.start(), t.end()));
                    case "null" -> n = mk(new Node.Lit(null, t.start(), t.end()));
                    default -> {
                        if (isPunct(peek(), "(")) {
                            next();
                            List<Node> args = parseArgs();
                            n =
                                    mk(
                                            new Node.Call(
                                                    t.text(),
                                                    args,
                                                    t.start(),
                                                    toks.get(pos - 1).end()));
                        } else {
                            n = mk(new Node.Ident(t.text(), t.start(), t.end()));
                        }
                    }
                }
            }
            case PUNCT -> {
                switch (t.text()) {
                    case "(" -> {
                        n = parseExpr(0);
                        expect(")");
                    }
                    case "[" -> n = parseArray(t);
                    case "{" -> n = parseObject(t);
                    case "-", "!" -> {
                        Node operand = parseExpr(UNARY_BP);
                        n = mk(new Node.Unary(t.text(), operand, t.start(), operand.end()));
                    }
                    default -> throw unexpected(t);
                }
            }
            default -> throw unexpected(t);
        }
        return parsePostfix(n);
    }

    private Node parsePostfix(Node n) {
        while (true) {
            Token t = peek();
            if (t.kind() != Kind.PUNCT) {
                return n;
            }
            switch (t.text()) {
                case ".", "?." -> {
                    boolean optional = t.text().equals("?.");
                    next();
                    if (optional && isPunct(peek(), "[")) {
                        next();
                        Node idx = parseExpr(0);
                        Token close = expect("]");
                        n = mk(new Node.Index(n, idx, true, n.start(), close.end()));
                        continue;
                    }
                    Token name = next();
                    if (name.kind() != Kind.IDENT) {
                        throw new ExpressionException(
                                "Expected a property name after '"
                                        + t.text()
                                        + "' but found "
                                        + name.describe(),
                                name.start(),
                                Math.max(name.end(), name.start() + 1));
                    }
                    if (isPunct(peek(), "(")) {
                        next();
                        List<Node> args = parseArgs();
                        n =
                                mk(
                                        new Node.MethodCall(
                                                n,
                                                name.text(),
                                                args,
                                                optional,
                                                n.start(),
                                                toks.get(pos - 1).end()));
                    } else {
                        n = mk(new Node.Member(n, name.text(), optional, n.start(), name.end()));
                    }
                }
                case "[" -> {
                    next();
                    Node idx = parseExpr(0);
                    Token close = expect("]");
                    n = mk(new Node.Index(n, idx, false, n.start(), close.end()));
                }
                default -> {
                    return n;
                }
            }
        }
    }

    private List<Node> parseArgs() {
        List<Node> args = new ArrayList<>();
        if (isPunct(peek(), ")")) {
            next();
            return args;
        }
        while (true) {
            args.add(lambdaAhead() ? parseLambda() : parseExpr(0));
            Token t = peek();
            if (isPunct(t, ",")) {
                next();
            } else {
                expect(")");
                return args;
            }
        }
    }

    private boolean lambdaAhead() {
        Token a = peek();
        if (a.kind() == Kind.IDENT && isPunct(peekAt(1), "=>")) {
            return true;
        }
        return isPunct(a, "(")
                && peekAt(1).kind() == Kind.IDENT
                && isPunct(peekAt(2), ")")
                && isPunct(peekAt(3), "=>");
    }

    private Node parseLambda() {
        Token first = peek();
        Token param;
        if (isPunct(first, "(")) {
            next();
            param = next();
            next();
        } else {
            param = next();
        }
        expect("=>");
        Node body = parseExpr(0);
        return mk(new Node.Lambda(param.text(), body, first.start(), body.end()));
    }

    private Node parseArray(Token open) {
        List<Node> items = new ArrayList<>();
        while (!isPunct(peek(), "]")) {
            items.add(parseExpr(0));
            if (isPunct(peek(), ",")) {
                next();
            } else {
                break;
            }
        }
        Token close = expect("]");
        return mk(new Node.ArrayLit(items, open.start(), close.end()));
    }

    private Node parseObject(Token open) {
        List<String> keys = new ArrayList<>();
        List<Node> values = new ArrayList<>();
        while (!isPunct(peek(), "}")) {
            Token key = next();
            if (key.kind() != Kind.IDENT && key.kind() != Kind.STR && key.kind() != Kind.NUM) {
                throw new ExpressionException(
                        "Expected an object key but found " + key.describe(),
                        key.start(),
                        Math.max(key.end(), key.start() + 1));
            }
            keys.add(key.kind() == Kind.STR ? (String) key.value() : key.text());
            expect(":");
            values.add(parseExpr(0));
            if (isPunct(peek(), ",")) {
                next();
            } else {
                break;
            }
        }
        Token close = expect("}");
        return mk(new Node.ObjectLit(keys, values, open.start(), close.end()));
    }
}
