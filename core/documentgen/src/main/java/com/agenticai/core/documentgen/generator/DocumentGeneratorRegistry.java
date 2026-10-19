package com.agenticai.core.documentgen.generator;

import com.agenticai.core.documentgen.model.DocumentType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * One bean per {@link DocumentType}, dispatched by format - mirrors {@code
 * com.agenticai.core.imagegen.ImageGenerationClientRegistry}'s shape exactly.
 * Empty (every {@link #find} call returns {@link Optional#empty()}) until
 * {@code generator.impl} gains its first {@code @Component} - that is
 * expected and safe; this module ships the SPI/registry only.
 *
 * <p>Two {@link DocumentGenerator} beans registering the same {@link
 * DocumentGenerator#supportedType()} is a startup-time configuration error
 * (fails fast via {@link Collectors#toMap(Function, Function)}'s duplicate-key
 * {@link IllegalStateException}), not something this registry silently
 * resolves by "last one wins" - each format should have exactly one
 * implementation.
 */
@Component
public class DocumentGeneratorRegistry {

    private final Map<DocumentType, DocumentGenerator> generatorsByType;

    public DocumentGeneratorRegistry(List<DocumentGenerator> generators) {
        this.generatorsByType = generators.stream()
            .collect(Collectors.toMap(DocumentGenerator::supportedType, Function.identity()));
    }

    public Optional<DocumentGenerator> find(DocumentType type) {
        return Optional.ofNullable(generatorsByType.get(type));
    }
}
