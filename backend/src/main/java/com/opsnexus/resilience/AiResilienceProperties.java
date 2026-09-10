package com.opsnexus.resilience;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ops.ai-resilience")
public class AiResilienceProperties {
    private ProviderSettings chat = new ProviderSettings();
    private ProviderSettings embedding = new ProviderSettings();

    public ProviderSettings getChat() { return chat; }
    public void setChat(ProviderSettings chat) { this.chat = chat; }
    public ProviderSettings getEmbedding() { return embedding; }
    public void setEmbedding(ProviderSettings embedding) { this.embedding = embedding; }

    public ProviderSettings forProvider(AiProvider provider) {
        return provider == AiProvider.DEEPSEEK_CHAT ? chat : embedding;
    }

    public static class ProviderSettings {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration responseTimeout = Duration.ofSeconds(30);
        private int retryMaxAttempts = 2;
        private Duration retryWaitDuration = Duration.ofMillis(200);
        private int circuitSlidingWindowSize = 10;
        private int circuitMinimumCalls = 5;
        private float circuitFailureRateThreshold = 50;
        private Duration circuitOpenWaitDuration = Duration.ofSeconds(10);
        private int circuitHalfOpenPermittedCalls = 2;

        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; }
        public int getRetryMaxAttempts() { return retryMaxAttempts; }
        public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }
        public Duration getRetryWaitDuration() { return retryWaitDuration; }
        public void setRetryWaitDuration(Duration retryWaitDuration) { this.retryWaitDuration = retryWaitDuration; }
        public int getCircuitSlidingWindowSize() { return circuitSlidingWindowSize; }
        public void setCircuitSlidingWindowSize(int circuitSlidingWindowSize) { this.circuitSlidingWindowSize = circuitSlidingWindowSize; }
        public int getCircuitMinimumCalls() { return circuitMinimumCalls; }
        public void setCircuitMinimumCalls(int circuitMinimumCalls) { this.circuitMinimumCalls = circuitMinimumCalls; }
        public float getCircuitFailureRateThreshold() { return circuitFailureRateThreshold; }
        public void setCircuitFailureRateThreshold(float circuitFailureRateThreshold) { this.circuitFailureRateThreshold = circuitFailureRateThreshold; }
        public Duration getCircuitOpenWaitDuration() { return circuitOpenWaitDuration; }
        public void setCircuitOpenWaitDuration(Duration circuitOpenWaitDuration) { this.circuitOpenWaitDuration = circuitOpenWaitDuration; }
        public int getCircuitHalfOpenPermittedCalls() { return circuitHalfOpenPermittedCalls; }
        public void setCircuitHalfOpenPermittedCalls(int circuitHalfOpenPermittedCalls) { this.circuitHalfOpenPermittedCalls = circuitHalfOpenPermittedCalls; }
    }
}
