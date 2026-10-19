package com.agenticai.core.workflow.expression;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain immutable {@link EvalContext} built through {@link #builder()}; used by tests and simple
 * callers.
 */
public final class BasicEvalContext implements EvalContext {

    private final String input;
    private final Map<String, NodeOutput> byLabel;
    private final Map<String, NodeOutput> byId;
    private final Object trigger;
    private final Map<String, Object> run;
    private final Map<String, Object> workflow;
    private final Map<String, Object> vars;
    private final Object item;
    private final Object index;
    private final List<Object> items;
    private final Clock clock;

    private BasicEvalContext(Builder b) {
        this.input = b.input == null ? "" : b.input;
        this.byLabel = Map.copyOf(b.byLabel);
        this.byId = Map.copyOf(b.byId);
        this.trigger = b.trigger;
        this.run = b.run;
        this.workflow = b.workflow;
        this.vars = b.vars;
        this.item = b.item;
        this.index = b.index;
        this.items = b.items;
        this.clock = b.clock;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static BasicEvalContext ofInput(String input) {
        return builder().input(input).build();
    }

    @Override
    public String input() {
        return input;
    }

    @Override
    public NodeOutput nodeByLabel(String label) {
        return label == null ? null : byLabel.get(label);
    }

    @Override
    public NodeOutput nodeById(String id) {
        return id == null ? null : byId.get(id);
    }

    @Override
    public Object trigger() {
        return trigger;
    }

    @Override
    public Map<String, Object> run() {
        return run;
    }

    @Override
    public Map<String, Object> workflow() {
        return workflow;
    }

    @Override
    public Map<String, Object> vars() {
        return vars;
    }

    @Override
    public Object item() {
        return item;
    }

    @Override
    public Object index() {
        return index;
    }

    @Override
    public List<Object> items() {
        return items;
    }

    @Override
    public Clock clock() {
        return clock;
    }

    public static final class Builder {
        private String input = "";
        private final Map<String, NodeOutput> byLabel = new HashMap<>();
        private final Map<String, NodeOutput> byId = new HashMap<>();
        private Object trigger;
        private Map<String, Object> run = Map.of();
        private Map<String, Object> workflow = Map.of();
        private Map<String, Object> vars = Map.of();
        private Object item;
        private Object index;
        private List<Object> items;
        private Clock clock = Clock.systemUTC();

        public Builder input(String v) {
            this.input = v;
            return this;
        }

        /**
         * Registers an upstream node; on duplicate labels the first one wins (the linter flags the
         * ambiguity).
         */
        public Builder node(NodeOutput n) {
            if (n.label() != null) {
                byLabel.putIfAbsent(n.label(), n);
            }
            if (n.id() != null) {
                byId.put(n.id(), n);
            }
            return this;
        }

        public Builder trigger(Object v) {
            this.trigger = v;
            return this;
        }

        public Builder run(Map<String, Object> v) {
            this.run = v == null ? Map.of() : v;
            return this;
        }

        public Builder workflow(Map<String, Object> v) {
            this.workflow = v == null ? Map.of() : v;
            return this;
        }

        public Builder vars(Map<String, Object> v) {
            this.vars = v == null ? Map.of() : v;
            return this;
        }

        public Builder loop(Object item, Object index, List<Object> items) {
            this.item = item;
            this.index = index;
            this.items = items;
            return this;
        }

        public Builder clock(Clock v) {
            this.clock = v == null ? Clock.systemUTC() : v;
            return this;
        }

        public BasicEvalContext build() {
            return new BasicEvalContext(this);
        }
    }
}
