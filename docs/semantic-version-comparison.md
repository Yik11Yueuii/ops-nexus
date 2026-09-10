# Semantic Version Comparison

The version comparison endpoint keeps the existing deterministic LCS diff as its source of truth.
It joins ordered document chunks, normalizes trailing whitespace and blank lines, and calculates
added, deleted and paired modified blocks. Every exposed block has a stable CHANGE-xxx identifier,
version metadata, line positions, and redacted old/new text.

AI is an advisory layer, not a replacement diff engine. It receives only bounded, changed blocks
(at most 12 blocks, 800 characters per block and 5,000 characters in total), rather than two full
documents. The provider must return JSON that references real block IDs and valid change/risk enums;
malformed output, invented IDs and invalid classifications are rejected.

Document text is untrusted data. It is redacted and sent as separately labelled untrusted contexts,
never appended to trusted system policy. Existing DeepSeek retry, circuit-breaker and provider audit
paths are reused with operation VERSION_SEMANTIC_COMPARE. A metadata-only audit stores version IDs,
outcome, failure category and latency; it never stores document bodies, prompts or credentials.

When the provider is unavailable, returns invalid JSON, or the changed data is oversized, the API
still returns the deterministic diff with respectively SEMANTIC_ANALYSIS_UNAVAILABLE,
SEMANTIC_ANALYSIS_INVALID_RESPONSE, or SEMANTIC_ANALYSIS_INPUT_TOO_LARGE. No semantic-result cache is
used: immutable results could be cached later, but this avoids persistence of AI interpretation until
invalidation/model-version policy is explicitly defined.

The comparison UI intentionally renders the AI advisory summary first and the raw, traceable change
blocks beneath it. Semantic risk is an interpretation, not a factual prediction; known limitations are
line-level alignment (not document-section semantics), bounded input without asynchronous batching,
and provider-dependent advisory quality.
