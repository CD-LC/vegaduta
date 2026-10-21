package com.agenticai.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Channels without a webhook (Signal, the WhatsApp QR bridge) are pulled by a receiver in the API
 * shape; each message is handed out once, so a second receiver in this deployable would take a
 * share of them. The worker profile must keep every such receiver off.
 */
class WorkerProfileReceiversOffTest {

    @ParameterizedTest
    @ValueSource(strings = {"app.signal.receive.enabled", "app.whatsappqr.receive.enabled"})
    void pullReceiverIsOff(String key) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-worker.yml"));
        Properties props = yaml.getObject();
        assertEquals("false", props == null ? null : props.getProperty(key), key);
    }
}
