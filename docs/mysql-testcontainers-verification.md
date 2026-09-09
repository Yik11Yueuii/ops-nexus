# OPS-V2-006 MySQL / Testcontainers verification record

- Time: 2026-09-09 16:26 Asia/Shanghai
- Git baseline: `7ef56dc` on `feat/mysql-testcontainers`, plus this compatibility update under verification
- Host: Docker Desktop / Docker Engine 29.7.2, WSL2 Linux backend
- Testcontainers: 2.0.5 (BOM-managed); docker-java 3.7.1
- Containers: `testcontainers/ryuk:0.14.0` and `mysql:8.4` both started by Testcontainers
- Application schema: `schema-mysql.sql`, initialized by Spring against a fresh MySQL 8.4 container
- Command: `mvn -f backend/pom.xml -Pmysql-integration verify`
- MySQL integration result: **PASS** — Tests run: 3, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS.

The verified scenarios are:

1. User, knowledge base, document version publication, transaction rollback, and current-version behavior.
2. Conversation, feedback, knowledge-gap lifecycle, revalidation/reopen, and business/SQL audit persistence.
3. Database-enforced read-only analytics access: allowed SELECTs and rejected writes/unauthorized-table reads.

The `mysql-integration` profile remains explicit. Default `mvn test` does not run these Docker-backed `*IT` tests and therefore remains Docker-free.