# OPS-V2-004 Prompt Injection / Untrusted Context Defense

## Threat model and trust boundary

Only fixed application system policy is trusted instruction. User questions, conversation history, retrieved chunks, document titles and metadata, diagnosis inputs, read-only tool results, and model output are untrusted data. Untrusted data may contain role claims, commands, SQL, URLs, or requests to expose internal material; none can alter backend authorization, tool permission, SQL validation, or output policy.

## Prompt structure

`DeepSeekChat` sends a single trusted `system` message, retains historical roles as `user`/`assistant`, and wraps every non-system value in an escaped `<untrusted_context>` envelope. RAG evidence and diagnosis snapshots are separate user-role context messages rather than text appended to system policy. Escaping prevents document text from closing its own envelope.

## Enforcement and secrets

Explicit requests to reveal the current system/developer prompt, hidden configuration, credentials, API keys, authorization values, or database passwords receive `PROMPT_DISCLOSURE_DENIED`. Conceptual questions such as “what is a system prompt?” remain allowed. Secret assignments and bearer values are redacted before entering model messages, persisted conversation values, diagnosis snapshots, or SQL audit records. AI provider telemetry contains metadata only.

Authorization remains backend-enforced: knowledge-base/version filtering occurs before evidence reaches a prompt; ADMIN access is enforced by Spring Security; Text-to-SQL still requires its existing AST whitelist, row limit, read-only account, timeout, and audit. Prompt wording is not a SQL security boundary.

## Regression baseline and limitations

The fixed offline regression set has 24 cases across `DIRECT_USER_INJECTION`, `INDIRECT_DOCUMENT_INJECTION`, `PROMPT_LEAKAGE`, `PRIVILEGE_ESCALATION`, `SECRET_EXTRACTION`, and `SQL_BYPASS`. It verifies deterministic containment, redaction, direct-disclosure refusal, and preserved SQL rejection without calling a real model.

Prompt Injection cannot be completely eliminated: a model can still misunderstand adversarial content. This defense lowers instruction-following risk and limits impact through role/context separation, backend enforcement, secret exclusion, bounded history, citations, and regression tests. It does not introduce a second LLM judge, embedding classifier, or third-party security vendor.
