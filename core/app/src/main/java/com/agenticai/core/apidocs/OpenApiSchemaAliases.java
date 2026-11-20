package com.agenticai.core.apidocs;

import java.util.Map;
import org.springframework.core.ResolvableType;

/**
 * Schemas published under the name of the controller-nested record they were moved out of. The
 * generator names a nested record {@code Enclosing_Record}, and the SDK generator turns schema
 * names into public type names, so moving a view record from a controller into its service would
 * rename the SDK type even though the JSON is unchanged. Keyed by binary class name.
 */
final class OpenApiSchemaAliases {

    private static final Map<String, String> PUBLISHED =
            Map.ofEntries(
                    Map.entry(
                            "com.agenticai.core.agents.demo.studio.DemoStudioWorkflowService$RunView",
                            "DemoStudioController_RunView"),
                    Map.entry(
                            "com.agenticai.core.agents.demo.studio.DemoStudioWorkflowService$WorkflowView",
                            "DemoStudioController_WorkflowView"),
                    Map.entry(
                            "com.agenticai.core.platform.moderation.ReviewItemResponse",
                            "ModerationController_ReviewItemResponse"),
                    Map.entry(
                            "com.agenticai.core.platform.moderation.BlocklistEntryResponse",
                            "ModerationController_BlocklistEntryResponse"),
                    Map.entry(
                            "com.agenticai.core.platform.PlatformTenantDetailService$Quota",
                            "PlatformTenantDetailController_Quota"),
                    Map.entry(
                            "com.agenticai.core.platform.PlatformTenantDetailService$TenantDetail",
                            "PlatformTenantDetailController_TenantDetail"),
                    Map.entry(
                            "com.agenticai.core.platform.PlatformTenantDetailService$TenantRef",
                            "PlatformTenantDetailController_TenantRef"),
                    Map.entry(
                            "com.agenticai.core.admin.sso.TenantScimTokenService$ScimTokenView",
                            "TenantSsoController_ScimTokenView"),
                    Map.entry(
                            "com.agenticai.core.eventbus.webhook.WebhookDeliveryQueryService$DeliveryView",
                            "WebhookSubscriptionController_DeliveryView"));

    private OpenApiSchemaAliases() {}

    /** The published base name for {@code raw}, or null when it keeps the generated name. */
    static String publishedName(Class<?> raw) {
        return PUBLISHED.get(raw.getName());
    }

    /**
     * The component name for a type: its alias, else {@code Enclosing_Name} plus generic arguments.
     */
    static String baseName(Class<?> raw, ResolvableType type) {
        String published = publishedName(raw);
        if (published != null) {
            return published;
        }
        String simple = raw.getSimpleName();
        for (Class<?> e = raw.getEnclosingClass(); e != null; e = e.getEnclosingClass()) {
            simple = e.getSimpleName() + "_" + simple;
        }
        StringBuilder sb = new StringBuilder(simple);
        for (ResolvableType g : type.getGenerics()) {
            Class<?> a = g.resolve();
            sb.append('_').append(a == null || a == Object.class ? "Any" : a.getSimpleName());
        }
        return sb.toString().replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }

    static String lastPackageSegment(Class<?> raw) {
        String pkg = raw.getPackageName();
        String seg = pkg.substring(pkg.lastIndexOf('.') + 1);
        if (seg.isEmpty()) {
            return "Default";
        }
        return Character.toUpperCase(seg.charAt(0)) + seg.substring(1);
    }
}
