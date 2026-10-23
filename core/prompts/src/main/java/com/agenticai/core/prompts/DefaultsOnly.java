package com.agenticai.core.prompts;

/** Lazily-built {@link PromptResolver#defaultsOnly()}: the classpath catalog and no store. */
final class DefaultsOnly {

    private DefaultsOnly() {}

    static final PromptResolver INSTANCE =
            new DefaultPromptResolver(
                    PromptCatalog.load(
                            DefaultsOnly.class.getClassLoader(),
                            new PromptProperties().getDefaultLocale(),
                            new PromptProperties().getDefaultMaxLength()),
                    null,
                    new PromptProperties());
}
