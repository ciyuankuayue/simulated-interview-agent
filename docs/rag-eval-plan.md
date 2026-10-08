# RAG 检索评测方案（基于现有项目真实代码）

> 目标：用项目里真实存在的代码跑一次可复现的检索评测，输出 recall@5 / MRR@10 / P95，
> 用来**替换简历里编的数字**（92.5% 等）变成真值。并验证「混合检索是否真比纯向量好」。

## 现状盘点（已读源码确认）
- `LuceneVectorStore.search(userId, vec, topK)` 目前**只做向量 KNN**（`KnnVectorQuery`），无 BM25 路径。
- `content` 字段写入时是 `StoredField`（只存不索引），所以现在**根本无法全文检索**。做 BM25 必须改代码。
- 默认切块 `TextChunker(500, 50)`（maxChars=500, overlap=50，见 `RagService`）。
- 已支持内存目录 `ByteBuffersDirectory`（`new LuceneVectorStore(directory, dim)`），可离线评测、**不污染生产 `data/rag-index`**。
- 真实向量：`DashscopeEmbedder`（qwen3.7-text-embedding-flash，dim=1024），key 只从 `DASHSCOPE_API_KEY` 读。

## 需新增代码（最小改动，不碰现有 `search`）
1. `LuceneVectorStore`
   - `toDocument` 增加 `TextField(FIELD_CONTENT_TEXT, content)`，用 **CJKAnalyzer** 做中文分词（StandardAnalyzer 对中文是单字切分，BM25 会很差）。
   - 新增 `searchKeyword(userId, query, topK)`：`BooleanQuery`（TermQuery 命中 + userId filter）走 BM25。
   - 新增 `searchHybrid(userId, vec, query, topK, k=60)`：向量 + BM25 各取 topN，**RRF 融合** `score = Σ 1/(k+rank)`。
2. `pom.xml`：加 `lucene-analyzers-common`（CJKAnalyzer）。
3. 全部是**新方法**，不动 `search`，不影响线上。

## 评测数据集（决定数字可信度的关键）
- **语料**：一份技术文档集（Java/八股笔记导出 md，~50–80 篇；或复用 l3 的 79 篇）。
- **查询集**：30 条「面试提问式」查询（如「ReentrantLock 和 synchronized 区别」「MySQL 间隙锁什么时候触发」）。
- **标准答案 ground truth**：每条查询标注 1–3 个相关 chunk 的 id。先由「查询关键词命中哪个 chunk」自动草拟标签，用户过一眼确认。
- **切分**：语料经 `TextChunker(500,50)` 切块后建索引。

## 运行方式
- 新建 `scripts/eval/RunEval.java`（或 `src/test/.../EvalRetrievalTest`）：
  - 内存目录建 `LuceneVectorStore(dim=1024)`；
  - `DashscopeEmbedder` 真实向量化语料 + 查询（需 `DASHSCOPE_API_KEY`）；
  - 对每条查询跑 A/B/C 三策略，记录命中与耗时；
  - 排除预热后算 recall@5 / MRR@10 / P95，打印对比表。
- 真实 embedding 烧少量平台 token（30 查询 + 几十篇语料，量很小）。
- 延迟分别测：embedding 网络耗时、Lucene search-only 耗时、端到端 retrieve() 耗时。

## 预期产出
- 一张三策略对比表（**真实数字**）。
- 若纯向量 recall@5 已 >85%，混合提升有限 → 简历只写向量+隔离；若混合明显更高 → 写混合并附对比表。
- 简历数字**全部换成这次跑出来的真值**。

## 待确认（见对话提问）
1. 评测范围：仅测现状（纯向量，不动代码） / 现状 + 混合对比（加 BM25/RRF 代码）
2. Embedding：真实 Dashscope（需 key 可用，recall 才有意义） / 先 FakeEmbedder 跑通流程（recall 无意义）
3. 语料：用指定资料（给路径） / 我直接造一份示例技术语料 / 复用 l3 的 79 篇

## 实测结果（2026-10-07）
- 已实现：LuceneVectorStore 新增 `contentText`(CJK 分词) + `searchKeyword`(BM25) + `searchHybrid`(RRF 融合)；
  `pom.xml` 的 lucene-analyzers-common 依赖**多余**（CJKAnalyzer/PerFieldAnalyzerWrapper 已在 lucene-analysis-common 内），已回退。
- 评测类：`src/test/java/com/bupt/mockinterview/rag/RunEval.java`，`.\mvnw.cmd test -Dtest=RunEval` 跑通（需 DASHSCOPE_API_KEY）。
- **规模化实测（150 篇文档 / 30 条查询，真实 Dashscope embedding，2026-10-07）**：
  - 入库 150 篇 19.3s；向量化单次 **P95 123ms**；纯 Lucene 检索 **<2ms**。
  - 纯向量 **recall@5 96.7% / MRR@10 0.824**；纯 BM25 **86.7% / 0.700**；混合 RRF **96.7% / 0.825**。
- **关键结论（重要，颠覆原编数据）**：中文 BM25（CJK bigram + 短文档）噪声大，**纯 BM25 单独召回弱于纯向量**（86.7% vs 96.7%）；混合 RRF 与纯向量持平（召回不降、MRR 微升 0.824→0.825）。**因此简历不能写"混合比纯向量高 21pt"**——真实结论是「向量为主通道、BM25 作互补保底，召回维持 96.7%」。这反而是更硬的工程质量信号：用数据推翻了自己先入为主的假设。
- 简历 `18` 文件已更新为实测数字（纯向量 96.7% / 混合持平 / 纯 BM25 86.7% / 向量化 P95 123ms）。
- 日志：`eval_run.log`(UTF-16) / `eval_run_utf8.log`(已转 UTF-8)，未提交 git。
