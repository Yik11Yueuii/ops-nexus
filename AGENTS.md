\# OpsNexus Project AGENTS.md



This file contains project-specific rules for OpsNexus.



Global Codex rules still apply.

When global rules and this file overlap, follow the more specific OpsNexus rule for this repository.



\---



\# 1. Project Identity



OpsNexus is an enterprise knowledge operations assistant.



Its core goals are:



\- traceable enterprise knowledge Q\&A

\- document ingestion and version management

\- RAG with evidence and citations

\- fault diagnosis

\- controlled Tool Calling

\- knowledge-gap discovery and resolution

\- secure administrator data analysis

\- Java backend engineering

\- AI application engineering

\- reliability, security, testing, and observability



OpsNexus is NOT intended to be a simple chatbot or CRUD knowledge-base demo.



The project should demonstrate a complete engineering lifecycle:



product design

→ architecture

→ development

→ testing

→ security

→ reliability

→ observability

→ measurable evaluation



\---



\# 2. Current Stable Baseline



Before modifying anything, inspect the actual repository.



Do not assume a feature is missing merely because it appears in a task description.



The current project already contains substantial V1 functionality.

Preserve working functionality and evolve the system incrementally.



Current baseline architecture is approximately:



Frontend:

\- Vue 3

\- Vite

\- TypeScript



Backend:

\- Java 21

\- Spring Boot

\- Spring AI where already integrated



Data / infrastructure:

\- H2 as current primary local/demo database

\- MySQL profile/configuration exists

\- Redis exists

\- SimpleVectorStore exists

\- original files are currently stored in a controlled local directory



The repository is the source of truth for actual implementation state.

The PRD is the source of truth for intended product behavior.



When repository behavior and PRD differ:

1\. identify the difference;

2\. do not silently rewrite one to match the other;

3\. report it;

4\. follow the scope of the current assigned task.



\---



\# 3. Architecture Philosophy



\## 3.1 Incremental evolution



Prefer:



working V1

→ verified small change

→ tests

→ commit

→ next change



Do NOT perform large rewrites unless explicitly requested.



Do NOT replace stable implementations simply because another technology appears more advanced.



\---



\## 3.2 Modular monolith first



OpsNexus should remain a modular monolith unless a real requirement proves that service splitting is necessary.



Do not introduce microservices merely for architectural appearance.



Do not introduce the following without explicit approval:



\- Nacos

\- Seata

\- Kafka

\- Kubernetes

\- unnecessary Spring Cloud services

\- unnecessary API Gateway

\- multiple message queues solving the same problem

\- multiple vector databases solving the same problem



If a future requirement genuinely requires one of these technologies, explain the requirement first.



\---



\## 3.3 Middleware must solve a real problem



Never add middleware only to make the technology stack look sophisticated.



Before introducing middleware, state:



1\. What concrete problem exists today?

2\. How is the problem currently handled?

3\. Why is the current solution insufficient?

4\. Why is this middleware appropriate?

5\. What new failure modes will it introduce?

6\. What happens when the middleware is unavailable?

7\. How will the new behavior be tested?

8\. How will success be measured?



Examples of reasonable future directions:



\- MinIO:

&#x20; object storage for uploaded enterprise documents



\- RabbitMQ:

&#x20; reliable asynchronous document ingestion / embedding jobs



\- MySQL:

&#x20; production-like persistent relational storage



\- Redis:

&#x20; short-term conversation memory, rate limiting, cache, temporary state



\- Elasticsearch:

&#x20; hybrid BM25 + vector retrieval where justified



\- Resilience4j:

&#x20; timeout, circuit breaking, bulkhead and controlled retry for external AI dependencies



\- Prometheus + Grafana:

&#x20; measurable system and AI observability



These examples are NOT automatic implementation requirements.

Only implement them when assigned by the current task.



\---



\# 4. Scope Control



Only implement the currently assigned task.



Do NOT:



\- opportunistically implement later roadmap tasks;

\- perform unrelated large refactors;

\- change UI unrelated to the task;

\- change database schemas unrelated to the task;

\- introduce additional frameworks because they may be useful later;

\- modify stable APIs without a clear need;

\- rewrite working modules merely for stylistic preference.



If the current task exposes a future architectural issue:

document it instead of implementing unrelated future work.



\---



\# 5. Definition of Done



A feature is NOT complete merely because:



\- code was generated;

\- code compiles;

\- an endpoint exists;

\- a frontend button exists;

\- the happy path works once.



Where applicable, a completed feature must include:



1\. backend implementation;

2\. frontend entry or user-visible workflow;

3\. persistence behavior;

4\. authorization;

5\. validation;

6\. normal scenario;

