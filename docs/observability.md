# Observability baseline

OpsNexus exposes Spring Boot Actuator health, info, and metrics. Only `/actuator/health/**` and `/actuator/info` are public; metrics and all other Actuator endpoints require the existing `ADMIN` role. No environment, configuration, heap, thread, mapping, document, prompt, SQL, credential, or tool-result content is exported.

`X-Trace-Id` is reused only when it matches the bounded identifier format; otherwise a UUID is generated. The value is returned in the response, stored in MDC for safe request logs, and written to AI, SQL, semantic-comparison, and diagnosis-tool audit metadata. It is deliberately never a metric tag.

| Metric | Fixed tags |
| --- | --- |
| `opsnexus.ai.calls`, `opsnexus.ai.latency` | provider, operation, outcome, failure_category |
| `opsnexus.rag.requests`, `opsnexus.rag.no_hits`, `opsnexus.rag.*` | outcome |
| `opsnexus.tool.calls`, `opsnexus.tool.latency` | tool, event, outcome, failure_category |
| `opsnexus.sql.queries`, `opsnexus.sql.latency` | event, outcome, failure_category |
| `opsnexus.semantic.comparisons`, `opsnexus.semantic.latency` | outcome, failure_category |
| `opsnexus.ingestion.events`, `opsnexus.ingestion.duration` | event, outcome |
| `opsnexus.ingestion.queue.depth` | none |

Tags use fixed enums and the diagnosis tool allowlist; unknown tool names collapse to `UNKNOWN`. User IDs, document IDs, query text, request IDs, trace IDs, raw SQL, prompts, and error messages are prohibited from metric labels. Resilience4j retry and circuit-breaker counters are supplied by its official Micrometer binder.

Health is passive: database reachability is required, Redis failure is reported as `DEGRADED` because the application falls back to database state, and AI health is based on recent outcomes/circuit state without probing providers. Liveness remains independent of external AI availability. The administrator UI’s System Status page shows a small aggregate runtime summary from `/api/admin/observability/summary`.
