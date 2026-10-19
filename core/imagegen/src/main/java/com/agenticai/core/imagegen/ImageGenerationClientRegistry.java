package com.agenticai.core.imagegen;

import com.agenticai.core.providers.ProviderName;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Mirrors {@code core.chat.ChatClientRegistry} - one bean per {@link ImageGenerationClient}, dispatched by provider. */
@Component
public class ImageGenerationClientRegistry {

    private final Map<ProviderName, ImageGenerationClient> clientsByProvider;

    public ImageGenerationClientRegistry(List<ImageGenerationClient> clients) {
        this.clientsByProvider = clients.stream()
            .collect(Collectors.toMap(ImageGenerationClient::supportedProvider, Function.identity()));
    }

    public boolean supports(ProviderName providerName) {
        return clientsByProvider.containsKey(providerName);
    }

    public ImageGenerationClient.GeneratedImage generate(ProviderName providerName, String apiKey, String prompt, String model) {
        ImageGenerationClient client = clientsByProvider.get(providerName);
        if (client == null) {
            throw new ImageGenNotConfiguredException("No image-generation client is registered for " + providerName);
        }
        return client.generate(apiKey, prompt, model);
    }
}
