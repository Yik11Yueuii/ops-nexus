# Fault injection and reproducible performance verification

## Purpose and safety boundary

This document records the opt-in verification added by OPS-V2-013. **This is a reproducible local engineering baseline, not a production capacity benchmark.** It is intended to make failure behaviour, bounded retries, circuit breaking, fallback and local component latency repeatable during engineering work.

Run it with:

```powershell
$env:JAVA_HOME='E:\jdk\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -f backend/pom.xml -Pfault-performance verify
```

The profile runs normal Maven tests and only the Failsafe class under `com.opsnexus.fault`. It uses Mockito fakes, H2 already owned by the regular tests, and a loopback `HttpServer` created and stopped by the test process. It does not require, start, stop, restart, flush, change, or observe a native MySQL, Redis, MinIO, Docker container, WSL service, external AI provider, secret, or live production resource. The separate `mysql-integration` profile continues to use Testcontainers as its owned MySQL resource.

## Fault model and expected behaviour

| Boundary | Injection and observation | Expected safe result |
| --- | --- | --- |
| Chat provider transient 503 | Fake provider returns 503 then success | exactly 2 provider invocations; one bounded retry; success audit and `opsnexus.ai.calls` metric |
| Persistent 503 | Four priming failures then 40 concurrent requests | circuit opens; 40 requests return `CIRCUIT_OPEN`; invocation count remains 4; no retry storm |
| Non-retryable provider responses | Fake 400, 401, 403, malformed response | exactly 1 invocation each; `BAD_REQUEST`, `AUTHENTICATION`, or `MALFORMED_RESPONSE` remains stable |
| 429 | Fake rate-limit response | bounded retry policy invokes exactly twice, never an infinite loop |
| Native SSE before first delta | Test-owned loopback endpoint returns 503 then valid SSE | connection is retried once, one recovered delta emitted |
| Native SSE after first delta | Endpoint emits one delta then malformed JSON | no reconnect/replay; one output delta only; stable `MALFORMED_RESPONSE`; current trace remains intact |
| Provider isolation | Open Chat circuit, then invoke Embedding | Chat is `OPEN`; Embedding circuit stays `CLOSED` and succeeds |
| Redis context cache | Mock Redis get and write failures | database fallback is user- and conversation-scoped; health is `DEGRADED` with `DATABASE_FALLBACK` |
| Semantic comparison | Existing `SemanticVersionComparisonRegressionTest` provider timeout/open/malformed cases | deterministic LCS diff remains available and semantic enhancement falls back safely |
| Tool calling | Mock callback throw, mock callback timeout, unauthorized admin tool, plus existing `ToolCallingRegressionTest` denial/input/injection/call-loop cases | allowlist/authorization/argument and loop guard retain stable error categories and audit/metric coverage |
| Text-to-SQL | AST validator attack strings plus MySQL Testcontainers read-only validation | invalid statement/table/column/function/JOIN/subquery rejected before execution; excessive `LIMIT` clamps to 100; DB permission is second line of defense |
| Ingestion embedding failure | Existing `KnowledgeIntegrationTest` mock embedding exception | version becomes `FAILED`, never `READY`; retry can later succeed; ingestion failure metric path is exercised |

The failure harness reports both request counts and fake provider invocation counts. Its persistent-503 test deliberately opens the circuit deterministically before concurrent load; that avoids an assertion depending on scheduling races while still proving that the open circuit suppresses provider work under concurrency.

### Local result from this run

`FaultPerformanceVerificationIT`: 9 tests, 0 failures, 0 errors, 0 skipped.

| Scenario | Result |
| --- | --- |
| transient 503 | 1 request, 2 provider invocations, retry=1, success |
| persistent 503 | 44 total requests, 4 provider invocations, 40 concurrent rejections, circuit `OPEN`, 10 ms measured concurrent rejection phase |
| non-retryable / rate limit | 400/401/403/malformed each 1 invocation; 429 exactly 2 |
| SSE | pre-token: 2 invocations and recovery; post-token: 1 invocation, no duplicate delta, `MALFORMED_RESPONSE` |
| Redis | source `DATABASE`, user 7/conversation 33, `DEGRADED/DATABASE_FALLBACK` |
| Tool | mock throw=`TOOL_EXECUTION_FAILED`; controlled timeout=`TOOL_TIMEOUT`; unauthorized=`TOOL_ACCESS_DENIED` |
| SQL | 7 attack shapes rejected before execution; `LIMIT 9999` normalized to `LIMIT 100` |

