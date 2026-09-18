# 会话交接（handoff.md）

> 每轮续对话先读本文件。记录：当前进度 / 已锁决策 / 任务拆解 / 卡点 / 下一步。
> 配套：`./domain.md`（领域模型）、`./api.md`（契约）、`../AGENTS.md`（规则）。

## 1. 当前进度（截至 2026-09-17）

| 阶段 | 产物 | 状态 | 备注 |
|---|---|---|---|
| 地基 | 仓库克隆 + `.gitignore` + README 骨架 | ✅ 已推远端 master | commit 由你审 |
| 地基 | DeepSeek function calling 验通 | ✅ | 实测模型回 `deepseek-flash`，`tool_calls` 结构化打通 |
| S1 | MVP 边界确认 | ✅ | 按 `19-项目A-技术栈与形态.md` §6：2 角色 + Critic、单轮≤2 层追问、md 输入、向量先内存余弦、最简静态页 |
| S2 | `./docs/domain.md` | ✅ 已落盘 | 10 实体 + 判分结构 + 间隔重复方案 A |
| S3 | 技术选型 | ✅ | 与规划文档一致，无变更 |
| S4 | `./docs/api.md` | ✅ 已落盘 | 契约优先，待实现遵守 |
| S5 | `./docs/handoff.md`（本文件） | 🔵 当前 | 任务拆解 |
| S6 | 代码实现 | 🔵 进行中 | T1 工程骨架已建且本机编译验通（`/api/health` → UP）；T2–T10 待做 |

