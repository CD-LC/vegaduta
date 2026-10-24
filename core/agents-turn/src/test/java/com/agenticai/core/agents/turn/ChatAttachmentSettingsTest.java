package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.settings.ProductControlsFixture;
import com.agenticai.core.agents.settings.SettingsRig;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.storage.FileStorageService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * {@code chat.attachments.max{Image,Audio,Video,Document}Mb}: the size cap of a chat upload follows the agent's own or its
 * tenant's setting, {@code app.chat.limits.attachments.*} when there is none. Real service, real catalog, real store; the
 * collaborators past the size check are mocks (an upload under the cap fails later on them, which is what the test reads).
 */
class ChatAttachmentSettingsTest {

    private static final long MB = 1024L * 1024L;
    private final ProductControlsFixture f = new ProductControlsFixture();
    private final UUID agent = UUID.randomUUID();
    private final UUID sibling = UUID.randomUUID();

    @AfterEach
    void clear() {
        SettingsRig.clear();
    }

    private ChatAttachmentService service(boolean withCatalog) {
        ChatAttachmentService service = new ChatAttachmentService(
                mock(AgentService.class),
                mock(ProviderModelCatalogService.class),
                mock(ModelModalitiesService.class),
                mock(FileStorageService.class));
        service.useLimits(f.limits);
        if (withCatalog) {
            service.setSettingsResolver(f.rig().resolver());
        }
        return service;
    }

    private static MockMultipartFile file(String name, String type, long bytes) {
        return new MockMultipartFile("file", name, type, new byte[(int) bytes]);
    }

    /** True when the upload was stopped by the size cap (anything else is a later, unrelated failure of a mock). */
    private static boolean refusedForSize(ChatAttachmentService service, UUID agentId, MockMultipartFile file) {
        try {
            service.upload(agentId, file);
        } catch (AttachmentTooLargeException e) {
            return true;
        } catch (RuntimeException laterFailure) {
            return false;
        }
        return false;
    }

    @Test
    void withNoOverrideEveryKindKeepsItsPlatformLimit() {
        ChatAttachmentService service = service(true);
        SettingsRig.inTenant("tenant-a", () -> {
            assertThat(refusedForSize(service, agent, file("a.png", "image/png", 9 * MB))).as("image over 8 MB").isTrue();
            assertThat(refusedForSize(service, agent, file("a.png", "image/png", 7 * MB))).as("image under 8 MB").isFalse();
            assertThat(refusedForSize(service, agent, file("a.mp3", "audio/mpeg", 16 * MB))).as("audio over 15 MB").isTrue();
            assertThat(refusedForSize(service, agent, file("a.mp4", "video/mp4", 26 * MB))).as("video over 25 MB").isTrue();
            assertThat(refusedForSize(service, agent, file("a.pdf", "application/pdf", 11 * MB))).as("document over 10 MB").isTrue();
        });
    }

    @Test
    void aTenantCanRaiseOrLowerTheLimitOfOneKindWithoutTouchingTheOthers() {
        f.rig().tenant("tenant-a", "chat.attachments.maxImageMb", 12).tenant("tenant-a", "chat.attachments.maxDocumentMb", 2);
        ChatAttachmentService service = service(true);

        SettingsRig.inTenant("tenant-a", () -> {
            assertThat(refusedForSize(service, agent, file("a.png", "image/png", 11 * MB))).as("raised image limit").isFalse();
            assertThat(refusedForSize(service, agent, file("a.png", "image/png", 13 * MB))).isTrue();
            assertThat(refusedForSize(service, agent, file("a.pdf", "application/pdf", 3 * MB))).as("lowered document limit").isTrue();
            assertThat(refusedForSize(service, agent, file("a.mp3", "audio/mpeg", 14 * MB))).as("audio untouched").isFalse();
        });
        SettingsRig.inTenant("tenant-b", () ->
                assertThat(refusedForSize(service, agent, file("a.png", "image/png", 11 * MB)))
                        .as("another tenant keeps the platform limit")
                        .isTrue());
    }

    @Test
    void anAgentsOwnLimitBeatsItsTenantsAndSiblingsKeepTheTenants() {
        f.rig().tenant("tenant-a", "chat.attachments.maxVideoMb", 10).agent("tenant-a", agent, "chat.attachments.maxVideoMb", 30);
        ChatAttachmentService service = service(true);

        SettingsRig.inTenant("tenant-a", () -> {
            assertThat(refusedForSize(service, agent, file("a.mp4", "video/mp4", 28 * MB))).isFalse();
            assertThat(refusedForSize(service, sibling, file("a.mp4", "video/mp4", 11 * MB))).isTrue();
            assertThat(refusedForSize(service, sibling, file("a.mp4", "video/mp4", 9 * MB))).isFalse();
        });
    }

    @Test
    void theRefusalNamesTheLimitThatApplied() {
        f.rig().tenant("tenant-a", "chat.attachments.maxImageMb", 3);
        ChatAttachmentService service = service(true);
        SettingsRig.inTenant("tenant-a", () ->
                assertThatThrownBy(() -> service.upload(agent, file("a.png", "image/png", 4 * MB)))
                        .isInstanceOf(AttachmentTooLargeException.class)
                        .hasMessageContaining("limited to 3MB"));
    }

    @Test
    void aPlatformLimitThatIsNotWholeMegabytesIsKeptExactlyUntilSomeoneSetsTheirOwn() {
        f.limits.getAttachments().setMaxImageBytes(1_500_000);
        ChatAttachmentService service = service(true);
        SettingsRig.inTenant("tenant-a", () -> {
            assertThat(refusedForSize(service, agent, file("a.png", "image/png", 1_400_000))).isFalse();
            assertThat(refusedForSize(service, agent, file("a.png", "image/png", 1_600_000))).isTrue();
        });
    }

    @Test
    void aServiceBuiltWithoutACatalogKeepsThePropertyLimits() {
        f.rig().tenant("tenant-a", "chat.attachments.maxImageMb", 30);
        ChatAttachmentService service = service(false);
        SettingsRig.inTenant("tenant-a", () ->
                assertThat(refusedForSize(service, agent, file("a.png", "image/png", 9 * MB))).isTrue());
    }
}
