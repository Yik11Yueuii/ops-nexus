# OpsNexus 企业知识运营助手
当前阶段：功能冻结前联调加固。系统已实现登录权限、文档版本化入库、RAG 流式问答与引用、会话反馈、受控业务工具、故障辅助诊断、版本对比、知识缺口雷达、管理员安全 Text-to-SQL、AI 调用限流与审计。

## 本地启动
需要 Java 21、Maven、Node.js。当前机器 Java 21 在 E:\jdk\jdk21；终端默认 Java 17，启动脚本会为当前进程选择 Java 21。

在项目根目录执行：
```powershell
.\scripts\start-redis.ps1
.\scripts\start-backend.ps1
```

模型密钥可以在当前 PowerShell 设置，也可以复制 `scripts/local-env.example.ps1` 为 `scripts/local-env.ps1` 后填写。后者已加入 `.gitignore`，启动脚本会自动加载。
另开终端：
```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```
浏览器访问 http://127.0.0.1:5173 。前端 /api 由 Vite 代理到 8080，不需要跨域放行。

| 演示用户名 | 初始密码 | 权限 |
|---|---|---|
| admin | OpsAdmin2026! | 管理员 |
| user | OpsUser2026! | 普通用户 |

仅用于本地虚构企业演示。默认 JWT 密钥也仅用于本地开发。共享部署前通过 JWT_SECRET、DEMO_ADMIN_PASSWORD、DEMO_USER_PASSWORD、DB_PASSWORD 提供独立值。初始化仅创建缺失账号，不覆盖已有密码。当前无刷新令牌，登录有效期两小时；退出清除浏览器 sessionStorage 中的令牌。

后端运行数据默认相对于后端工作目录，使用启动脚本时位于 backend/runtime/db。可用 OPSNEXUS_DATA_DIR 指定绝对目录。运行数据和构建产物均被 Git 忽略。Redis 地址由 REDIS_HOST、REDIS_PORT 配置，未启动不影响登录、文档管理和当前问答闭环。DASHSCOPE_API_KEY 用于管理员主动触发的文档向量化，DEEPSEEK_API_KEY 用于基于检索证据生成回答；上传和解析本身不调用模型。

## 验证
```powershell
$env:JAVA_HOME='E:\jdk\jdk21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -f backend/pom.xml test
cd frontend
npm.cmd run build
```
自动化测试覆盖认证越权、文档状态与版本、向量快照、当前版本引用、故障诊断、版本对比、知识缺口、受控工具、AI 限流与 Token 估算审计，以及 Text-to-SQL 的 AST 白名单、独立只读账号和危险语句拦截。单元测试使用模拟模型，不产生云端费用；浏览器回归中的问答、诊断和数据分析会进行少量真实 DeepSeek 调用。

## 使用文档中心

以管理员登录后，首页展示“星云技术知识库”：部署手册 v1/v2、Redis 排障 SOP 和发布规范，共 3 份逻辑文档、4 个版本。首次启动自动导入，后续启动不会覆盖或重新创建被删除的样例。

1. 点击目录阅读正文，选择版本标签对照原文。
2. 使用“上传文档”创建资料，或在详情中“上传新版本”。支持 PDF、DOCX、MD/Markdown、TXT，最大 20 MB。
3. 文本文件要求 UTF-8，PDF 不支持 OCR 或加密。单文件最多 200 个 PDF 页、300 个片段，片段长 800 字符，重叠 80 字符。
4. 解析成功后状态为 PARSED（待向量化），即使未配置模型也能预览和下载草稿。
5. 设置 DASHSCOPE_API_KEY 后重启后端，再点击“执行向量化”。调用成功并保存 SimpleVectorStore 快照后变为 READY，才能发布。只有管理员的这个显式操作会把解析后的演示片段发送至百炼。
6. 发布新版本会自动归档旧版本。普通用户只能查看当前已发布且处理成功的版本；管理员可查看全部历史版本。

向量模型默认 text-embedding-v2。通过 EMBEDDING_MODEL 和 EMBEDDING_URL 调整模型与地域地址，密钥须与服务地域匹配。当前适配器使用 DashScope 原生 texts 请求与 output.embeddings 响应，接口依据见 [阿里云官方向量接口文档](https://www.alibabacloud.com/help/zh/model-studio/text-embedding-synchronous-api)。不要把密钥填写到前端或提交到 Git。

原文件位于 backend/runtime/uploads，向量快照位于 backend/runtime/vectors/store.json。缺失快照会使对应 READY 版本标记失败，禁止继续发布；损坏快照需先恢复备份再重启。首次样例导入只解析，不会自动调用付费模型。

## 使用知识助手

在侧栏进入“知识助手”，选择知识库后输入问题。后端先使用百炼向量检索，再校验命中的文档版本仍为当前、已发布且处理成功，最后只把通过校验的虚构演示片段发送给 DeepSeek。没有有效证据时系统直接拒答，不调用回答模型；有证据时通过 SSE 返回答案、可信等级和引用片段。

普通用户的业务查询只会调用 `service_info`、`latest_releases`、`recent_incidents` 三个固定只读工具，不接收 SQL。管理员“数据分析”生成的 SQL 必须通过 JSqlParser AST 审核，只能访问三张白名单演示表，并由独立只读账号在 3 秒、100 行限制内执行；所有成功与拦截均写入审计。

AI 问答、故障诊断和 Text-to-SQL 使用每用户每分钟 10 次、同时最多 1 个生成任务的基础限流。生产式演示环境优先使用 Redis 计数器和带自动过期的互斥锁；Redis 不可用时自动降级为单实例内存限流。调用日志中的 Token 为按字符估算值，字段 `token_estimated=true`，不冒充供应商精确计费数据。

浏览器回归（前后端须先启动，默认使用本机 Edge）：

```powershell
cd frontend
npx.cmd playwright test
```

测试只删除自己本次上传的临时草稿；知识助手用例会进行一次真实模型问答，可能产生少量云端调用。截图和结果保存在项目 runtime 目录。

## 可选 MySQL
MySQL 8 profile 使用 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`，分析只读账户使用 `DB_ANALYTICS_USERNAME`、`DB_ANALYTICS_PASSWORD`。生产 profile 不自动执行 DDL；空库导入 `backend/src/main/resources/schema-mysql.sql`，已有库按 [MySQL 本地运行与 Testcontainers 验证](docs/MySQL本地运行.md) 的升级步骤处理。默认 `mvn test` 仍完全离线；Docker 可用时执行 `mvn -f backend/pom.xml -Pmysql-integration verify` 运行真实 MySQL 8 Testcontainers 验证。
## 接口
- POST /api/auth/login：登录
- GET /api/auth/me：当前用户
- GET /api/health：公开应用状态
- GET /api/admin/status：管理员查看数据库、Redis 和模型密钥配置状态
- GET /api/assistant/capabilities：查询对话与向量模型是否配置
- POST /api/assistant/chat/stream：基于当前已发布证据进行 SSE 流式问答
- POST /api/assistant/business-query：普通用户受控业务查询
- POST /api/assistant/diagnose/stream：故障辅助诊断
- GET /api/assistant/conversations：当前用户会话历史
- POST /api/admin/analytics/query：管理员安全 Text-to-SQL
- GET /api/admin/analytics/audits：SQL 审计记录
- GET /api/admin/ai-calls：AI 调用与估算 Token 日志

## 文档
- docs/产品需求文档-PRD.md
- docs/开发设计说明.md
