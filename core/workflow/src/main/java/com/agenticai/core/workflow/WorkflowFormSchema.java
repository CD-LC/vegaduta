package com.agenticai.core.workflow;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The field schema of a hosted-form trigger (a Trigger node whose config carries {@code triggerType: "form"}) and
 * the server-side validation of a submission against it. Pure and static so it is unit-testable without Spring;
 * {@link WorkflowFormService} owns resolution, rate limits and starting the run.
 *
 * <p>Config, inside the Trigger node's {@code data.config}:
 * <pre>
 * "triggerType": "form",
 * "form": { "title", "description", "submitLabel", "successMessage", "redirectUrl" (https only),
 *           "fields": [ { "key", "label", "type", "required", "placeholder", "options", "maxLength", "min", "max" } ] }
 * </pre>
 * Field types: {@code text}, {@code long_text}, {@code number}, {@code email}, {@code select}, {@code checkbox},
 * {@code date}. Validation is authoritative here - the page's own checks are a convenience. Error values are
 * stable machine codes ({@code required}, {@code too_long}, {@code invalid_number}, {@code out_of_range},
 * {@code invalid_email}, {@code invalid_option}, {@code invalid_date}, {@code invalid_type}, {@code
 * invalid_characters}) the web page translates.
 */
final class WorkflowFormSchema {

    static final String HONEYPOT_FIELD = "_hp";

