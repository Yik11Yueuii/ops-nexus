package com.opsnexus.resilience;

/** Fixed provider identities prevent resilience instance names from becoming scattered strings. */
public enum AiProvider {
    DEEPSEEK_CHAT("deepseekChat"),
    DASHSCOPE_EMBEDDING("dashscopeEmbedding");

    private final String instanceName;

    AiProvider(String instanceName) {
        this.instanceName = instanceName;
    }

    public String instanceName() {
        return instanceName;
    }
}
