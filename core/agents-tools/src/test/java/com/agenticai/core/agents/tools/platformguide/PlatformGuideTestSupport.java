package com.agenticai.core.agents.tools.platformguide;

final class PlatformGuideTestSupport {

    private PlatformGuideTestSupport() {}

    static boolean bundled(String doc) {
        return PlatformGuideTestSupport.class.getClassLoader().getResource("platform-guide/docs/" + doc) != null;
    }
}