**未 commit 文件（截至 2026-09-17，遵守 NEVER 自动 commit，你审完再提）**：
- 文档：`AGENTS.md`、`docs/domain.md`、`docs/api.md`、`docs/handoff.md`（含本次 H2 默认 / MySQL profile 修正）
- 工程：`pom.xml`（JPA/MySQL/Redis/H2/OkHttp/Lombok 依赖）、`mvnw`+`mvnw.cmd`+`.mvn/wrapper/`、`docker-compose.yml`（MySQL 8 + Redis 7 一键起）
- 配置：`src/main/resources/application.yml`（**H2 本地默认**）、`src/main/resources/application-mysql.yml`（新增，真 MySQL，需 `mysql` profile）
- 代码：`src/main/java/com/bupt/mockinterview/`（web/HealthController、web/DepCheckController、domain/** 10 实体 + SessionStatus 枚举 + 10 Repository）
- ⚠️ 不提交：`application-h2.yml` 已删（不进库）；`scripts/deepseek-fc-demo/`（仅验证用）。

## 2. 已锁定的关键决策

1. **模型命名与模式**（**2026-09-18 核实修正**，覆盖 2026-09-17 版）：
   - **真实模型名已核实**（官方 api-docs.deepseek.com 定价页）：推荐 **`deepseek-flash`**（= DeepSeek-V4.1-Flash）；旧名 **`deepseek-v4-flash` 已下线**，仍可调用但会被**静默映射到 V4.1-Flash** → 一律用 `deepseek-flash`。`deepseek-v4-pro` 仍为旗舰推理档（2026-09-14 后继续提供）。早前实测回执 `model=deepseek-flash` 是对的，原「待核实」风险解除。
   - 追问：`deepseek-flash` + `thinking.type=disabled`（非思考，快）。
   - 判分 / Critic：`deepseek-flash` + `thinking.type=enabled`（思考模式**默认即开**）；质量不够 → 升 `deepseek-v4-pro`（同 provider，只换 model 字符串，零新集成）。
   - 思考强度：`reasoning_effort: low|high|max`（默认 high）。
   - ⚠️ **实现坑（T6 function calling 必踩）**：请求一旦携带 `tools`，后续每轮**必须完整回传上一轮 `reasoning_content`**，否则 API 返 400。思考模式下不支持 `temperature`/`presence_penalty`/`frequency_penalty`；`top_p` 下限 0.95。
   - 不引入新 provider。AGENTS.md Architecture 写的 `deepseek-flash` **本来就是正确的**，无需改（之前误判成待改项）。
2. **max_tokens 放宽**：追问/判分/Critic 不再卡 800，按 provider 默认或放宽到 ≥4096；仍**校验输出非空**（思考型 reasoning 与正文共用额度，超了正文可能变空串）。
3. **project_doc 是外部上传的弹药库**，独立于简历内嵌项目介绍（双源检索）；缺失则质疑官降级为仅基于简历。建议必传。
4. **角色支持部分启用**：`interview_session.roles` 为数组，可只含 `["技术深挖官"]`；Critic 始终在。
5. **实时只追问/一句纠正，不展开讲解**；讲解/纠正/知识补充/示范回答全归 Critic 报告（domain.md §4.1）。
6. **「精简」= 表达精炼、抓核心，关键内容不省**（per_turn_review / overall_notes / reminders 全在）。
7. **间隔重复方案 A 固定阶梯**：`review_stage∈{0,1,2,3}`，间隔 `[1,3,7,15]` 天（domain.md §5）。
8. **start 同步返回首题 + SSE 推后续**（避免 SSE 先连后等首题的竞态）。
9. **SSE 事件协议**：`question/score/followup/await_answer/report/done/error`，前端收 `await_answer` 才调 answer，循环到 `done`（api.md §4）。
10. **L3 对比实验纳入 MVP 关键路径**（不后置，D2 做）：RAG 先手写余弦相似度检索，再换向量库，当场记录对比数字（召回率/耗时/代码量）。这组数字是项目最硬「评估闭环」面试证据，首版 demo 就要能讲。2026-09-18 向量库侧定 **Apache Lucene HNSW**：`org.apache.lucene:lucene-core:9.12.3`（Maven Central，纯 Java、零原生库、零 JitPack；不引 Redis vector / pgvector 以省基建）。与 FAISS 同属 ANN 算法族（HNSW vs IVF/PQ），是 OpenSearch / Elasticsearch 用的同一套向量检索，Java 项目里更地道。⚠️ 失败教训：FAISS 的 Java 绑定在本环境**装不上**——bytedeco **没有** faiss 预设（`org.bytedeco:faiss-platform` 是错的）；`com.github.karellen:faiss-java`、`com.github.jelmerk:faiss4j` 均不在 Maven Central（repo1 实测 404），只能走 JitPack 且实测 **401 Unauthorized**。
11. **RabbitMQ 砍出 MVP，仅作可选 stretch**：单人面试练习工具同步调用足够，MQ 的异步/削峰/重试属过度设计。D3 不强制引入；消息队列相关从已有八股知识答即可。
12. **前端用 Thymeleaf 服务端渲染 + SSE**：不引入 npm / 任何前端构建链；复用已有 `SseEmitter` + `EventSource` 经验，专注 AI 后端。
13. **数据源：H2 本地默认，MySQL 为显式 profile**（2026-09-17 修正，覆盖原「base=MySQL 默认」）：
    - `application.yml` 默认即 **H2 内存库**（`jdbc:h2:mem:mock_interview;MODE=MySQL`，H2Dialect，`ddl-auto:create-drop`），**零参数 `mvnw clean spring-boot:run` 即可起**，免 Docker 验 T1–T3 编译 + 10 实体建表。
    - 真 MySQL 8 抽到 `application-mysql.yml`，需显式开 profile：`-Dspring-boot.run.profiles=mysql` 或 `SPRING_PROFILES_ACTIVE=mysql`（URL 已用 `characterEncoding=UTF-8`，修掉 `utf8mb4` 的 Java 字符集崩溃）。原 `application-h2.yml` 已删。
    - ⚠️ **最终生产 / 真库验收必须走 `mysql` profile + 真实 Redis**（Docker `docker compose up -d` 起 MySQL 8 + Redis 7）。H2 仅本地验证用，重启即清空，**不能**上生产。Redis 同理：本地可用 H2 跑通但生产 Redis 必须起。

## 3. 任务拆解（第一版可跑闭环）

> 顺序即实现顺序。每完成一项在「状态」打 ✅，并记耗时/卡点到本文件。

- [x] **T1 初始化 Spring Boot 工程**（骨架已建；2026-09-17 本机编译 + 启动验通 `/api/health` → UP）
  - 已建：`pom.xml`（Spring Boot 3.3.5 / Java 17 / starter-web + validation；JPA/MySQL/Redis 在 T2 解注释）、`mvnw`+`mvnw.cmd`+`.mvn/wrapper/`（官方格式，首次运行自下载 Maven 3.9.9）、分层包 `web/agent/rag/llm/memory/domain/`、`MockInterviewApplication`、`web/HealthController`（`GET /api/health`）、`application.yml`（占位，T2 补 DB/Redis）。
  - 基础包：`com.bupt.mockinterview`（如需改名在 T1 阶段就改）。
  - 验收：你本机跑 `.\mvnw.cmd -DskipTests package` + `.\mvnw.cmd spring-boot:run` → 访问 http://localhost:8080/api/health 返回 `{"status":"UP"}`。沙盒外网限流，无法在此编译验证。
  - 2026-09-17 修正：原 mvnw 依赖 maven-wrapper.jar（本机下载失败留坏 jar）+ 中文 `@rem` 在 GBK cmd 下乱码当命令执行。已改为**纯 ASCII** 且**直接下载 Maven 3.9.9 发行包**解压调用（存 `.mvn/wrapper/dists/`，已 gitignore；不再用 maven-wrapper.jar）。
- [x] **T2 配置与外部依赖接入**（2026-09-17 配置落盘；本机验连待你跑）
  - `pom.xml`：解注释 `spring-boot-starter-data-jpa` + `mysql-connector-j`(runtime) + `spring-boot-starter-data-redis`。**RabbitMQ 不引入**（决策 #11）。LLM 调用走 JDK 内置 HttpClient，不引 okhttp。
  - `application.yml`（**本地默认 = H2 内存库**）：`jdbc:h2:mem:mock_interview;MODE=MySQL`，H2Dialect，`ddl-auto:create-drop`；Redis 配置（`${REDIS_HOST:localhost}:${REDIS_PORT:6379}`）仍在此，down 不影响启动、用到才连。
  - `application-mysql.yml`（新增，真 MySQL 8，需 `mysql` profile 才生效）：`jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/mock_interview?...createDatabaseIfNotExist=true&characterEncoding=UTF-8`，账号/端口可环境变量覆盖，默认 root/root；JPA `ddl-auto: update`（MVP 自动建表；上线前改 none + 迁移脚本）。
    - LLM 密钥：`deepseek.api-key: ${DEEPSEEK_API_KEY:}`、`dashscope.api-key: ${DASHSCOPE_API_KEY:}` —— **绝不写死**；空默认，T2 不调 LLM 故启动不报错，T5/T4 调之前必须 set。
    - `deepseek.model-flash/model-reasoner`（均为 `deepseek-flash`，2026-09-18 已核实；新增 `model-pro: deepseek-v4-pro` 作升级档）、`timeout-ms`、`max-tokens: 8192`（决策 #2 不卡 800）；`dashscope.embed-model: qwen3.7-text-embedding-flash`。（原占位 `deepseek-v4-flash` 已下线，本次修正）
  - `docker-compose.yml`（新增，跟 AGENTS.md「redis docker 起」一致，并补 MySQL）：`docker compose up -d` 一键起 MySQL 8 + Redis 7，含 healthcheck。
  - `web/DepCheckController`：`GET /api/health/deps` 探活 MySQL/Redis（T2 验收辅助，不建 Entity）。
  - **验收（你本机，本地 H2 零依赖先行）**：
    1. `.\mvnw.cmd clean spring-boot:run`（默认 H2，无需 Docker）→ `GET /api/health/deps` 返回 `{"database":{"ok":true,...},"redis":{"ok":true/false,...}}`（`database`=H2 内存库恒连；`redis` 取决于本机是否起了 Redis）。
    2. **真库验收（生产口径，必须做）**：先 `docker compose up -d` 起 MySQL 8 + Redis 7，再 `.\mvnw.cmd clean spring-boot:run -Dspring-boot.run.profiles=mysql` → `/api/health/deps` 的 `database` 与 `redis` 均为 `ok:true`。
- [x] **T3 domain 持久化层**（实体 + Repository 编译 ✅；2026-09-17 本机 H2 默认启动 + `/api/health/deps` 返 `database.ok:true` → 10 实体表已由 Hibernate `create-drop` 自动建出）
  - 10 实体（domain.md §2）建表/Entity + Repository。
  - 验收（H2）：启动即建表、`/deps` 探活 database 连通 ✅。
  - 验收（真 MySQL）：待 Docker 起 MySQL + `mysql` profile 复验建表（`ddl-auto:update`）；接口写查属 T7 范畴。
- [x] **T4 rag 模块（D2，含 L3 手写余弦 vs 向量库对比，MVP 关键路径，决策 #10）** ✅ 2026-09-18 完成
  - 第一版：解析（md/文本）→ 分块 → 通义 `qwen3.7-text-embedding-flash` 向量化 → **内存手写余弦**检索 → 排序返回 top-k。
  - **对比实验**：同一查询集，先跑手写余弦、再换 **Lucene HNSW 向量库**，当场记录 3 组数字：**召回率 / 单查询耗时 / 代码量**；由 `L3ComparisonRealEmbeddingTest` 自动写出 `docs/l3-comparison.md`，首版 demo 即可讲。
  - 验收 ✅：15 条真实查询全部命中 ground truth（recall@3 = 100%，两侧一致）；对比数字已写入 `docs/l3-comparison.md`。
  - 2026-09-17 落盘子模块（手写余弦路径）：`rag/` 包 `Embedder` 接口 + `CosineSimilarity`（手写余弦）+ `TextChunker`（按段落切分、长段硬切、重叠 50）+ `InMemoryVectorStore` + `RagService`；`DashscopeEmbedder`（OkHttp 调通义 `/embeddings` compatible-mode 接口，需 `DASHSCOPE_API_KEY`）+ `FakeEmbedder`（离线单测用，词频哈希向量）。`RagServiceTest` 用 FakeEmbedder 验「查询命中最相似块」。`DashscopeVerifyTest` 真实联调 2026-09-18 本机验通：dim=1024、两查询 top-1 命中正确块 A(synchronized)/B(InnoDB回表)、score 0.65/0.67；修复点——compatible-mode 须用 OpenAI 格式 `input` 直为字符串（非原生 `{"texts":[...]}`），否则 HTTP 400。
  - L3 向量库侧 2026-09-18 已定并落码，两个待确认项都已拍板：(1) 语料＝**用户真实学习资料**——桌面 `Java后端AI面试知识框架.docx` 经 `scripts/convert_docx_to_md.py` 转成 `src/test/resources/corpus/java-backend-knowledge.md`（117 行，按知识点逐行切块），查询集与 ground truth 均据此生成；(2) 向量库＝**Lucene HNSW**——`LuceneHnswRetriever`（`ByteBuffersDirectory` + `KnnVectorField` + `KnnVectorQuery`，入库前 L2 归一化，Lucene 默认 EUCLIDEAN 在归一化向量上排序等价余弦）。15 条查询集 + ground truth 在 `L3ComparisonRealEmbeddingTest`，set `DASHSCOPE_API_KEY` 后 `mvnw test -Dtest=L3ComparisonRealEmbeddingTest` 即跑并写出 `docs/l3-comparison.md`。**预期结论**：小语料下 HNSW 与手写余弦 recall 打平（都接近 100%），差异主要在代码量/依赖重量 → 论证「MVP 先手写余弦、规模上来再换真向量索引」。注意：Lucene 返回的 score 是其内部打分（约 1/(1+dist)），与手写侧余弦值不可直接比较，对比只取排序与 recall@k。
  - **L3 实测结果（2026-09-18 本机跑出，见 `docs/l3-comparison.md`）**：

    | 方案 | 召回率@3 | 单查询耗时(ms) | 代码量(行) |
    |---|---|---|---|
    | HandcraftedRetriever（手写余弦） | 100.0% | 159.069 | 30 |
    | LuceneHnswRetriever（HNSW） | 100.0% | 143.638 | 100 |

    语料 79 块 / 15 查询 / topK=3。**结论（面试可讲的三点）**：
    1. **两侧 recall 打平 100%**：79 条规模下 HNSW 的近似检索零精度损失 → 手写余弦「够用、没漏检」，MVP 不必为了召回率上向量库。
    2. **耗时差异不可信，且原因本身就是知识点**：单查询 ~150ms 里绝大部分是**通义 embedding 的网络往返**，检索本体（79×1024 维）是微秒级、被完全淹没；「143 vs 159」是网络抖动而非检索快慢。**真要优化应先做 embedding 批量化 / 缓存，而不是换检索算法**——这是评估闭环里最有价值的一条。
    3. **代码量 30 行 vs 100 行 + 引入第三方依赖**：这才是换向量库的真实成本 → 论证「MVP 先手写余弦，规模上万级 chunk 再换真向量索引」。
- [x] **T5 llm 层**（2026-09-18 落码+真实链路验收通过）
  - `LlmService`：封装 DeepSeek 调用 + 超时重试 + 失败降级；两个入口：追问（flash、**非思考**）/ 判分（**思考模式**、结构化 JSON 输出、校验非空）。判分 schema 对齐 domain.md §4：**4 维度 × 0-2 = 0-8（正确性/完整性/深度/实证）**。
  - 已落文件：`llm/LlmProperties.java`（配置绑定）、`llm/LlmException.java`、`llm/LlmReply.java`、`llm/ScoreResult.java`（record：score/breakdown/missedPoints/followup/followupDepth/needWeakPoint）、`llm/LlmService.java`（OkHttp + 超时重试）、`config/OkHttpConfig.java`（OkHttp 单例连接池，决策 #13）；验收 `src/test/.../llm/DeepSeekVerifyTest.java`（未 set key 自动跳过，不污染 CI）。
  - **模型名已修正**（2026-09-18 官方核实）：`application.yml` 的 `model-flash`/`model-reasoner` 由已下线的 `deepseek-v4-flash` 改为 **`deepseek-flash`**（V4.1-Flash），新增 `model-pro: deepseek-v4-pro`。
  - ⚠️ **DeepSeek V4 两个必知**：① 思考模式靠请求体 `{"thinking":{"type":"enabled|disabled"}}` 开关（**默认开启**），不再靠模型名区分；强度 `reasoning_effort: low|high|max`。② 请求一旦带 `tools`，后续每轮**必须回传上一轮 `reasoning_content`**，否则返 400（T6 function calling 必踩）。
  - **验收命令**（JAVA_HOME 指到 JDK 21，见下方环境坑）：
    ```bash
    # Git Bash（repo 根目录）
    JAVA_HOME="C:\\Users\\19284\\jdk-21" DEEPSEEK_API_KEY=sk-xxx ./mvnw.cmd test -Dtest=DeepSeekVerifyTest
    ```
  - ✅ **真实链路验收通过**（2026-09-18，`DeepSeekVerifyTest` Tests run:1 Errors:0 BUILD SUCCESS，6.3s）：
    ```
    [verify] 追问（非思考）-> 为什么功能更多？
    [verify] 判分 score = 4
    [verify] breakdown   = {正确性=2, 完整性=1, 深度=1, 实证=0}
    [verify] missed      = [二级索引叶子节点只存索引列和主键值…, InnoDB 隐藏 rowid…, 回表代价…, 覆盖索引定义…, ICP/延迟关联/MRR…]
    [verify] followup    = 索引下推（ICP）是怎么减少回表次数的？它和覆盖索引避免回表在原理上有什么区别？
    [verify] 追问层数=2，记错题本=false
    ```
    → 判分 JSON 结构化解析成功、4 维打分 + missedPoints + 追问生成 + 追问层数映射全部按设计工作。
- [x] **T6 agent 角色编排**（2026-09-18 落码；stub 离线 2/2 通过 + 真实链路验收通过）
  - 手写状态机，不引编排框架：`start → question(layer0) → await_answer`；`answer → score → [followup(layer+1) | question(下一道)] → await_answer`；`end → report → done`。事件用 `AgentEvent` 承载（对齐 api.md §4），T6 只生产事件、T7 负责推 SSE。
  - 落码文件：
    - `agent/Role.java`：技术深挖官 / 项目质疑官 / Critic 三角色 + system prompt；`parse` 从 `session.roles` JSON 恢复（Critic 不进 roles、始终在），空则回退 [技术深挖官]。
    - `agent/AgentEvent.java`：`question/score/followup/await_answer/report/done/error`。
    - `agent/InterviewOrchestrator.java`：编排主体。追问决策 = `已追问层 < followupDepth()`（8→0 层｜6-7→1 层｜≤5→2 层封顶）；主问题上限 8 道（达上限发 `done`，等用户 `end`）；角色按主问题序号轮转。
    - `agent/CriticService.java`：报告生成 + JSON 解析（`perTurnReview/collapsedPoints/expressionIssues/overallNotes/reminders/matchScore`）。**LLM 失败返回占位报告不抛**，保证 `end` 必能置 DONE。
    - `llm/LlmPort.java`：LLM 端口接口（让编排可离线单测），`LlmService implements LlmPort`。
    - `config/RagConfig.java`：把 T4 的 rag 类装进 Spring；未 set `DASHSCOPE_API_KEY` 时注入 `FakeEmbedder` 降级（本地能启动）。
    - `domain/InterviewTurn.java`：**新增 `layer` 字段**（0=主问题，1/2=追问层）——api.md 的 followup 事件要带 layer，且「何时换下一道主问题」依赖它，seq 单独表达不了层级。
  - **降级策略（LLM 失败不让整场中断）**：判分失败 → 记 `error` 事件 + 跳过追问直接下一题；追问生成失败 → 直接下一题；Critic 失败 → 占位报告。
  - ✅ 验收（离线，已过）：`InterviewOrchestratorTest` 2/2 通过——跑通「首题 → 4 分触发追问(layer1) → 7 分换下一题 → Critic 报告 + DONE」，并断言落库 3 个 turn 的 layer=[0,1,0]、`score_json` 含 `"score":4`、耗时非空；另有用例验「8 分不追问」。
  - ✅ **真实链路验收通过**（2026-09-18，`T6RealRoundTripTest` Tests run:1 Errors:0 BUILD SUCCESS，43.9s）。真实 DeepSeek 跑完一整场，事件序列完全符合设计：
    ```
    score{turnSeq=1, score=0} → followup{turnSeq=2, layer=1} → await_answer
    → score{turnSeq=2, score=1} → followup{turnSeq=3, layer=2} → await_answer
    → report{reportId=1, matchScore=15, perTurnReview[3], collapsedPoints[6], expressionIssues[5], reminders[8]}
    → done{sessionId=1}
    ```
    实测确认三件事：① **追问决策生效**——0 分/1 分连续触发追问且 layer 递增到 2 后封顶；② **Critic 报告 JSON 可被真实解析**（perTurnReview 含 explanation/correction/knowledgeSupplement/exemplarAnswer 四段，质量很高）；③ **降级未被触发**（无需降级即跑通）。
  - ⚠️ 环境变量说明：会话级 `set` 的 key 跨进程继承不到，沙盒读不到。已设到**用户级**：`[Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY","sk-xxx","User")`（注意 `[Environment]` 前缀不能漏）。沙盒代跑时用 python 读 `HKCU\Environment` 再注入。
- [ ] **T7 web 层 + SSE**
  - controller 对齐 `api.md`：资料管理 CRUD、start/answer/end/report/status。
  - `GET /events` SSE 推送 `question/score/followup/await_answer/report/done/error`。
  - 验收：按 api.md §4 事件流示例跑通（question→await_answer→…→report→done）。
- [ ] **T8 memory 层（错题本 + 间隔重复）**
  - 答崩点（score<4）入 `weak_point`；方案 A 阶梯排 `next_review_at`。
  - 验收：`GET /api/weak-point` 按 `next_review_at` 升序返回。
- [ ] **T9 前端（Thymeleaf 服务端渲染 + SSE，决策 #12）**
  - 不引入 npm / 任何前端构建链；复用已有 `SseEmitter`（后端）+ `EventSource`（前端）经验，专注 AI 后端。
  - 功能：粘贴 JD、上传简历/project_doc、开始模拟、展示流式问题/评分/纠正（EventSource 收 SSE）、结束拉报告、看错题本。
  - 验收：浏览器走完首轮闭环。
- [ ] **T10 全链路验收**
  - 起服务 → 上传资料 → 跑一次完整模拟（含 1 层追问）→ 出报告 → 错题本有记录。
  - 验收：端到端无报错，报告含 per_turn_review + reminders。

## 4. 卡点 / 待确认

- **L3 对比版**：已纳入 MVP 关键路径（决策 #10）。D2 做 RAG 时先手写余弦相似度检索、再换向量库，当场记录召回率/耗时/代码量对比数字；首版 demo 就要能讲，不后置。
- **RabbitMQ**：已砍出 MVP，仅作可选 stretch（决策 #11）。单人工具同步调用足够，MQ 的异步/削峰/重试属过度设计；D3 不再引入异步判分，判分改同步 `deepseek-flash` 思考模式。消息队列相关从已有八股知识答即可。
- **前端框架**：已定 **Thymeleaf 服务端渲染 + SSE**（决策 #12）。不引入 npm / 任何前端构建链；复用 `SseEmitter` + `EventSource`，专注 AI 后端。
- **判分模型真实型号名（已核实，2026-09-18）**：决策 #1 修正为 `deepseek-flash` 思考模式起步、不足升 `deepseek-v4-pro`。官方定价页明确：`deepseek-v4-flash` 已下线（仍可调用但静默映射到 V4.1-Flash），推荐 `deepseek-flash`。原「待核实」风险解除——早前实测回执 `deepseek-flash` 本就是正确名。
- **AGENTS.md 同步待办**：~~改为 `deepseek-v4-flash`~~ —— **已取消**。AGENTS.md 写的 `deepseek-flash` 经官方核实**本来就是正确的**，反倒是 handoff/`application.yml` 里的 `deepseek-v4-flash` 才是已下线的旧名。本次已把 `application.yml` 与本文档统一改回 `deepseek-flash`。教训：**实测回执 / 官方文档优先于凭印象写下的模型名**。本次已同步 RabbitMQ 砍出 + OkHttp 注。
- **⚠️ 构建 JDK 与 Lombok 静默失效（2026-09-18 踩坑，影响 T3–T10 全部实体）**：项目编译目标 **Java 17**（`java.version=17` → `release 17`，产物字节码 major=61 已验证）。但本机 `JAVA_HOME` 未设置，Maven 默认跑在 **JDK 24** 上，Spring Boot 3.3.5 管理的 **Lombok 1.18.34 不支持 JDK 24 → 注解处理器静默失效**，10 个 domain 实体的 getter/setter **一个都没生成**，且编译不报错（此前无人调用故未暴露，T5 调用时才炸）。
  - 修法（两道保险）：① `pom.xml` 显式覆盖 `<lombok.version>1.18.48</lombok.version>`（支持 JDK 17/21/24，IDE 用高版本 JDK 也不失效）；② 命令行构建用 **JDK 21** 跑 Maven：`JAVA_HOME="C:\Users\19284\jdk-21" ./mvnw.cmd clean test-compile`。
  - 已验证：JDK 21 下 BUILD SUCCESS（44 主源 + 6 测试源），`javap` 确认 `InterviewTurn` 生成 9 个 getter、字节码 major=61。
  - 本机 JDK 清单：`C:\Users\19284\jdk-21`（推荐）、`C:\Program Files\Common Files\jdk-24_windows-x64_bin\jdk-24.0.1`（**勿用于构建**）。本机**没有 JDK 17**，也不需要装——Java 17 是编译目标，不是工具链版本。
- **数据源形态（2026-09-17 修正）**：本地默认 **H2 内存库**（`application.yml`，零参数 `mvnw clean spring-boot:run` 即起，免 Docker 验 T1–T3 编译 + 建表）；真 MySQL 8 抽到 `application-mysql.yml`，需 `-Dspring-boot.run.profiles=mysql` 或 `SPRING_PROFILES_ACTIVE=mysql` 才生效。原 `application-h2.yml` 已删。Docker 拉镜像源超时问题仍存在、暂未修，但**不再阻塞本地验证**。
  - ⚠️ **最终生产 / 真库验收必须恢复 Docker 起 MySQL 8 + Redis 7，并走 `mysql` profile**。H2 重启即清空，仅本地验证用；Redis 生产也必须起（H2 路下若本机 Redis 未起，`redis.ok` 为 false，属预期但不代表生产可省）。

## 5. 下一步立即做

**从 T1 开始：初始化 Spring Boot 工程。**
- 用项目自带 `mvnw`（`NEVER` 用 Git Bash 跑 mvn）。
- 先建 `pom.xml` + 分层包空壳 + 一个最小 `/api/health` 端点，跑通 `.\mvnw.cmd spring-boot:run` 再逐步加 T2–T10。
- 每步「读→想→改→跑→报」，跑通再进下一项。

> 开工前提示：本机需 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY` 环境变量；Redis 需 docker 起。缺则 T2 会卡，先确认环境就绪。