## Performance harness

The opt-in harness has a fixed warm-up of 20 requests, then 200 measured requests at concurrency 10. Inputs are deterministic and contain no network call or credential:

1. `rag-chat-equivalent`: fixed four-item retrieval loop plus the real `ExternalAiResilience` Chat execution boundary and a fake response.
2. `tool-diagnosis-equivalent`: fixed allowlisted service lookup result.
3. `semantic-version-compare-equivalent`: deterministic LCS workload on fixed version text.
4. `open-circuit-fault-load`: same 200-request shape after deterministic persistent-503 priming; it proves invocations stay capped by the pre-open count.

Measured values from this local run (microseconds except throughput):

| Scenario | Warm-up | Requests / concurrency | Throughput | p50 | p95 | p99 | Error rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| rag-chat-equivalent | 20 | 200 / 10 | 13,799.8 req/s | 267 | 1,766 | 4,630 | 0% |
| tool-diagnosis-equivalent | 20 | 200 / 10 | 66,733.4 req/s | 1 | 6 | 12 | 0% |
| semantic-version-compare-equivalent | 20 | 200 / 10 | 34,506.6 req/s | 42 | 141 | 384 | 0% |
| open-circuit-fault-load | 20 | 200 / 10 | 14,092.4 req/s | 262 | 1,706 | 2,735 | 0% |

All latency values use monotonic `System.nanoTime`. Throughput and percentiles are intentionally reported rather than asserted as hard pass/fail thresholds: workstation load, JVM warm-up and scheduler behaviour make absolute numbers non-portable. Correctness assertions cover measured request count, zero errors for normal scenarios, bounded error handling for the fault scenario, and no post-open provider invocations.

## Observability and regression matrix

The fault paths use the existing bounded metrics and audit implementations: AI calls expose provider/operation/outcome/failure category, Resilience4j binds retry/circuit metrics, tool and SQL paths use bounded event/failure tags, semantic comparison and ingestion have success/failure counters, and request trace IDs are audit-only rather than metric labels. The `ObservabilityRegressionTest` retains its 21-case regression suite; semantic, tool, prompt-injection and ingestion checks remain in their existing regression/integration suites.

| Verification | Command or suite | Required result |
| --- | --- | --- |
| Default backend regression | `mvn -f backend/pom.xml test` | at least 79 tests, all pass |
| MySQL permissions/persistence | `mvn -f backend/pom.xml -Pmysql-integration verify` | 3/3, no failures/errors/skips; Testcontainers-owned MySQL |
| Fault/performance baseline | `mvn -f backend/pom.xml -Pfault-performance verify` | 9/9 fault tests plus default regression |
| Observability | `ObservabilityRegressionTest` | 21/21 |
| Semantic comparison | `SemanticVersionComparisonRegressionTest` | 20/20 |
| Tool calling | `ToolCallingRegressionTest` | 20/20 |
| Prompt injection | `PromptInjectionRegressionTest` | 24/24 |
| Frontend | production build | succeeds |

## Limitations and follow-up interpretation

- The harness is component-level. It intentionally does not claim end-to-end HTTP throughput, database capacity, remote model latency, Redis capacity, or production sizing.
- The SSE test validates provider-boundary retry/no-replay and error category. Controller-level SSE event serialization remains covered by its normal web flow, not by a live remote stream.
- Redis fallback health advertises `DEGRADED` and `DATABASE_FALLBACK`; there is not yet a dedicated conversation-context fallback metric. Do not infer cache-fallback rate from health alone.
- Tool timeout and throw handling use controlled mocks in the local fault profile. The timeout is the production two-second bound, so this one test intentionally takes that bounded interval; it does not use wall-clock sleeps or a real tool dependency.
- The SQL AST validator is the primary application boundary. MySQL read-only privileges in the Testcontainers profile are a second line of defense, not a substitute for validation.
- Results should be compared only on a comparable machine/JDK and treated as trend data after repeated runs. A materially slower result is an investigation signal, not automatically a release gate.
