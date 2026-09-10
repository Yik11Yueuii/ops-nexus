package com.opsnexus.observability;

import com.opsnexus.resilience.AiProvider;
import com.opsnexus.resilience.ExternalAiResilience;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Passive provider health: it never performs a network probe or makes readiness depend on an AI call. */
@Component("aiProvider")
public class AiProviderHealthIndicator implements HealthIndicator {
    private final ExternalAiResilience resilience;
    public AiProviderHealthIndicator(ExternalAiResilience resilience) { this.resilience = resilience; }
    @Override public Health health() {
        boolean known = false;
        for (AiProvider provider : AiProvider.values()) {
            if ("OPEN".equals(resilience.circuitState(provider))) return Health.status("DEGRADED").withDetail("reason", "CIRCUIT_OPEN").build();
            known |= resilience.hasRecentOutcome(provider);
        }
        if (!known) return Health.unknown().withDetail("mode", "PASSIVE_NO_CALLS").build();
        for (AiProvider provider : AiProvider.values()) if (!resilience.recentSuccess(provider) && resilience.hasRecentOutcome(provider))
            return Health.status("DEGRADED").withDetail("reason", "RECENT_FAILURE").build();
        return Health.up().withDetail("mode", "PASSIVE_RECENT_SUCCESS").build();
    }
}
