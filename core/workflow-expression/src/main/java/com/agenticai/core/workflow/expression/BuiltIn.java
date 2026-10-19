package com.agenticai.core.workflow.expression;

import java.util.List;

/**
 * One entry in the built-in function registry. The same metadata feeds editor autocomplete and the
 * docs page, so documentation cannot drift from the code (a unit test checks every field is filled
 * and that {@code example} really evaluates to {@code exampleResult}).
 */
public final class BuiltIn {

    /**
     * Eager implementation: arguments are already evaluated ({@link Evaluator.Closure} for arrow
     * functions).
     */
    @FunctionalInterface
    interface Impl {
        Object apply(Evaluator ev, List<Object> args);
    }

    /**
     * Lazy implementation: receives argument nodes and decides what to evaluate ({@code if}, {@code
     * coalesce}).
     */
    @FunctionalInterface
    interface LazyImpl {
        Object apply(Evaluator ev, List<Node> args, Evaluator.Scope scope);
    }

    private final String name;
    private final String category;
    private final int minArgs;
    private final int maxArgs;
    private final String signature;
    private final String doc;
    private final String example;
    private final String exampleResult;
    private final Impl impl;
    private final LazyImpl lazyImpl;

    BuiltIn(
            String name,
            String category,
            int minArgs,
            int maxArgs,
            String signature,
            String doc,
            String example,
            String exampleResult,
            Impl impl,
            LazyImpl lazyImpl) {
        this.name = name;
        this.category = category;
        this.minArgs = minArgs;
        this.maxArgs = maxArgs;
        this.signature = signature;
        this.doc = doc;
        this.example = example;
        this.exampleResult = exampleResult;
        this.impl = impl;
        this.lazyImpl = lazyImpl;
    }

    public String name() {
        return name;
    }

    public String category() {
        return category;
    }

    public int minArgs() {
        return minArgs;
    }

    /** -1 for "any number". */
    public int maxArgs() {
        return maxArgs;
    }

    public String signature() {
        return signature;
    }

    public String doc() {
        return doc;
    }

    /** An expression (no braces) that evaluates without any context. */
    public String example() {
        return example;
    }

    /** The compact JSON the example evaluates to. */
    public String exampleResult() {
        return exampleResult;
    }

    /** True for {@code if}/{@code coalesce}, which only evaluate the arguments they need. */
    public boolean lazy() {
        return lazyImpl != null;
    }

    Impl impl() {
        return impl;
    }

    LazyImpl lazyImpl() {
        return lazyImpl;
    }
}