    static final int MAX_FIELDS = 40;
    static final int MAX_OPTIONS = 50;
    static final int MAX_OPTION_LENGTH = 100;
    static final int DEFAULT_TEXT_MAX = 200;
    static final int ABSOLUTE_TEXT_MAX = 1_000;
    static final int DEFAULT_LONG_TEXT_MAX = 5_000;
    static final int ABSOLUTE_LONG_TEXT_MAX = 10_000;
    static final int EMAIL_MAX = 254;
    static final int MAX_TITLE = 120;
    static final int MAX_DESCRIPTION = 1_000;
    static final int MAX_SUBMIT_LABEL = 40;
    static final int MAX_SUCCESS_MESSAGE = 500;
    static final int MAX_REDIRECT_URL = 2_000;

    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}$");
    private static final Set<String> TYPES = Set.of("text", "long_text", "number", "email", "select", "checkbox", "date");

    /** One field of the form. {@code maxLength} is meaningful for text types; {@code min}/{@code max} for number. */
    record Field(
        String key, String label, String type, boolean required, String placeholder, List<String> options,
        int maxLength, BigDecimal min, BigDecimal max
    ) {
    }

    record Form(
        String title, String description, String submitLabel, String successMessage, String redirectUrl,
        List<Field> fields
    ) {
    }

    /** {@code values} holds one entry per schema field (normalised); {@code errors} maps field key -> error code. */
    record Result(Map<String, Object> values, Map<String, String> errors) {
        boolean ok() {
            return errors.isEmpty();
        }
    }

    private WorkflowFormSchema() {
    }

    /** True when a Trigger node's config marks it as a hosted form. */
    static boolean isFormTrigger(Map<String, Object> triggerConfig) {
        return triggerConfig != null && "form".equalsIgnoreCase(str(triggerConfig.get("triggerType")));
    }

    /** Save-time lint: every problem with the form config is appended to {@code errors}. */
    static void validateConfig(Map<String, Object> triggerConfig, String label, List<String> errors) {
        List<String> problems = new ArrayList<>();
        parseInto(triggerConfig, problems);
        for (String problem : problems) {
            errors.add("Trigger node '" + label + "' form: " + problem);
        }
    }

    /**
     * @throws IllegalArgumentException with every problem joined, when the form config is not usable
     */
    static Form parse(Map<String, Object> triggerConfig) {
        List<String> problems = new ArrayList<>();
        Form form = parseInto(triggerConfig, problems);
        if (!problems.isEmpty() || form == null) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        return form;
    }

    @SuppressWarnings("unchecked")
    private static Form parseInto(Map<String, Object> triggerConfig, List<String> problems) {
        Object rawForm = triggerConfig == null ? null : triggerConfig.get("form");
        if (!(rawForm instanceof Map<?, ?>)) {
            problems.add("the 'form' definition is missing.");
            return null;
        }
        Map<String, Object> form = (Map<String, Object>) rawForm;
        String title = bounded(form.get("title"), MAX_TITLE, "title", problems);
        String description = bounded(form.get("description"), MAX_DESCRIPTION, "description", problems);
        String submitLabel = bounded(form.get("submitLabel"), MAX_SUBMIT_LABEL, "submitLabel", problems);
        String successMessage = bounded(form.get("successMessage"), MAX_SUCCESS_MESSAGE, "successMessage", problems);
        String redirectUrl = str(form.get("redirectUrl"));
        if (!redirectUrl.isEmpty() && !isHttpsUrl(redirectUrl)) {
            problems.add("redirectUrl must be an absolute https:// URL (up to " + MAX_REDIRECT_URL + " characters).");
            redirectUrl = "";
        }

        List<Field> fields = new ArrayList<>();
        Object rawFields = form.get("fields");
        if (!(rawFields instanceof List<?> list) || list.isEmpty()) {
            problems.add("add at least one field.");
        } else if (list.size() > MAX_FIELDS) {
            problems.add("a form can have at most " + MAX_FIELDS + " fields.");
        } else {
            Set<String> seen = new HashSet<>();
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?>)) {
                    problems.add("every field must be an object.");
                    continue;
                }
                Field field = parseField((Map<String, Object>) entry, problems);
                if (field != null && !seen.add(field.key())) {
                    problems.add("duplicate field key '" + field.key() + "'.");
                } else if (field != null) {
                    fields.add(field);
                }
            }
        }
        return new Form(title, description, submitLabel, successMessage, redirectUrl, List.copyOf(fields));
    }

    @SuppressWarnings("unchecked")
    private static Field parseField(Map<String, Object> raw, List<String> problems) {
        String key = str(raw.get("key"));
        if (!KEY.matcher(key).matches()) {
            problems.add("field key '" + key + "' must start with a letter and use only letters, digits and _ (max 64).");
            return null;
        }
        String type = str(raw.get("type")).toLowerCase(Locale.ROOT);
        if (type.equals("textarea")) {
            type = "long_text";
        }
        if (!TYPES.contains(type)) {
            problems.add("field '" + key + "' has an unknown type '" + type + "'.");
            return null;
        }
        String label = str(raw.get("label"));
        if (label.length() > 200) {
            problems.add("field '" + key + "' has a label over 200 characters.");
            label = label.substring(0, 200);
        }
        String placeholder = str(raw.get("placeholder"));
        if (placeholder.length() > 200) {
            placeholder = placeholder.substring(0, 200);
        }
        boolean required = Boolean.TRUE.equals(raw.get("required")) || "true".equalsIgnoreCase(str(raw.get("required")));

        List<String> options = List.of();
        if (type.equals("select")) {
            Object rawOptions = raw.get("options");
            List<String> parsed = new ArrayList<>();
            if (rawOptions instanceof List<?> list) {
                for (Object o : list) {
                    String option = str(o);
                    if (option.isEmpty() || option.length() > MAX_OPTION_LENGTH || parsed.contains(option)) {
                        problems.add("field '" + key + "' has an empty, duplicate or over-long option.");
                    } else {
                        parsed.add(option);
                    }
                }
            }
            if (parsed.isEmpty() || parsed.size() > MAX_OPTIONS) {
                problems.add("select field '" + key + "' needs between 1 and " + MAX_OPTIONS + " options.");
            }
            options = List.copyOf(parsed);
        }

        int maxLength = switch (type) {
            case "long_text" -> clampLength(raw.get("maxLength"), DEFAULT_LONG_TEXT_MAX, ABSOLUTE_LONG_TEXT_MAX);
            case "email" -> EMAIL_MAX;
            default -> clampLength(raw.get("maxLength"), DEFAULT_TEXT_MAX, ABSOLUTE_TEXT_MAX);
        };

        BigDecimal min = null;
        BigDecimal max = null;
        if (type.equals("number")) {
            min = decimalOrNull(raw.get("min"));
            max = decimalOrNull(raw.get("max"));
            if (min != null && max != null && min.compareTo(max) > 0) {
                problems.add("number field '" + key + "' has min greater than max.");
            }
        }
        return new Field(key, label.isEmpty() ? key : label, type, required, placeholder, options, maxLength, min, max);
    }

    /**
     * Validates {@code submitted} (the form's field values, keyed by field key) against {@code form}. Unknown keys
     * are dropped; every schema field appears in {@code values} - absent optional text as {@code ""}, other absent
     * optional values as null, an unchecked checkbox as false - so downstream nodes see a stable shape.
     */
    static Result validate(Form form, Map<String, Object> submitted) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Field field : form.fields()) {
            Object raw = submitted == null ? null : submitted.get(field.key());
            String error = validateField(field, raw, values);
            if (error != null) {
                errors.put(field.key(), error);
            }
        }
        return new Result(values, errors);
    }

    /** Puts the normalised value in {@code values}; returns an error code or null. */
    private static String validateField(Field field, Object raw, Map<String, Object> values) {
        switch (field.type()) {
            case "checkbox" -> {
                Boolean checked = toBoolean(raw);
                if (checked == null) {
                    values.put(field.key(), Boolean.FALSE);
                    return "invalid_type";
                }
                values.put(field.key(), checked);
                return field.required() && !checked ? "required" : null;
            }
            case "number" -> {
                if (isBlank(raw)) {
                    values.put(field.key(), null);
                    return field.required() ? "required" : null;
                }
                BigDecimal number = toDecimal(raw);
                if (number == null) {
                    values.put(field.key(), null);
                    return "invalid_number";
                }
                values.put(field.key(), numberValue(number));
                boolean low = field.min() != null && number.compareTo(field.min()) < 0;
                boolean high = field.max() != null && number.compareTo(field.max()) > 0;
                return low || high ? "out_of_range" : null;
            }
            default -> {
                return validateText(field, raw, values);
            }
        }
    }

    private static String validateText(Field field, Object raw, Map<String, Object> values) {
        boolean textual = field.type().equals("text") || field.type().equals("long_text");
        Object emptyValue = textual ? "" : null;
        if (raw != null && !(raw instanceof String)) {
            values.put(field.key(), emptyValue);
            return "invalid_type";
        }
        String text = raw == null ? "" : ((String) raw).replace("\r\n", "\n").strip();
        if (text.isEmpty()) {
            values.put(field.key(), emptyValue);
            return field.required() ? "required" : null;
        }
        if (hasControlCharacters(text, field.type().equals("long_text"))) {
            values.put(field.key(), emptyValue);
            return "invalid_characters";
        }
        if (text.length() > field.maxLength()) {
            values.put(field.key(), emptyValue);
            return "too_long";
        }
        switch (field.type()) {
            case "email" -> {
                if (!EMAIL.matcher(text).matches()) {
                    values.put(field.key(), null);
                    return "invalid_email";
                }
            }
            case "select" -> {
                if (!field.options().contains(text)) {
                    values.put(field.key(), null);
                    return "invalid_option";
                }
            }
            case "date" -> {
                try {
                    LocalDate.parse(text);
                } catch (DateTimeParseException e) {
                    values.put(field.key(), null);
                    return "invalid_date";
                }
            }
            default -> {
                // text / long_text: length and character checks above are all there is
            }
        }
        values.put(field.key(), text);
        return null;
    }

    private static boolean hasControlCharacters(String text, boolean allowLayout) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean layout = c == '\n' || c == '\t';
            if ((c < 0x20 && !(allowLayout && layout)) || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static Boolean toBoolean(Object raw) {
        if (raw == null) {
            return Boolean.FALSE;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty() || v.equals("false") || v.equals("off") || v.equals("0")) {
                return Boolean.FALSE;
            }
            if (v.equals("true") || v.equals("on") || v.equals("1")) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    private static BigDecimal toDecimal(Object raw) {
        try {
            if (raw instanceof Double d && (d.isNaN() || d.isInfinite())) {
                return null;
            }
            if (raw instanceof Float f && (f.isNaN() || f.isInfinite())) {
                return null;
            }
            if (raw instanceof BigDecimal bd) {
                return bd;
            }
            if (raw instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (raw instanceof String s && s.trim().length() <= 40) {
                return new BigDecimal(s.trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static Number numberValue(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.precision() <= 18) {
            return stripped.longValue();
        }
        return value.doubleValue();
    }

    private static boolean isBlank(Object raw) {
        return raw == null || (raw instanceof String s && s.isBlank());
    }

    private static BigDecimal decimalOrNull(Object raw) {
        return isBlank(raw) ? null : toDecimal(raw);
    }

    private static int clampLength(Object raw, int fallback, int ceiling) {
        BigDecimal parsed = decimalOrNull(raw);
        int requested = parsed == null ? fallback : parsed.intValue();
        return Math.max(1, Math.min(requested, ceiling));
    }

    private static boolean isHttpsUrl(String value) {
        if (value.length() > MAX_REDIRECT_URL) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank()
                && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String bounded(Object raw, int max, String name, List<String> problems) {
        String value = str(raw);
        if (value.length() > max) {
            problems.add(name + " is longer than " + max + " characters.");
            return value.substring(0, max);
        }
        return value;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
