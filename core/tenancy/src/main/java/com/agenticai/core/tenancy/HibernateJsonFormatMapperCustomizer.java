package com.agenticai.core.tenancy;

import com.agenticai.core.json.JsonMappers;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Pins Hibernate 7's {@code @JdbcTypeCode(SqlTypes.JSON)} mapper to a Jackson 3 mapper built from
 * {@link JsonMappers} (Jackson 2 wire defaults), so JSONB text written by core does not silently
 * change with the Jackson 2 to 3 jump, and so a mixed Jackson 2 / 3 classpath (Temporal,
 * Playwright) cannot make Hibernate pick a different mapper (ADR-0012 P2).
 */
@Component
public class HibernateJsonFormatMapperCustomizer implements HibernatePropertiesCustomizer {

    /** The mapper Hibernate uses for JSON columns; shared with the JSONB characterisation test. */
    public static FormatMapper formatMapper() {
        return new Jackson3JsonFormatMapper(JsonMappers.jsonMapper());
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.JSON_FORMAT_MAPPER, formatMapper());
    }
}
