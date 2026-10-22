package com.agenticai.core;

import java.util.TimeZone;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Forces a JVM default timezone the Postgres JDBC driver always recognizes, before ANY bean
 * (DataSource, Flyway, Hikari) exists.
 *
 * <p>This used to be a line inside {@code CoreApplication.main()}, then a static initializer on
 * {@code CoreApplication} itself - both turned out unreliable. {@code main()}'s body never runs
 * under {@code @SpringBootTest} (its {@code SpringBootContextLoader} calls {@code
 * SpringApplication.run( CoreApplication.class, args)} directly, skipping {@code main()} entirely).
 * The static initializer looked like a fix for that (class-load, not method-call, was the trigger)
 * but a bare class literal reference - {@code CoreApplication.class}, which is all Spring's
 * {@code @SpringBootConfiguration} auto-detection needs - does NOT trigger Java class
 * initialization (JLS 12.4.1 explicitly exempts class literals); Spring reads
 * {@code @Configuration} metadata via ASM bytecode inspection for exactly this reason (to avoid
 * prematurely initializing classes it's just scanning), so the static block never actually ran
 * before Flyway opened its connection either.
 *
 * <p>{@link EnvironmentPostProcessor} is the one hook genuinely guaranteed to run first: {@code
 * SpringFactoriesLoader} invokes every registered post-processor during {@code SpringApplication}'s
 * {@code prepareEnvironment()} step, well before {@code refreshContext()} creates any bean - and
 * every entry point (a real {@code java -jar}, {@code mvn spring-boot:run}, and
 * {@code @SpringBootTest}) goes through this exact same {@code SpringApplication.run()} sequence,
 * so there's no second code path to keep in sync. Registered via {@code META-INF/spring.factories}
 * (the classic mechanism - still current for arbitrary {@code SpringFactoriesLoader} extension
 * points in Spring Boot 3.x; only auto-configurations themselves moved to {@code
 * AutoConfiguration.imports}).
 */
public class TimeZoneEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
