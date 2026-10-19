package com.agenticai.core.imagegen;

import com.agenticai.core.providers.ProviderName;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class VideoGenerationClientRegistry {

    private final Map<ProviderName, VideoGenerationClient> clientsByProvider;

    public VideoGenerationClientRegistry(List<VideoGenerationClient> clients) {
        this.clientsByProvider = clients.stream()
            .collect(Collectors.toMap(VideoGenerationClient::supportedProvider, Function.identity()));
    }

    public VideoGenerationClient get(ProviderName providerName) {
        VideoGenerationClient client = clientsByProvider.get(providerName);
        if (client == null) {
            throw new ImageGenNotConfiguredException("No video-generation client is registered for " + providerName);
        }
        return client;
    }
}
