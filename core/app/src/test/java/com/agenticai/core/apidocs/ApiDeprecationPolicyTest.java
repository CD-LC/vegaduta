package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.common.apicontract.ApiDeprecationInterceptor;
import com.agenticai.core.security.http.ApiDeprecated;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every {@link ApiDeprecated} use follows docs/api/DEPRECATION.md: ISO dates, and a sunset at least
 * {@value #MIN_NOTICE_DAYS} days after the announcement.
 */
class ApiDeprecationPolicyTest {

    static final long MIN_NOTICE_DAYS = 180;

    @Test
    void everyDeprecationHasValidDatesAndTheMinimumNotice() {
        List<String> problems = new ArrayList<>();
        for (Class<?> c : OpenApiSpecGenerator.scanControllers(OpenApiSpecGenerator.BASE_PACKAGE, new ArrayList<>())) {
            check(c.getSimpleName(), c.getAnnotation(ApiDeprecated.class), problems);
            for (Method m : c.getDeclaredMethods()) {
                check(c.getSimpleName() + "#" + m.getName(), m.getAnnotation(ApiDeprecated.class), problems);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void policyAppliesToAHandWrittenExample() {
        List<String> problems = new ArrayList<>();
        check("ok", Example.class.getAnnotation(ApiDeprecated.class), problems);
        assertThat(problems).isEmpty();
        assertThat(ApiDeprecationInterceptor.find(Example.class.getDeclaredMethods()[0], Example.class)).isNotNull();
    }

    @ApiDeprecated(since = "2026-10-16", sunset = "2027-04-16")
    static class Example {
        void handler() {}
    }

    private static void check(String where, ApiDeprecated a, List<String> problems) {
        if (a == null) {
            return;
        }
        try {
            LocalDate since = LocalDate.parse(a.since());
            if (!a.sunset().isBlank()) {
                LocalDate sunset = LocalDate.parse(a.sunset());
                if (ChronoUnit.DAYS.between(since, sunset) < MIN_NOTICE_DAYS) {
                    problems.add(where + ": sunset " + sunset + " is less than " + MIN_NOTICE_DAYS + " days after " + since);
                }
            }
        } catch (DateTimeParseException e) {
            problems.add(where + ": dates must be ISO-8601 (yyyy-MM-dd): " + e.getMessage());
        }
    }
}
