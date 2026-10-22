package com.agenticai.core.agents.core;

import com.agenticai.core.json.JsonMappers;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores a List&lt;String&gt; as a JSON array in a TEXT column. Used instead of
 * {@code @JdbcTypeCode(SqlTypes.JSON)} because Hibernate resolves the JSON JDBC type for the shared
 * {@code List<String>} java type and then fails schema validation on unrelated {@code
 * SqlTypes.ARRAY} List&lt;String&gt; columns (provider_credential.capabilities).
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise string list", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new ArrayList<>();
        try {
            return new ArrayList<>(MAPPER.readValue(dbData, TYPE));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
