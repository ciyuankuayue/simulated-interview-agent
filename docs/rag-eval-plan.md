# RAG 评测历史与复跑入口

2026-10-07评测已完成；2026-10-21压缩旧“需新增代码/待确认”方案，不再作为当前实现待办。历史L3 79篇/15查询/top3是另一口径，本页150篇/30查询不得与其混算。

## 已有实现与历史结果

LuceneVectorStore已有关键词BM25与RRF对照方法；CJKAnalyzer/PerFieldAnalyzerWrapper使用现有lucene-analysis-common，无需重复增加lucene-analyzers-common。生产既有向量检索不能因历史实验自动换策略。

150篇合成文档、30条查询、真实DashScope embedding；历史Recall@5/MRR@10：

| 策略 | Recall@5 | MRR@10 |
|---|---:|---:|
| 纯向量 | 96.7% | 0.824 |
| BM25 | 86.7% | 0.700 |
| RRF混合 | 96.7% | 0.825 |

历史入库约19.3s、查询embedding均值101.433ms/P95 123ms；旧脚本整数毫秒造成部分search P95显示0，不能称零成本。混合与向量召回持平，不支持“提升21个百分点”或显著提速结论。ground truth主要围绕目标文档构造，非独立专家审核的真实用户全集。

连续原始日志摘录已保留在 [historical-rag-log-excerpt.txt](measurements/2026-10-20-first-question/historical-rag-log-excerpt.txt)，口径核验见 [首题测量报告](interview-measurement-2026-10-20.md)。原eval_run.log/eval_run_utf8.log为本地历史过程文件，不假定始终存在。

## 复跑与后续

```powershell
.\mvnw.cmd test "-Dtest=RunEval"
```

实现位置 `src/test/java/com/bupt/mockinterview/rag/RunEval.java`。需DASHSCOPE_API_KEY，会调用真实embedding并发生平台用量；本轮没有重跑。评测隔离目录/语料，不能清理生产索引。复跑输出须另存，不覆盖历史证据。

MI-09后续需扩大岗位/级别、人工相关性与无依据前提案例，拆分embedding、search-only、端到端延迟，记录样本/分位数/usage；与首题约19块配置对照不可直接比较。当前计划见 [主计划](product-experience-iteration-plan.md)，不能用检索召回替代多轮面试质量证明。
