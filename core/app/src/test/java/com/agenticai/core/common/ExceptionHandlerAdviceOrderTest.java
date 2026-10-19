package com.agenticai.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pins the two rules the split of {@code GlobalExceptionHandler} into an ordered advice chain
 * depends on (see {@link FallbackExceptionHandler}'s javadoc). Spring stops at the FIRST advice (by
 * {@code @Order}) with ANY handler assignable from the thrown type, so: (a) the generic {@code
 * Exception} catch-all must be in the last advice only, and (b) no advice may handle a superclass
 * of a type a LATER advice handles. A violation of (b) silently answers the subclass with the
 * superclass handler - the kind of regression no per-handler unit test would catch.
 */
class ExceptionHandlerAdviceOrderTest {

    private static final List<Class<?>> CHAIN =
            List.of(
                    RequestBindingExceptionHandler.class,
                    ResourceStateExceptionHandler.class,
                    ClientRequestExceptionHandler.class,
                    ServiceCredentialExceptionHandler.class,
                    ExecutionPolicyExceptionHandler.class,
                    UpstreamDependencyExceptionHandler.class,
                    FallbackExceptionHandler.class);

    private static List<Class<?>> handledTypes(Class<?> advice) {
        List<Class<?>> types = new ArrayList<>();
        for (Method m : advice.getDeclaredMethods()) {
            ExceptionHandler h = m.getAnnotation(ExceptionHandler.class);
            if (h == null) {
                continue;
            }
            if (h.value().length == 0) {
                types.addAll(List.of(m.getParameterTypes()));
            } else {
                types.addAll(List.of(h.value()));
            }
        }
        return types;
    }

    @Test
    void everyAdviceIsAnnotatedAndStrictlyOrdered() {
        int previous = Integer.MIN_VALUE;
        for (Class<?> advice : CHAIN) {
            assertTrue(
                    advice.isAnnotationPresent(RestControllerAdvice.class),
                    advice.getSimpleName() + " must be a @RestControllerAdvice");
            Integer order = OrderUtils.getOrder(advice);
            assertTrue(order != null, advice.getSimpleName() + " must declare an explicit @Order");
            assertTrue(
                    order > previous,
                    advice.getSimpleName() + " must be ordered after the advice before it");
            previous = order;
        }
        assertEquals(
                Ordered.LOWEST_PRECEDENCE,
                OrderUtils.getOrder(FallbackExceptionHandler.class),
                "the catch-all must run last");
    }

    @Test
    void genericCatchAllsLiveOnlyInTheLastAdvice() {
        for (Class<?> advice : CHAIN.subList(0, CHAIN.size() - 1)) {
            for (Class<?> type : handledTypes(advice)) {
                assertTrue(
                        type != Exception.class
                                && type != RuntimeException.class
                                && type != Throwable.class,
                        advice.getSimpleName() + " must not handle " + type.getSimpleName());
            }
        }
        assertTrue(
                handledTypes(FallbackExceptionHandler.class).contains(Exception.class),
                "FallbackExceptionHandler must keep the Exception.class catch-all");
    }

    @Test
    void noAdviceHandlesASuperclassOfATypeALaterAdviceHandles() {
        Map<Class<?>, List<Class<?>>> handled = new LinkedHashMap<>();
        for (Class<?> advice : CHAIN) {
            handled.put(advice, handledTypes(advice));
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < CHAIN.size(); i++) {
            for (Class<?> earlier : handled.get(CHAIN.get(i))) {
                for (int j = i + 1; j < CHAIN.size(); j++) {
                    for (Class<?> later : handled.get(CHAIN.get(j))) {
                        if (earlier != later && earlier.isAssignableFrom(later)) {
                            violations.add(
                                    CHAIN.get(i).getSimpleName()
                                            + " handles "
                                            + earlier.getSimpleName()
                                            + ", which shadows "
                                            + later.getSimpleName()
                                            + " in "
                                            + CHAIN.get(j).getSimpleName());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void everyHandledTypeIsHandledByExactlyOneAdvice() {
        Map<Class<?>, Integer> seen = new LinkedHashMap<>();
        for (Class<?> advice : CHAIN) {
            for (Class<?> type : handledTypes(advice)) {
                seen.merge(type, 1, Integer::sum);
            }
        }
        List<String> duplicates = new ArrayList<>();
        seen.forEach(
                (type, count) -> {
                    if (count > 1) {
                        duplicates.add(type.getSimpleName() + " x" + count);
                    }
                });
        assertEquals(List.of(), duplicates);
    }
}
