# RAG 评测基线

本文件说明 OPS-V2-003 的固定离线评测。评测数据位于 `backend/src/test/resources/evaluation/rag-evaluation.json`，只使用仓库已提交的星云科技演示文档；没有生产数据库迁移、接口或前端改动。

## 运行

```powershell
$env:JAVA_HOME='E:\jdk\jdk21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -f backend/pom.xml -Dtest=RagEvaluationTest,RagEvaluationMetricsTest test
```

控制台打印汇总；机器可读结果写入 `backend/target/rag-evaluation/report.json`，该目录是构建产物，未纳入 Git。

## 评测范围和可重复性

数据集有 32 个 case：12 个部署手册、9 个 Redis SOP、6 个发布规范、5 个拒答。部署手册 v1.0 作为归档干扰项存在于 fixture 中，但不会进入候选检索；当前可回答 case 只期望 v2.0。评测器用固定的中英文词项重叠 surrogate 产生稳定排序，模拟“固定语料 → 检索 → 当前发布版本过滤”的链路，避免调用 DashScope/DeepSeek，因此不产生费用也不受外部模型随机性影响。

这不是 DashScope 在线 embedding 的质量结论。真实模型、语料切分、向量快照和阈值变化都可能改变线上结果；后续比较 BM25、Hybrid 或 Rerank 时，应保留本数据集并新增对应适配器，单独报告在线模型实验。

当前演示语料只有一个知识库，无法真实评估多知识库隔离；该场景被明确列为数据集限制，而非伪造第二知识库数据。

## 指标定义

- Recall@K：可回答 case 中，前 K 个结果存在同时匹配期望文档与版本的比例。
- MRR：可回答 case 的第一个正确文档+版本结果的倒数排名的平均值；没有正确结果计 0。
- correct document rate：可回答 case 的 Top-1 文档匹配期望文档的比例。
- correct version rate：可回答 case 的 Top-1 同时匹配期望文档和版本的比例。
- refusal correctness：应拒答 case 中，当前发布语料未得到候选结果的比例。它只衡量检索/证据策略，不判定生成回答措辞。
- citation document/version correctness：当前基线中引用候选取 Top-1，分别等同于 correct document rate / correct version rate；将来接入真实回答引用时可替换为实际 citation。
- retrieval latency：只测本地固定检索器，从问题进入检索到排序结束的耗时；P50/P95 采用 nearest-rank 计算。它不包含网络 embedding 或 LLM 生成时间。

评测不宣称自然语言“回答准确率”。`expectedKeywords` 是已保留的结构化事实标注，供后续稳定的关键事实校验使用；当前离线基线不对模型生成全文作逐字断言。
