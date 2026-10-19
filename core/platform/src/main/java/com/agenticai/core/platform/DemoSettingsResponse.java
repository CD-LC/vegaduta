package com.agenticai.core.platform;

import java.util.List;
import java.util.Map;

/**
 * {@code GET|PUT /api/platform/demo-settings} response.
 *
 * @param effective what the demo code would read right now.
 * @param defaults what it would read if the backing {@code platform_setting}
 *     row were absent (the {@code @Value} fallbacks compiled into
 *     core/tenancy/demo). See {@link DemoSettingsValues}.
 * @param settingKeys field name -&gt; the underlying {@code
 *     control_plane.platform_setting} key, so the console can show an operator
 *     exactly which row a control writes (and so the generic Settings screen
 *     and this screen are visibly the same data, not two sources of truth).
 * @param notes honest, machine-supplied caveats about controls that are stored
 *     but not (yet) enforced anywhere. Rendered verbatim by the admin UI - the
 *     alternative is a toggle that looks live and silently does nothing.
 */
public record DemoSettingsResponse(
    DemoSettingsValues effective,
    DemoSettingsValues defaults,
    Map<String, String> settingKeys,
    List<String> notes
) {
}