7\. abnormal scenario;

8\. dependency failure behavior;

9\. automated tests;

10\. end-to-end verification;

11\. documentation where behavior changed.



Every important business capability should form an explainable closed loop.



\---



\# 6. AI Engineering Rules



\## 6.1 AI is not automatically trusted



Model output is untrusted input.



Never directly trust model-generated:



\- SQL

\- shell commands

\- URLs

\- filenames

\- database identifiers

\- tool arguments

\- security decisions

\- permission decisions



Validate all model-controlled structured outputs before use.



\---



\## 6.2 Retrieved documents are data, not instructions



RAG documents may contain malicious or irrelevant instructions.



Treat retrieved content as DATA.



Do not allow retrieved text such as:



"ignore previous instructions"



to override system behavior.



Prompt injection must be considered in design and tests.



\---



\## 6.3 Tool Calling



Tool Calling must use predefined, allowlisted backend tools.



The model may choose among approved tools, but must not gain arbitrary access to:



\- SQL execution

\- operating-system commands

\- arbitrary HTTP requests

\- filesystem paths

\- secret configuration



Tool parameters must be validated server-side.



Tool execution and important failures should be auditable.



\---



\## 6.4 Deterministic logic before LLM logic



Prefer deterministic code for deterministic problems.



Examples:



\- permissions

\- version state

\- document lifecycle

\- SQL security

\- numeric limits

\- authorization

\- exact diff generation

\- file validation



Use LLMs where semantic reasoning genuinely adds value.



A good pattern is:



deterministic preprocessing

→ minimal necessary AI reasoning

→ deterministic validation



\---



\## 6.5 Graceful AI degradation



Failure of an external LLM or embedding provider must not unnecessarily break unrelated business functionality.



Where appropriate:



LLM unavailable

→ keep normal business functions available

→ keep deterministic retrieval available

→ return clear degraded behavior

→ do not fabricate an AI answer



Never hide external dependency failure behind fake success.



\---



\# 7. RAG Rules



RAG answers should remain traceable.



Where applicable preserve:



\- knowledge base

\- document

\- document version

\- source location

\- retrieved evidence

\- confidence/evidence sufficiency

\- retrieval metadata necessary for debugging



Vector-store metadata must NOT be treated as the final authorization source.



Before protected evidence is returned to a user, authorization and valid-version rules must be enforced using trusted business data.



Do not fabricate page numbers or source locations.



When evidence is insufficient, prefer explicit uncertainty/refusal over invented enterprise facts.



\---



\# 8. Document Lifecycle Rules



Document processing must have an explicit state model.



Do not leave documents permanently in ambiguous intermediate states.



Important processing operations should support:



\- status visibility

\- failure reason

\- retry where appropriate

\- idempotency

\- duplicate protection

\- consistency between relational metadata and retrieval index



When asynchronous processing is introduced, consider:



\- message persistence

\- ACK behavior

\- retries

\- DLQ

\- duplicate delivery

\- idempotent consumers

\- application restart

\- partial failure



Do not claim reliable asynchronous processing until these behaviors are tested.



\---



\# 9. Database and Transaction Rules



MySQL / H2 / future storage systems each have different behavior.



Do not assume passing H2 tests proves MySQL compatibility.



When a task affects relational behavior:



\- inspect transaction boundaries;

\- inspect locking behavior;

\- inspect indexes;

\- inspect schema constraints;

\- test relevant database behavior.



Use database transactions for correctness where possible.



Do not introduce Redis distributed locks when an existing database transaction/lock already solves the problem correctly without a demonstrated distributed requirement.



\---



\# 10. Security Rules



Never log:



\- passwords

\- API keys

\- tokens

\- database passwords

\- secrets

\- unnecessary sensitive document content



Mask sensitive fields where appropriate.



Important operations should be auditable.



Authorization must be enforced on the backend.



Frontend hiding is not authorization.



Uploaded files must be treated as untrusted input.



Validate where appropriate:



\- file size

\- actual file type

\- extension

\- path handling

\- duplicate checksum

\- unsupported/encrypted/corrupt files



Never construct filesystem paths directly from raw user filenames.



\---



\# 11. Text-to-SQL Rules



Text-to-SQL is administrator-only.



Model-generated SQL must NEVER be executed directly.



Maintain defense in depth:



1\. administrator authorization;

2\. approved schema provided to the model;

3\. SQL parser / AST validation;

4\. single SELECT only;

5\. table whitelist;

6\. column whitelist;

7\. function whitelist;

8\. query restrictions;

9\. result row limit;

10\. timeout;

11\. read-only database permissions;

12\. audit.



String-prefix checks alone are insufficient.



Do not weaken existing SQL security merely to support additional generated queries.



