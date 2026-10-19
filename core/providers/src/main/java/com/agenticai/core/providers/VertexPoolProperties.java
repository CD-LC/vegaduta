package com.agenticai.core.providers;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vertex.pool.*} - the platform's own Vertex AI credentials (up to 3
 * GCP projects), used for the platform-subsidized paths that must not depend
 * on any tenant's BYOK key: the anonymous {@code /try} sandbox and FREE-plan
 * creative generation. Mirrors {@link PlatformHostedLlmProvider}'s
 * priority-ordered-failover shape, but deliberately config-driven rather than
 * a DB-admin-editable table - Vertex credentials are service-account JSON key
 * files on disk, not a single pasted string, so there is no natural "paste a
 * key in an admin form" UI for them the way the LLM pool has. A slot is
 * usable once {@link Slot#getProjectId()} is non-blank AND either {@link
 * Slot#getServiceAccountKeyPath()} is non-blank OR {@link
 * Slot#isUseApplicationDefaultCredentials()} is set; blank/false (the
 * default) means the slot is simply not configured yet - identical "blank
 * means unconfigured" posture the rest of this codebase's optional
 * integrations take (Google Workspace OAuth, X OAuth, etc).
 *
 * <p><b>Application Default Credentials (ADC)</b> - {@code
 * useApplicationDefaultCredentials: true} is the no-key-file alternative to
 * {@code serviceAccountKeyPath}: {@link VertexAccessTokenProvider} then
 * calls {@code GoogleCredentials.getApplicationDefault()} instead of reading
 * a mounted JSON file, which picks up credentials from whatever the
 * environment already provides - a workload-identity-bound / attached
 * service account when this runs on GCP (Cloud Run, GKE), or the local
 * `gcloud auth application-default login` file otherwise (set up once via
 * {@code bash <(curl -sSL https://storage.googleapis.com/cloud-samples-data/adc/setup_adc.sh)}).
 * No key material ever needs to be baked into an image, mounted as a
 * secret, or committed anywhere for a slot configured this way.
 */
@ConfigurationProperties(prefix = "vertex.pool")
public class VertexPoolProperties {

    /** Master kill switch - independent of individual slot config, for pausing all Vertex traffic in one flip. */
    private boolean enabled = false;

    private List<Slot> slots = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Slot> getSlots() {
        return slots;
    }

    public void setSlots(List<Slot> slots) {
        this.slots = slots;
    }

    public static class Slot {

        private String projectId = "";

        private String location = "us-central1";

        /** Path to a GCP service-account JSON key file, mounted into the container (e.g. a Docker secret) - never the key content itself in config. Ignored when {@link #useApplicationDefaultCredentials} is set. */
        private String serviceAccountKeyPath = "";

        /** See this class's javadoc "Application Default Credentials" section - true means skip {@link #serviceAccountKeyPath} entirely and authenticate via whatever ADC finds in this environment. */
        private boolean useApplicationDefaultCredentials = false;

        /** Lower tries first, same convention as {@link PlatformHostedLlmProvider#getPriority()}. */
        private int priority = 0;

        /** Which capabilities this slot's project has been provisioned/allowlisted for - Veo access in particular is not automatic on every GCP project. */
        private boolean supportsText = true;
        private boolean supportsImage = true;
        private boolean supportsVideo = false;

        public boolean isUsable() {
            if (projectId == null || projectId.isBlank()) {
                return false;
            }
            return useApplicationDefaultCredentials || (serviceAccountKeyPath != null && !serviceAccountKeyPath.isBlank());
        }

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getServiceAccountKeyPath() {
            return serviceAccountKeyPath;
        }

        public void setServiceAccountKeyPath(String serviceAccountKeyPath) {
            this.serviceAccountKeyPath = serviceAccountKeyPath;
        }

        public boolean isUseApplicationDefaultCredentials() {
            return useApplicationDefaultCredentials;
        }

        public void setUseApplicationDefaultCredentials(boolean useApplicationDefaultCredentials) {
            this.useApplicationDefaultCredentials = useApplicationDefaultCredentials;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public boolean isSupportsText() {
            return supportsText;
        }

        public void setSupportsText(boolean supportsText) {
            this.supportsText = supportsText;
        }

        public boolean isSupportsImage() {
            return supportsImage;
        }

        public void setSupportsImage(boolean supportsImage) {
            this.supportsImage = supportsImage;
        }

        public boolean isSupportsVideo() {
            return supportsVideo;
        }

        public void setSupportsVideo(boolean supportsVideo) {
            this.supportsVideo = supportsVideo;
        }
    }
}
