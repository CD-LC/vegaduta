package com.agenticai.core.settings;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One catalog entry, declared in code by the module that owns the behaviour. The platform value is read from the SAME
 * property object the runtime uses ({@link #platformValue()} calls the supplier on every read), so there is one source
 * of truth and the configuration drift test keeps covering the default.
 *
 * <p>Keys are {@code <area>.<name>[.<name>...]} in camelCase segments and no key may be a prefix of another one
 * (the i18n tree {@code settings.<key>.label} would clash). i18n keys: {@code settings.<key>.label},
 * {@code .description}, {@code .help}.
 */
public final class SettingDefinition {

    static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*)+");

    private final String key;
    private final String group;
    private final SettingType type;
    private final SettingUnit unit;
    private final Double min;
    private final Double max;
    private final Double step;
    private final List<String> enumValues;
    private final SettingRisk risk;
    private final List<SettingScope> scopes;
    private final SettingRole minRole;
    private final Strictness strictness;
    private final Supplier<Object> platform;
    private final String platformProperty;
    private final String docsAnchor;

    private SettingDefinition(Builder b) {
        this.key = b.key;
        this.group = b.group;
        this.type = b.type;
        this.unit = b.unit != null ? b.unit : (b.type == SettingType.PERCENT ? SettingUnit.PERCENT : null);
        this.min = b.min != null ? b.min : (b.type == SettingType.PERCENT ? Double.valueOf(0) : null);
        this.max = b.max != null ? b.max : (b.type == SettingType.PERCENT ? Double.valueOf(100) : null);
        this.step = b.step;
        this.enumValues = b.enumValues == null ? null : List.copyOf(b.enumValues);
        this.risk = b.risk;
        this.scopes = List.copyOf(b.scopes);
        this.minRole = b.minRole != null ? b.minRole : (b.risk == SettingRisk.SECURITY ? SettingRole.TENANT_ADMIN : SettingRole.EDITOR);
        this.strictness = b.strictness;
        this.platform = Objects.requireNonNull(b.platform, "platform value supplier of " + b.key);
        this.platformProperty = b.platformProperty;
        this.docsAnchor = b.docsAnchor;
        validateShape();
    }

    private void validateShape() {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Setting key must look like area.name: " + key);
        }
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("Setting " + key + " needs a group");
        }
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("Setting " + key + " needs at least one scope");
        }
        if (type == SettingType.ENUM && (enumValues == null || enumValues.size() < 2)) {
            throw new IllegalArgumentException("Enum setting " + key + " needs enumValues");
        }
        if (type != SettingType.ENUM && enumValues != null) {
            throw new IllegalArgumentException("Only enum settings take enumValues: " + key);
        }
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("min > max for " + key);
        }
        if (risk == SettingRisk.SECURITY) {
            if (strictness == Strictness.NONE) {
                throw new IllegalArgumentException("Security setting " + key + " needs a strictness direction");
            }
            if (minRole != SettingRole.TENANT_ADMIN) {
                throw new IllegalArgumentException("Security setting " + key + " needs minRole TENANT_ADMIN");
            }
        }
        if (strictness == Strictness.ENUM_ORDER && type != SettingType.ENUM) {
            throw new IllegalArgumentException("ENUM_ORDER only for enum settings: " + key);
        }
        if ((strictness == Strictness.HIGHER_IS_STRICTER || strictness == Strictness.LOWER_IS_STRICTER) && !type.isNumeric()) {
            throw new IllegalArgumentException("Numeric strictness only for numeric settings: " + key);
        }
        if (type == SettingType.DURATION && unit != SettingUnit.MS && unit != SettingUnit.S) {
            throw new IllegalArgumentException("Duration setting " + key + " needs unit ms or s");
        }
    }

    public String key() { return key; }
    public String group() { return group; }
    public SettingType type() { return type; }
    public SettingUnit unit() { return unit; }
    public Double min() { return min; }
    public Double max() { return max; }
    public Double step() { return step; }
    public List<String> enumValues() { return enumValues; }
    public SettingRisk risk() { return risk; }
    public List<SettingScope> scopes() { return scopes; }
    public SettingRole minRole() { return minRole; }
    public Strictness strictness() { return strictness; }
    public String platformProperty() { return platformProperty; }
    public String docsAnchor() { return docsAnchor; }

    public String labelKey() { return "settings." + key + ".label"; }
    public String descriptionKey() { return "settings." + key + ".description"; }
    public String helpKey() { return "settings." + key + ".help"; }

    public boolean allows(SettingScope scope) {
        return scopes.contains(scope);
    }

    /** The value the platform (yml / environment / code default) currently gives, in canonical form. */
    public Object platformValue() {
        return canonical(platform.get());
    }

    // ------------------------------------------------------------------------------------------ values

    /**
     * Converts a value read from a property object (Number, Boolean, String, Enum, Duration) to the canonical form of
     * {@link #type()}, without bounds checks (the platform's own value is the operator's, never clamped).
     */
    Object canonical(Object v) {
        if (v == null) {
            return switch (type) {
                case BOOL -> Boolean.FALSE;
                case ENUM, STRING, TEXT -> type == SettingType.ENUM ? enumValues.get(0) : "";
                default -> Long.valueOf(0);
            };
        }
        switch (type) {
            case BOOL:
                return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
            case ENUM: {
                String s = v instanceof Enum<?> e ? e.name() : String.valueOf(v);
                return matchEnum(s).orElse(s.toLowerCase(Locale.ROOT));
            }
            case STRING:
            case TEXT:
                return String.valueOf(v);
            case DURATION: {
                if (v instanceof Duration d) {
                    return unit == SettingUnit.MS ? d.toMillis() : d.toSeconds();
                }
                return ((Number) v).longValue();
            }
            case INT:
            case LONG:
                return ((Number) v).longValue();
            default:
                return ((Number) v).doubleValue();
        }
    }

    private java.util.Optional<String> matchEnum(String s) {
        if (enumValues == null) {
            return java.util.Optional.empty();
        }
        for (String e : enumValues) {
            if (e.equalsIgnoreCase(s.strip())) {
                return java.util.Optional.of(e);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Validates a client-supplied value against the type, bounds and enum values and returns the canonical form.
     *
     * @throws SettingInvalidException naming the key, the reason and the allowed range / values
     */
    public Object normalize(Object raw) {
        if (raw == null) {
            throw invalid("A value is required. To go back to the inherited value, reset the setting instead.");
        }
        switch (type) {
            case BOOL:
                if (raw instanceof Boolean b) {
                    return b;
                }
                throw invalid("Must be true or false.");
            case ENUM: {
                if (!(raw instanceof String s)) {
                    throw invalid("Must be one of: " + String.join(", ", enumValues) + ".");
                }
                return matchEnum(s).orElseThrow(() -> invalid("Must be one of: " + String.join(", ", enumValues) + "."));
            }
            case STRING:
            case TEXT: {
                if (!(raw instanceof String s)) {
                    throw invalid("Must be text.");
                }
                String text = type == SettingType.STRING ? s.strip() : s;
                int lo = min == null ? 0 : min.intValue();
                int hi = max == null ? (type == SettingType.STRING ? 200 : 20_000) : max.intValue();
                if (text.length() < lo || text.length() > hi) {
                    throw new SettingInvalidException(key, "Must be between " + lo + " and " + hi + " characters.", lo, hi, null);
                }
                return text;
            }
            default:
                return normalizeNumber(raw);
        }
    }

    private Object normalizeNumber(Object raw) {
        if (!(raw instanceof Number n) || raw instanceof Boolean) {
            throw invalid("Must be a number.");
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw invalid("Must be a finite number.");
        }
        if (type.isIntegral()) {
            BigDecimal exact = new BigDecimal(n.toString());
            if (exact.stripTrailingZeros().scale() > 0) {
                throw rangeError("Must be a whole number.");
            }
            if (exact.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0 || exact.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                throw rangeError("Is out of range.");
            }
            long l = exact.longValue();
            if (type == SettingType.INT && (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE)) {
                throw rangeError("Is out of range.");
            }
            checkRange(l);
            return l;
        }
        checkRange(d);
        return d;
    }

    private void checkRange(double d) {
        if ((min != null && d < min) || (max != null && d > max)) {
            throw rangeError(rangeMessage());
        }
    }

    private String rangeMessage() {
        String u = unit == null ? "" : " " + unit.json();
        if (min != null && max != null) {
            return "Must be between " + fmt(min) + " and " + fmt(max) + u + ".";
        }
        return min != null ? "Must be at least " + fmt(min) + u + "." : "Must be at most " + fmt(max) + u + ".";
    }

    private String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    private SettingInvalidException rangeError(String message) {
        return new SettingInvalidException(key, message, num(min), num(max), null);
    }

    private SettingInvalidException invalid(String message) {
        return new SettingInvalidException(key, message, num(min), num(max), enumValues);
    }

    /** min / max as JSON numbers: whole when the type is integral. */
    Number num(Double d) {
        if (d == null) {
            return null;
        }
        return type.isIntegral() || d == Math.rint(d) ? (Number) Long.valueOf(d.longValue()) : d;
    }

    /**
     * A stored override read back: numbers are clamped into the CURRENT bounds (tightening a bound tightens the
     * overrides already stored), other types must still be valid; null when the stored value is unusable (the
     * override is then ignored).
     */
    Object coerceStored(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            if (type.isNumeric() && raw instanceof Number n && Double.isFinite(n.doubleValue())) {
                double d = n.doubleValue();
                if (min != null) {
                    d = Math.max(min, d);
                }
                if (max != null) {
                    d = Math.min(max, d);
                }
                return type.isIntegral() ? (Object) Math.round(d) : (Object) d;
            }
            return normalize(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------ strictness

    /** True when {@code candidate} is at least as strict as {@code baseline} (always true for a non-security setting). */
    public boolean isAtLeastAsStrict(Object candidate, Object baseline) {
        if (strictness == Strictness.NONE || candidate == null || baseline == null) {
            return true;
        }
        switch (strictness) {
            case HIGHER_IS_STRICTER:
                return ((Number) candidate).doubleValue() >= ((Number) baseline).doubleValue();
            case LOWER_IS_STRICTER:
                return ((Number) candidate).doubleValue() <= ((Number) baseline).doubleValue();
            default: {
                int c = enumValues.indexOf(String.valueOf(candidate));
                int b = enumValues.indexOf(String.valueOf(baseline));
                return b < 0 || c >= b;
            }
        }
    }

    // ------------------------------------------------------------------------------------------ builder

    public static Builder builder(String key, String group, SettingType type) {
        return new Builder(key, group, type);
    }

    public static final class Builder {
        private final String key;
        private final String group;
        private final SettingType type;
        private SettingUnit unit;
        private Double min;
        private Double max;
        private Double step;
        private List<String> enumValues;
        private SettingRisk risk = SettingRisk.LOW;
        private final List<SettingScope> scopes = new ArrayList<>();
        private SettingRole minRole;
        private Strictness strictness = Strictness.NONE;
        private Supplier<Object> platform;
        private String platformProperty;
        private String docsAnchor;

        private Builder(String key, String group, SettingType type) {
            this.key = key;
            this.group = group;
            this.type = type;
        }

        public Builder unit(SettingUnit unit) { this.unit = unit; return this; }
        public Builder range(double min, double max) { this.min = min; this.max = max; return this; }
        public Builder min(double min) { this.min = min; return this; }
        public Builder max(double max) { this.max = max; return this; }
        public Builder step(double step) { this.step = step; return this; }
        public Builder enumValues(String... values) { this.enumValues = List.of(values); return this; }
        public Builder risk(SettingRisk risk) { this.risk = risk; return this; }
        public Builder scopes(SettingScope... s) { this.scopes.clear(); this.scopes.addAll(List.of(s)); return this; }
        public Builder minRole(SettingRole role) { this.minRole = role; return this; }
        public Builder strictness(Strictness s) { this.strictness = s; return this; }
        public Builder platform(Supplier<Object> supplier) { this.platform = supplier; return this; }
        /** The {@code application.yml} key the platform value comes from; used to tell DEFAULT from PLATFORM. */
        public Builder property(String property) { this.platformProperty = property; return this; }
        public Builder docsAnchor(String anchor) { this.docsAnchor = anchor; return this; }

        public SettingDefinition build() {
            return new SettingDefinition(this);
        }
    }
}
