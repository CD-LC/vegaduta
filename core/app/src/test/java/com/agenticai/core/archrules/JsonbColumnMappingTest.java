package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * JAVA-011: an entity field declared {@code columnDefinition = "jsonb"} must be
 * bound as JSON ({@code @JdbcTypeCode(SqlTypes.JSON)}) or through an
 * {@code AttributeConverter}. A plain String/Map field binds as VARCHAR, and
 * Postgres then rejects every non-null write ("column is of type jsonb but
 * expression is of type character varying") - which {@code ddl-auto: validate}
 * does not catch. Skill.pending_files shipped exactly that way.
 */
class JsonbColumnMappingTest {

    @Test
    void everyJsonbColumnIsBoundAsJson() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<String> unbound = new ArrayList<>();
        int entities = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.agenticai")) {
            entities++;
            for (Class<?> type = Class.forName(candidate.getBeanClassName()); type != null && type != Object.class;
                 type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    Column column = field.getAnnotation(Column.class);
                    if (column == null || !column.columnDefinition().toLowerCase(Locale.ROOT).startsWith("jsonb")) {
                        continue;
                    }
                    JdbcTypeCode code = field.getAnnotation(JdbcTypeCode.class);
                    boolean json = code != null && code.value() == SqlTypes.JSON;
                    boolean converted = field.isAnnotationPresent(jakarta.persistence.Convert.class);
                    if (!json && !converted) {
                        unbound.add(type.getName() + "#" + field.getName());
                    }
                }
            }
        }
        assertThat(entities).as("entity scan found nothing - classpath problem?").isGreaterThan(50);
        assertThat(unbound).as("jsonb columns without @JdbcTypeCode(SqlTypes.JSON) or @Convert").isEmpty();
    }
}
