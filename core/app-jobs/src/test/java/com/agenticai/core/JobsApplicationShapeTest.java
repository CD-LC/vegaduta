package com.agenticai.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shape contract for the jobs deployable, checked without a database or any
 * infrastructure (the full context needs Postgres/Keycloak like
 * CoreApplicationTests; this guards the three annotations that make the shape
 * what it is): no controllers, scheduling ON, same scan root as the API.
 */
class JobsApplicationShapeTest {

    @Test
    void componentScanExcludesEveryWebEntryPoint() {
        ComponentScan scan = JobsApplication.class.getAnnotation(ComponentScan.class);
        assertNotNull(scan, "JobsApplication must define its own filtered scan");
        assertEquals(Set.of("com.agenticai.core"), Set.of(scan.basePackages()));
        Set<Class<?>> excluded = Set.of(scan.excludeFilters()[0].classes());
        assertEquals(Set.of(RestController.class, Controller.class, ControllerAdvice.class), excluded);
    }

    @Test
    void schedulingAndAsyncAreEnabled() {
        assertTrue(JobsApplication.class.isAnnotationPresent(EnableScheduling.class));
        assertTrue(JobsApplication.class.isAnnotationPresent(EnableAsync.class));
    }

    @Test
    void definesTheNamedApplicationTaskExecutor() {
        boolean found = Arrays.stream(JobsApplication.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("applicationTaskExecutor"));
        assertTrue(found);
    }
}
