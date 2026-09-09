# OPS-V2-006 MySQL / Testcontainers verification record

- Time: 2026-09-09 11:37 Asia/Shanghai
- Git baseline: local `master` `581ee39`; working branch `feat/mysql-testcontainers`
- Target: MySQL 8.4 through Testcontainers; application schema: `schema-mysql.sql`
- Command: `mvn -f backend/pom.xml -Pmysql-integration verify`
- Current host result: **NOT RUN**. Testcontainers discovered `MySqlPersistenceIT` (3 tests) but Docker CLI/daemon is not available, so all 3 were explicitly skipped. No MySQL quality or compatibility result is claimed from this host.
- Default isolation check: `mvn -f backend/pom.xml test` passed, 31 tests, Docker-free.
- Frontend check: `npm.cmd run build` passed.

When Docker is available, rerun the command above. The test bootstraps a fresh `mysql:8.4` database with `schema-mysql.sql`, uses test-only container credentials, and verifies document publication rollback/current-version behavior, conversation feedback/gap lifecycle/audits, and database-enforced read-only analytics access.