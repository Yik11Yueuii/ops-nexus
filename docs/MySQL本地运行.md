# MySQL 本地运行与 Testcontainers 验证

## 定位

默认运行和 `mvn test` 继续使用 H2，不需要 Docker。H2 只验证基础开发回归；MySQL 行为（外键、`LONGTEXT`、生成列、时间戳、布尔值、锁和账号权限）由显式 Testcontainers 测试验证，不能把 H2 结果当作 MySQL 兼容性结论。

## 本地 MySQL 8

先创建空数据库和两个最小权限账户。应用主账户需要本应用表的读写权限；数据分析账户只应对 `service_catalog`、`release_record`、`incident_record` 具有 `SELECT`。口令只通过环境变量或本机忽略的 `scripts/local-env.ps1` 提供，绝不写入仓库。

```powershell
$env:SPRING_PROFILES_ACTIVE='mysql'
$env:DB_HOST='127.0.0.1'
$env:DB_PORT='3306'
$env:DB_NAME='opsnexus'
$env:DB_USERNAME='opsnexus'
$env:DB_PASSWORD='set-locally'
$env:DB_ANALYTICS_USERNAME='opsnexus_analytics'
$env:DB_ANALYTICS_PASSWORD='set-locally'
```

以数据库管理员身份在**空库**执行 [schema-mysql.sql](../backend/src/main/resources/schema-mysql.sql)。应用的 mysql profile 不会自动执行 DDL，避免运行时改写已有生产库；Testcontainers 测试会显式启用该脚本。

Navicat 可用于创建数据库/用户、导入 schema 和检查索引/外键，但不替代下述自动验证。连接参数与账户口令仍必须来自环境变量。

## 既有数据库升级

升级前备份数据库并先在预发执行。`schema-mysql.sql` 是 fresh-init 脚本；已有库先执行 [mysql-upgrade-v2-006.sql](mysql-upgrade-v2-006.sql)，再执行 [mysql-upgrade-v2-005-ai-resilience.sql](mysql-upgrade-v2-005-ai-resilience.sql)。前者会为缺口问题建立完整 SHA-256 唯一键，并补足原先 MySQL 内联 `REFERENCES` 没有真正创建的外键；后者只新增不含请求内容或凭据的 AI Provider 调用元数据审计表。若预检发现孤儿数据或重复规范化问题，应先人工处理，不能强行关闭外键校验。

## 验证命令

```powershell
# 默认、离线 H2 回归：不启动 Docker
mvn -f backend/pom.xml test

# Docker 可用时：启动 mysql:8.4 并运行实际 MySQL 验证
mvn -f backend/pom.xml -Pmysql-integration verify
```

MySQL 集成测试覆盖用户种子、知识库/文档/版本发布事务、当前发布版本、对话与反馈幂等、缺口状态机与复发、业务/SQL 审计，以及独立分析账户的 SELECT 许可与 UPDATE、DELETE、非白名单表的数据库级拒绝。测试使用仅限容器的虚构密码，不读取任何本地密钥。