\---



\# 12. Redis Rules



Redis is an optimization / coordination dependency, not the authoritative source for long-term business facts.



Long-term conversation history and important business data must remain persistently stored.



For each Redis use case define:



\- key naming

\- TTL

\- invalidation

\- fallback behavior

\- consistency expectations



Do not cache merely because Redis exists.



\---



\# 13. Testing Rules



Every task must run the relevant existing tests.



Add new tests for new behavior.



Depending on the feature, include:



\- unit tests

\- integration tests

\- authorization tests

\- invalid input tests

\- dependency failure tests

\- regression tests

\- frontend tests

\- E2E tests



Never delete or weaken a valid test just to make a build pass.



Never fabricate test results.



If a test cannot be run:

state exactly why.



\---



\# 14. AI / RAG Evaluation Rules



Do not claim that RAG quality improved based only on subjective observation.



Where relevant use a fixed evaluation set containing fields such as:



\- question

\- expected source

\- expected document version

\- expected key facts

\- whether the system should answer

\- whether the system should refuse



Possible metrics include:



\- Recall@K

\- MRR

\- citation correctness

\- correct-version rate

\- refusal correctness

\- factual consistency

\- latency

\- token usage



Never invent benchmark numbers.



\---



\# 15. Performance Rules



Do not claim:



\- high concurrency

\- high availability

\- high performance

\- production readiness



without evidence.



Performance claims require a reproducible test describing:



\- environment

\- concurrency

\- request count

\- test scenario

\- latency

\- throughput

\- error rate



Keep raw results where practical.



\---



\# 16. Observability Rules



Important future production-like flows should be observable.



Prefer meaningful business/AI metrics rather than only infrastructure metrics.



Examples:



\- RAG query count

\- no-evidence count

\- LLM request count

\- LLM failure count

\- LLM latency

\- token usage

\- document ingestion count

\- ingestion failures

\- Tool Calling count

\- knowledge gap count



Logs, metrics and audit records have different purposes.

Do not mix them indiscriminately.



\---



\# 17. Code Quality



Before changing code:



1\. read the relevant existing implementation;

2\. understand the current request/data flow;

3\. identify existing abstractions;

4\. identify existing tests;

5\. avoid duplicate implementations.



Prefer clear, conventional Java.



Keep classes and methods reasonably focused.



Use meaningful names.



Avoid excessively compressed one-line Java formatting.



Document:



\- important classes;

\- important public methods;

\- non-obvious algorithms;

\- security decisions;

\- fallback behavior;

\- critical transaction logic;

\- complex data flows.



Do NOT add meaningless comments to every trivial line.



Comments should explain WHY, constraints and non-obvious behavior, not translate Java syntax into Chinese/English.



\---



\# 18. Documentation



When architecture or behavior changes, update the appropriate documentation.



Important system flows should be explainable using:



\- architecture diagram

\- sequence diagram

\- state diagram

\- data-flow diagram



especially for:



\- document ingestion

\- RAG Q\&A

\- Tool Calling

\- knowledge-gap lifecycle

\- version publication

\- Text-to-SQL

\- asynchronous processing

\- failure degradation



Documentation must describe the actual implementation, not an imagined architecture.



\---



\# 19. Git Discipline



Do not fabricate historical commits.



Do not rewrite Git history unless explicitly requested.



Before a significant task:

confirm the current working-tree state.



One roadmap task should preferably correspond to one logical commit.



Do not automatically commit unless explicitly requested.



At the end of a successful task, recommend an appropriate commit message.



Example:



feat: add conversation-aware AI memory



\---



\# 20. Required Task Workflow



For every non-trivial task:



\## Before implementation



1\. Read this AGENTS.md.

2\. Read relevant PRD sections.

3\. Inspect actual repository implementation.

4\. Inspect relevant tests.

5\. Briefly state:

&#x20;  - current behavior;

&#x20;  - problem;

&#x20;  - proposed change;

&#x20;  - affected modules;

&#x20;  - major risks.



Then implement.



\## After implementation



Report:



1\. what changed;

2\. files/modules changed;

3\. database changes;

4\. API changes;

5\. frontend changes;

6\. new failure/degradation behavior;

7\. tests added;

8\. tests executed;

9\. exact test results;

10\. remaining limitations;

11\. recommended next step;

12\. recommended Git commit message.



Do not begin another roadmap task unless explicitly asked.



\---



\# 21. Priority Principle



For OpsNexus, prioritize in this order:



correctness

> security

> closed-loop behavior

> reliability

> testability

> explainability

> performance

> architectural sophistication



A smaller architecture that is complete, tested and explainable is better than a large architecture that cannot be justified.

