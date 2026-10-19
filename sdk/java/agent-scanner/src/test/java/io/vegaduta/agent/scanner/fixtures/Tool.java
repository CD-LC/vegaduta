package io.vegaduta.agent.scanner.fixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stands in for a framework tool annotation (Spring AI, LangChain4j). The scanner matches it by
 * simple name only.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
  String name() default "";

  String description() default "";
}
