# 会话交接（handoff.md）

> 每轮续对话先读本文件。记录：当前进度 / 已锁决策 / 任务拆解 / 卡点 / 下一步。
> 配套：`./domain.md`（领域模型）、`./api.md`（契约）、`../AGENTS.md`（规则）。

## 1. 当前进度（截至 2026-09-19）

| 阶段 | 产物 | 状态 | 备注 |
|---|---|---|---|
| 地基 | 仓库克隆 + `.gitignore` + README 骨架 | ✅ 已推远端 master | commit 由你审 |
| 地基 | DeepSeek function calling 验通 | ✅ | 实测模型回 `deepseek-flash`，`tool_calls` 结构化打通 |
| S1 | MVP 边界确认 | ✅ | 按 `19-项目A-技术栈与形态.md` §6：2 角色 + Critic、单轮≤2 层追问、md 输入、向量先内存余弦、最简静态页 |
| S2 | `./docs/domain.md` | ✅ 已落盘 | 10 实体 + 判分结构 + 间隔重复方案 A |
| S3 | 技术选型 | ✅ | 与规划文档一致，无变更 |
| S4 | `./docs/api.md` | ✅ 已落盘 | 契约优先，待实现遵守 |
| S5 | `./docs/handoff.md`（本文件） | 🔵 当前 | 任务拆解 |
| S6 | 代码实现 | 🔵 收尾中 | T1–T9 ✅；T10 ✅（三项改进 + 39 用例全绿 + mysql 真机实证，浏览器 E1–E10 仍待用户）；**T11 ✅（2026-09-19）**：模型密钥页面保险箱（机器绑定/密码两模式、热生效）+ 简历版本下拉复用，单测 7 + 真机全链路 + 浏览器 7 项全过；**T12 ✅（2026-09-19）**：密码模式访问门禁（令牌刷新即失效，多设备/服务器场景）+ 自然结束自动出报告 + 密码框明文切换 + 静态资源 no-cache，**48 用例全绿 + 浏览器真实验证（3 题场自动出 76 分报告）**，未 commit |
| **R1-1** | **多用户隔离** | **✅ 后端（Trea 完成）🔵 待人终审 ｜ ⚠️ 前端未接线** | **后端 3 个 commits：e1b537f + ea9bfa0 + 1f0aea4（UserIsolationTest 全绿）。2026-09-29 复核 `mvnw test` 59 tests 全绿。⚠️ 发现缺口：前端 `app.js` 未接 JWT（无登录 UI / 不存 token / `api()` 不带 `Authorization` / SSE 只拼 `vaultToken`），浏览器当前全线 401、不可用。后端鉴权+隔离机制本身正确，但**前端 JWT 接线缺失**，R1-1.5 已立项（见 §4 卡点 + tasks.md）补前端接线。** |

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
- [x] **T7 web 层 + SSE**（2026-09-18 落码 + 验收通过）
  - 分层原则：**controller 只做协议适配，不含编排逻辑**——事件全部由 T6 的 `InterviewOrchestrator` 生产，web 层负责「受理请求 → 推事件 → 组装响应」。
  - 落码文件：
    - `web/MaterialController.java`：api.md §2 资料管理全量 CRUD（JD 单条/批量、简历 + activate、笔记、项目文档 + PUT 补现状、真实面试记录）。写入时**同步切块向量化入 RAG**（resume/note/project_doc/real_interview 为语料源；JD 是查询源不入语料）；RAG 入库失败只记日志不阻断写入。JD 上传时用 LLM（flash 非思考 + JSON 模式）抽 `keywords`/`jobType`，失败降级为空。
    - `web/InterviewController.java`：api.md §3 主流程 `start / answer / events / end / report / status`。`end` 幂等（已出报告则直接返回既有报告）；`reportView` 把 TEXT 列里的 JSON 还原成结构化对象。
    - `web/SseBroker.java`：`GET /events` 的推流中枢，解决三个现实问题——① **订阅晚于生产**：每会话保留 200 条有界历史，新订阅者先重放（否则前端永远收不到 start 的首题）；② 多端/重连：同会话多 emitter，断开自动摘除；③ 收到 `done` 主动 complete 收流。注册与发布**按会话加锁**互斥，避免重放与实时推送交错产生重复/丢事件。
    - `web/ApiExceptionHandler.java`：统一 `{code, message}`，按语义异常映射状态码——`IllegalArgumentException`→404、`IllegalStateException`→409、参数类→400、`LlmException`→504、其余→500（对齐 api.md §1）。
    - `web/WeakPointController.java`：api.md §5 错题本查询（按 `next_review_at` 升序；**写入归 T8**）。
  - ✅ 验收（`InterviewFlowTest` 3/3；全量 `mvnw test` **12/12 BUILD SUCCESS**）：断言的是契约而非「没报错」——start 同步返回首题、SSE 重放到订阅前的 question+await_answer、answer 只回 `accepted` 且低分触发追问（turnCount=2）、end 出报告且 status 转 DONE、report 可重拉、end 后新订阅者仍重放到 report+done、**已 DONE 再作答返回 409**；另有资料 CRUD 用例与 404 用例。
  - ⚠️ **两个已知边界**（MVP 范围内，非 bug）：① `answer` 同步跑完判分才返回（api.md §3 允许，结果走 SSE；异步化待规模上来再做，MQ 已按决策砍掉）；② `start` 的 `resumeId` 参数当前未使用——RAG 检索的是全局语料，未按简历版本隔离。**【用户已拍板 2026-09-18】简历版本隔离暂不做，等全链路（T8-T10）跑通后再作为迭代项考虑，不进 MVP。**
  - ⚠️ 测试坑：`MockHttpServletResponse.getContentAsString()` 默认按 **ISO-8859-1** 解码，中文会乱码；必须 `getContentAsString(StandardCharsets.UTF_8)`（真实容器无此问题）。
- [x] **T8 memory 层（错题本 + 间隔重复）**（2026-09-18 落码 + 验收通过）
  - 落码文件：
    - `memory/SpacedRepetition.java`：方案 A **固定阶梯 [1,3,7,15] 天**，stage 封顶 3。不把天数写散在各处，排期逻辑只有这一个入口（domain.md §5 明确 MVP 不做 SM-2：SM-2 要用户自评难度、冷启动参数敏感，固定阶梯的好处是用户能自己算出「什么时候复习什么」）。
    - `memory/WeakPointMemoryService.java`：唯一入口 `onTurnScored(jd, knowledgePoint, score, status, now)`，三分支——**≤3 分记/升阶**、**4-5 分不动**、**≥6 分出队（resolved）**。
    - `domain/WeakPoint.java` + `WeakPointRepository`：新增 `resolved / resolvedAt / createdAt`，新增 `findByResolvedFalse`、`findByResolvedFalseAndNextReviewAtLessThanEqual`。
    - `web/WeakPointController.java`：改为走 memory service（不再直连 repo）；新增 `GET /api/weak-point/due?now=`（今日待复习，不传 `now` 取当前时间，便于测试「假装今天是后天」）。
    - `llm/ScoreResult.java` + `LlmService`：判分 JSON 新增 **`knowledge_point`** 字段（10-20 字稳定短标签），`ScoreResult` 加第 5 个参数并保留 4 参兼容构造器（旧调用点不用改）。
    - `agent/InterviewOrchestrator.java`：判分后调 `memory.onTurnScored(...)`；**写失败只追加 `WEAK_POINT_FAILED` error 事件，不中断面试**（沿用 T6 的降级硬约束）。模型没给 `knowledge_point` 时用「题目前 30 字」兜底，防整条链路静默失效。
  - 🎯 **关键设计：知识点标签不用 missed_points 凑**。missed_points 措辞每轮都漂（"没提覆盖索引"/"覆盖索引的定义…"），拿它当 key 会把同一知识点记成好几条，间隔重复直接失去意义。改成让判分模型顺带输出稳定的短标签，并做归一化匹配（去标点/空白 → 去中文虚词「的/了/和…」→ 小写，再精确相等或一方包含另一方且较短一方 ≥4 字）。实测「MySQL 索引失效场景」与「索引失效的场景」能认成同一条。
  - ✅ 验收（`WeakPointMemoryTest` 7/7 + `WeakPointApiTest` 1/1 + 全量 `mvnw test` **20/20 BUILD SUCCESS**；本机设了 key 时两个真实链路测试也跑，没 key 则自动 skip）：断言的是规则不是「没报错」——新建 stage0/+1 天、连崩升阶 0→1→2→3 且 **间隔 1→3→7→15 天并封顶**（wrong_count 累加、不重复建条）、≥6 分出队且队列清空、出队后再崩重新入队、**4-5 分两边都不碰**、措辞漂移仍匹配、不同知识点各占一条且队列按 `next_review_at` 升序、due 只返回已到期。
  - ✅ **真实链路验证**（`DEEPSEEK_API_KEY` 注入后跑）：① 判分确实返回 `knowledge_point = "InnoDB 回表与覆盖索引"`（短标签，可作 key）；② `T6RealRoundTripTest` 跑完整场后错题本落两条：`线程池核心参数与队列配置(x1, stage0, next +1天)`、`线程池任务提交执行流程(x1, stage0, next +1天)`。
  - ⚠️ 已知边界（MVP 范围内）：① Critic 的 `collapsedPoints` **不再二次写入错题本**——实时判分已覆盖每个答崩轮次，再写会重复计数；② 知识点粒度取决于模型标签，同一领域可能被拆成相邻的两条（如上面线程池那两条），待真实数据多了再考虑聚类归一；③ 没有「手动标记已复习」接口，出队只由下一场面试的判分自动触发。
  - ⚠️ 测试隔离坑：H2 `mem` 库在**所有 @SpringBootTest 类之间共享**（同一 context/同一 URL），写错题本的测试类必须加 `@Transactional` 回滚，否则会把 `InterviewFlowTest` 的「错题本为空」断言冲掉。已给 `WeakPointMemoryTest`/`WeakPointApiTest` 加，并顺手补上 `T6RealRoundTripTest`（真实链路会真写库，之前漏了）。
- [x] **T9 前端（Thymeleaf 服务端渲染 + SSE，决策 #12）**（2026-09-18 落码）
  - 不引入 npm / 任何前端构建链。只加了 `spring-boot-starter-thymeleaf`（页面骨架），交互全在原生 JS。
  - 落码文件：
    - `web/PageController.java`：`GET /` → `templates/index.html`。**只返回骨架，不含任何业务逻辑**——所有数据走 `/api/**`（与 api.md 完全一致），前端只是契约的一个消费者，后端改协议不用改模板。
    - `resources/templates/index.html`：三栏单页——左「准备」（JD 粘贴上传 + 已有 JD 下拉、简历、项目文档、笔记、角色勾选、开始）、中「面试」（对话流 + 回答框 + 结束按钮 + 状态）、右「Critic 报告」+「错题本」。
    - `resources/static/app.js`：原生 `fetch` + `EventSource`，无框架。处理 api.md §4 全部事件：`question/followup/score/await_answer/report/done/error`。
    - `resources/static/style.css`：深色主题手写 CSS（面试练习要长时间盯屏，浅底刺眼）。
  - 🎯 **两个时序对齐点**（后端已配合，前端也要对上，否则必出 bug）：
    - **订阅晚于生产**：`start` 同步返回首题，但前端拿到 sessionId 才连 SSE。后端 `SseBroker` 保留历史并**重放**，所以首题由 SSE 的 `question` 事件渲染，`start` 响应的 `firstQuestion` **不渲染**，避免首题显示两遍。
    - **answer 只回 `{accepted:true}`**：判分结果走 SSE，所以提交后禁用输入框，等下一个 `await_answer` 再放开；`end` 的响应与 SSE 的 `report` 事件用 flag 去重，只渲染一次。
    - 另：SSE 的 `error` 事件与 EventSource 自带的 `onerror` 都会进同一个 listener，用有没有 `code` 字段区分「业务异常」与「连接断开」（否则断线会被当成业务错误刷红条）。
  - ✅ 验收：`PageRenderTest` 2/2 + 全量 `mvnw test` **BUILD SUCCESS**（22 个用例，未设 key 时 4 个真实链路用例自动 skip）（模板能渲染 + 静态资源能取到——**Thymeleaf 模板写错只有真正渲染时才炸，编译期看不出来**；静态资源 404 也不会有任何提示，所以钉死这两件事）。
  - ✅ **真实容器冒烟**：`mvnw spring-boot:run` 起服务后实测 `GET /` → 200 且页面含「AI 模拟面试 / 开始模拟面试」、`GET /app.js` → 200、`GET /style.css` → 200、`GET /api/weak-point` → `[]`。服务已停，端口 8080 已释放。
  - ⏳ **浏览器首轮闭环待用户验收**（`http://localhost:8080`）：贴 JD → 上传简历/项目文档 → 勾角色 → 开始 → 收流题目/评分/追问 → 提交回答 → 结束出报告 → 右下角错题本有记录。这步要真 LLM，需本机 `DEEPSEEK_API_KEY`。
  - 🐞 **T9 收尾修复：Critic 报告 JSON 被截断（2026-09-18，用户真机反馈「报告解析有问题，整体注意事项里是一段残缺 JSON」）**
    - **根因**：Critic 走思考模式（`thinking.type=enabled`），而 DeepSeek V4.1 Flash 的 `max_tokens` 在思考模式下限制 **reasoning+content 总生成量**。原 `max-tokens: 8192` 被 reasoning 吃完后，留给 JSON 正文的预算见底，模型把字段名写到一半（`"explanatio`）就被硬截断 → Jackson 解析失败 → `overallNotes` 被塞进那段残缺 JSON 原文。
    - **修复**：
      1. `LlmProperties` 新增 `criticMaxTokens=32768`，`CriticService` 调 `chat(..., criticMaxTokens)`（V4.1 Flash 支持到 384K 输出，32768 远在安全线内，不会 400）；判分/追问仍用全局 `max-tokens: 8192`。
      2. `LlmPort` 新增带 `maxTokens` 的 `chat` 重载，`LlmService` 实现；`StubLlmPort` 同步补实现（否则测试编译不过）。
      3. `CriticService.parse` 加**截断容错**：先补未闭合的字符串引号、再按「后开先闭」补 `]`/`}`（用栈记录打开顺序，修掉初版「先关数组后关对象」导致 `...讲解"]}}` 非法的问题），仍失败才降级为占位报告（不再回显模型原文）。
      4. `SYSTEM` prompt 加长度约束（每轮 explanation≤100 字等），从源头压低输出总长。
    - ✅ 验收：`CriticParseTest` 5/5（正常 JSON 完整解析 / 值截断补引号救回 / 缺括号补回 / 极端键名被砍或乱码降级不抛 / 修复器顺序正确），全量 `mvnw test` **BUILD SUCCESS**。真实链路（max_tokens=32768 是否被 API 接受）待用户 MySQL 生产形态验收时一并确认。
- [~] **T10 全链路验收**（环境层 ✅ 2026-09-18；三项改进 + 自动测试 + 真机实证 ✅ 2026-09-19；浏览器 E1–E10 待用户）
  - ✅ 环境层：`docker compose up -d`（MySQL 3307 + Redis 6379 双 healthy）→ `$env:SPRING_PROFILES_ACTIVE="mysql"; .\mvnw.cmd spring-boot:run`（**无需手设 JAVA_HOME/key**，mvnw 自动回落 JDK21，key 用户级继承）→ `/api/health/deps` database+redis 均 ok、容器库表建齐（新列靠 `ddl-auto:update` 自动加）、`/` 200。
  - ✅ **用户三项改进（2026-09-19，用户验收前批准实施，api.md 已同步授权改契约）**：
    1. **历史 Critic 报告可追溯回看**：`GET /api/interview/sessions`（最近 20 场，含 status/matchScore/maxQuestions/hasReport，报告 id 批量查避免 N+1）；前端右栏「3.历史面试」，有报告的场次可点开回看（回看复用 report 渲染，重置 reportRendered 强制重渲）。边界：只做最近 20 场列表+回看，不做历史表/全文检索（api.md §6 已改）。
    2. **错题本条目可点开看现场**：`weak_point` 加 `last_session_id/last_turn_seq`（纯 id 无外键，崩/升阶时写；旧 5 参 `onTurnScored` 重载委托 null，旧测试零改动）；`GET /api/weak-point/{id}`（不存在 → `IllegalArgumentException` → 404）聚合最近一次答崩现场（question/answer/layer/score + 从该场 `critic_report.per_turn_review` 按 turnSeq 匹配 explanation/correction/knowledgeSupplement/exemplarAnswer 四段；未 end 时 review=null 合法，任一环缺失降级 null）。前端 `<details>` 懒加载（`state.weakDetailLoaded` Set，`toggle` 事件 capture=true 委托在 #weak）。
    3. **问答轮数可设置**：`interview_session.max_questions`（null=默认 8）；start 请求 `maxQuestions` 1–8，**用 Bean Validation `@Min/@Max` + `@Valid` 校验 → 400**（⚠️ 本项目 `IllegalArgumentException` 被映射成 404，参数非法不能用它抛；控制器加了 `@Validated`，record 组件直接注解）；前端下拉 3 快速/5 标准/8 完整（默认）。追问层数仍只由分数驱动（8→0｜6-7→1｜≤5→2），不开放配置。达上限 done.reason=`MAX_QUESTIONS_REACHED`。
    4. **附：嵌入模型可观测**（用户追加验证真/假降级）：`/api/health/deps` 加 `embedder:{provider, vectorChunks, ping:{ok,dim,detail}}`，ping 真实调 `embedder.embed("连通性测试")`，结果缓存 30s（volatile+synchronized）；`Embedder` 接口加 `default name()`，Dashscope 返 `dashscope:qwen3.7-text-embedding-flash`。
  - ✅ 自动测试：新增 `T10FeaturesTest`（@Transactional 回滚，4 用例）——sessions 列表 end 前后 hasReport 翻转+报告可达；错题详情现场定位→end 后四段讲解补出+404；maxQuestions 0/9→400、=1 时答完首题（含一层追问）即 done 且 SSE 重放含 MAX_QUESTIONS_REACHED；deps 暴露 embedder 块且 ping.ok。全量 **31 用例（29 跑 2 skip）BUILD SUCCESS**。
  - ✅ **mysql profile 真机实证（2026-09-19）**：`/api/health/deps` → `embedder.provider=dashscope:qwen3.7-text-embedding-flash`、`ping.ok=true dim=1024`；传一条 Redisson 看门狗笔记前 vectorChunks=0、后 **=1**（同进程内真 embedding 调用入库，非 Fake 降级；内存向量重启清零属预期）；`POST /start maxQuestions=9` → **400** 中文消息；`GET /weak-point/999999` → **404**；`GET /sessions` 含昨日浏览器实测场（DONE/20 分/hasReport=true）；首页含「历史面试/主问题数/快速 3 题/完整 8 题」，app.js 含 loadHistory/懒加载。服务已停 8080 已释放。
  - ⏳ **用户浏览器 E1–E10 手工验收（下一步）**：起 mysql profile → http://localhost:8080：E1 资料入库（deps vectorChunks 增长）/ E2 首题不重复 / E3 追问 2 层封顶 / E4 高分不追问 / E5 角色轮转 / E6 报告四段齐全 / E7 Critic 截断回归（整体注意事项不得是半截 JSON）/ E8 错题本联动（条目可点开看现场+讲解）/ E9 DONE 后作答 409 / E10 降级（可临时改错 key 观察）。另人工验：历史面试列表点开旧报告、轮数选 3 实际 3 道主问题后 done。
- [x] **T11 两项用户迭代（2026-09-19，用户批准直接实施，api.md §6 + §1 已授权）**：① 模型 key 页面录入持久化，不再每次启动填终端 env；② 简历像 JD 一样下拉复用历史版本。
  - **① 密钥保险箱（security/KeyVault）**：
    - 落盘 `~/.mock-interview/vault.json`（可 `--app.vault.dir=` 覆盖），**AES-256-GCM**（12B IV/128b tag），原子写（tmp + ATOMIC_MOVE 回退 REPLACE_EXISTING）。两模式：**机器绑定**（不设密码；随机 32B DEK 存同目录 machine.key，重启自动解锁）/ **密码保护**（PBKDF2WithHmacSHA256 310000 轮 + 16B 盐派生 KEK，重启锁定等 `/unlock`）。
    - **来源优先级硬规则**：env `DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY` 优先；env 已提供时页面保存该厂商 → `IllegalStateException` → **409**。`save(deep,dash,password)` 语义：key 的 null=不动 / ""=清除 / 非空=覆盖；password 非空=转/改密码保护，null 或 ""=沿用现状（首次保存且不给密码 = 机器绑定）。
    - 热生效（无需重启）：`DynamicEmbedder` 每次调用按 keySupplier 在真实 DashscopeEmbedder / FakeEmbedder 间切换；`LlmService` 每次取 key 走 vault；`/api/health/deps` 加 `deepseek:{source,configured,unlocked}`。接口：`GET /api/setup/status`、`POST /api/setup/keys`、`POST /unlock`、`POST /lock`，**任何接口不回传明文**。
    - 新增文件：`security/KeyVault.java`、`security/KeyVaultException.java`、`web/KeySetupController.java`、`rag/DynamicEmbedder.java`、`security/KeyVaultTest.java`（7 用例纯单元）；改动 LlmService / DashscopeEmbedder（apiKey 改 Supplier，旧构造保留给测试）/ RagConfig / DepCheckController / 前端 index.html+app.js+style.css（左栏顶部密钥卡片三态：未配置展表单 / 锁定只显解锁框 / 可用显徽标点按展开）。
    - 🐞 **真机踩坑 1：错密码一度返 500 而非 401**。`KeyVaultException` 上的 `@ResponseStatus(401)` 被全局 `@RestControllerAdvice ApiExceptionHandler` 的兜底 `@ExceptionHandler(Exception.class)` 覆盖 → 必须在 Advice 里显式加 `KeyVaultException → 401 UNAUTHORIZED`。教训：**项目一旦有全局兜底 Advice，异常类上的 @ResponseStatus 全部失效**。
    - 🐞 **真机踩坑 2：沙盒写 `~/.mock-interview` 被宿主层拦截**（即使 Shell 传 `dangerouslyDisableSandbox`，Java 子进程写用户家目录仍被拒，vault save 抛 IOException→409「机器密钥不可读写」）。真机验证改用 `--app.vault.dir=<工作区>/.vault-e2e` 跑完全链路，验完目录已删；**用户在自己的终端无此限制**，默认路径直接可用。
  - **② 简历版本下拉**：`interview_session.resume_id`（纯列无外键，仅留痕；RAG 仍检索全局语料不按版本隔离，沿用 T7 已拍板边界）；`start` 加 `resumeId`，不存在 → 404（在调 LLM 前校验）。后端接口本已齐全（`GET /api/resume` 列表 / 首份自动 active / `POST /api/resume/{id}/activate`）；前端加 `#resumeSelect`（id 倒序，默认 active 或最新）+ 刷新按钮，上传后选中新版本，下拉 onchange 调 activate。`T10FeaturesTest` 加第 5 用例（合法 200 / 不存在 404 / 列表 isActive）。
  - ✅ **验收（2026-09-19）**：全量 `.\mvnw.cmd test` **39 用例（37 跑 2 skip 真实 LLM）BUILD SUCCESS**。mysql profile 真机（清空 env key 启动）：初始 NONE/NONE + fake embedder → 页面保存两 key → VAULT/unlocked、vault.json+machine.key 落盘且**密文不含明文片段**、deps 热切换 `dashscope:qwen3.7-text-embedding-flash` ping dim=1024、真实入库 vectorChunks 0→1 → lock 回退 fake / 机器模式 unlock 自动恢复 → 转密码模式、重启后**自动锁定**、错密码 401、对密码解锁恢复 → 清除单厂商 key status 转 NONE。简历：列表/上传两版（非首份不自动 active）/activate 唯一 active 切换/未知 404/start 未知 resumeId 404。浏览器人工 7 项全过（徽标三态、锁定、错密码 401 提示、对密码恢复、下拉 active 刷新持久化、status 无明文）。真机测试数据已清（resume 7/8、note 2/3 删除；active 仍为 id=1；`.vault-e2e` 已删；用户家目录无 `.mock-interview` 残留）。
    - 遗留：`note` 表 id=1（"T10验证知识点：Redisson…"）是上轮真机测试条目，非本轮产物，未删，用户可自行处理。
- [x] **T12 用户实测两问题修复（2026-09-19，用户批准直接实施，api.md §1/§3/§4/§6 已同步）**：
  - **问题 1：密码保护形同虚设 → 密码模式访问门禁（服务器/多设备诉求，单用户不做多账号）**
    - 根因：T11 密码只加密磁盘，解锁后 key 常驻进程内存，**没有任何请求级鉴权**——刷新页面/其他设备不输入密码照样调业务接口（花用户的 API 额度）。
    - 方案：`KeyVault` 内存维护令牌集合（`ConcurrentHashMap.newKeySet()`）：unlock / 带密码 save 成功后签发 **256b 随机 Base64URL 令牌**（不落盘，进程重启/lock/改密码全失效；多设备各自独立令牌，改密码 clear 旧签新）；新增 `security/VaultAccessFilter`（OncePerRequestFilter）：密码模式（保险箱落盘且 passwordProtected）下，除白名单（非 `/api/` 静态资源与页面、OPTIONS、`GET /api/setup/status`、`POST /api/setup/unlock`）外所有 `/api/**` 必须带令牌——header `X-Vault-Token` 或 SSE query `?vaultToken=`（EventSource 不能设头），失效返 **401 `{code:"UNAUTHORIZED",message}`**。机器绑定/纯 env/未配置不启用门禁。
    - KeyVault API 变化：`save`/`unlock` 返回类型改 **`UnlockResult(KeyStatus status, String token)`**；`lock()` 清令牌；新增 `accessRequired()`/`validToken(t)`。KeySetupController 两接口返回 UnlockResult（序列化为 `{status:{...},token}`），lock 仍返回 KeyStatus。
    - 前端：`state.vaultToken` **只存 JS 内存（严禁 localStorage）**；`api()` 注入 X-Vault-Token，业务 401 → 清 token + loadKeyStatus 回门禁态（/unlock 的 401 例外，直接抛「密码错误…」人话）；SSE URL 拼 `?vaultToken=`；门禁判定 `gated = s.passwordProtected && !state.vaultToken`（**以本地令牌为准，不信服务端 unlocked**——这是刷新保护的关键）；gated 时首屏只查 status，业务数据延后到解锁后 loadBusinessData，避免整屏 401。两个密码框（`#kuPassword`/`#ksPass`）加「显示/隐藏」按钮（`.pwdWrap`+`.pwdToggle`，切 input.type）。
  - **问题 2：答完最后一题自然结束不出报告**
    - 根因：`InterviewOrchestrator.nextMainQuestion()` 达 `maxQuestions` 时只发 `done(MAX_QUESTIONS_REACHED)`，报告只在手动 `end()` 里生成；前端 done 处理器也不补 end。手动点「结束」走 end() 所以一直能成功。
    - 修复：抽出私有 `finish(session, doneReason)` 作为出报告+置 DONE 的唯一实现（含「已有报告则重放」幂等分支，reportRepo/turnRepo 参数是 Long sessionId 不是实体——编译踩过）；`end()` 委托 reason=null，`nextMainQuestion()` 达上限分支在 answer 同事务内调 finish(reason=MAX_QUESTIONS_REACHED)，SSE 同批发 `report`+`done`（report 先于 done）。前端 done 处理器加幂等兜底：未渲染过报告时补 POST /end。
  - **工程：测试 vault 隔离**。pom surefire 加 `<systemPropertyVariables><app.vault.dir>${project.build.directory}/test-vault</app.vault.dir>`——否则用户本机若存过密码模式保险箱，@SpringBootTest 的 MockMvc 业务请求会被新过滤器全拦成 401。
  - **工程：静态资源缓存（真机踩出）**。Spring Boot 静态资源默认无 Cache-Control，浏览器启发式强缓存 app.js，后端发布新版后用户不 Ctrl+F5 一直跑旧 JS（T12 新 JS 被缓存吞掉，表现为 UI 显示「模型可用」但业务全 401）。application.yml 加 `spring.web.resources.cache.cachecontrol.no-cache=true`（每次回源验证，未变 304），实测响应头 `Cache-Control: no-cache` + If-Modified-Since 返 304。
  - 新增/改动文件：`security/VaultAccessFilter.java`（新）、`security/KeyVault.java`、`web/KeySetupController.java`、`agent/InterviewOrchestrator.java`、`static/app.js`、`templates/index.html`、`static/style.css`、`application.yml`、`pom.xml`；测试 `security/VaultAccessFilterTest.java`（新，6 用例 mock 过滤器）、`security/KeyVaultTest.java`（+3 令牌用例）、`web/T10FeaturesTest.java`（maxQuestions 用例改为断言 report 先于 done、status DONE、matchScore=55、end 幂等）。
  - ✅ **验收（2026-09-19）**：全量 `.\mvnw.cmd test` **48 用例（46 跑 2 skip）BUILD SUCCESS**。mysql 真机 API：密码模式无 token 业务接口 401（含服务端 unlocked=true 时——刷新场景核心）、status/unlock 白名单 200、错密码 401、对密码拿 token 两设备两令牌不同、header/query(SSE) 双通道 200/404、伪造/空 token 401、匿名 lock 401、lock 后两令牌全失效、静态资源/首页 200、机器模式无门禁。浏览器真机（真实 LLM）：gated 首屏（解锁框+「需要密码」+业务区零请求）、眼睛明文切换、错密码红字、对密码解锁业务数据加载、**F5 刷新后立即回门禁态**、3 题场答完 6 轮（3 主问+3 追问，评分 7/7/7/7/7/8）后**无任何点击自动出报告（匹配度 76/100，六轮四段讲解齐全）**，历史 #6 立即显示「76 分」可回看。e2e 会话 #6 数据已清理；服务已停、`.vault-e2e` 已删。
    - 浏览器工具教训：① 截图通道（browser_take_screenshot）在本环境 IDE Command timeout，证据改用 DOM evaluate；② evaluate script 里别写含 `\n` 的正则字面量（JSON 转义后变 "Invalid regular expression flags"），用字符串方法；③ 浏览器缓存排查先看 `performance.getEntriesByType('resource')` 的 transferSize（0=磁盘缓存）。

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
  - ✅ **2026-09-18 终版固化（不再需要每次手动 set JAVA_HOME）**：根因是 **Machine 级 JAVA_HOME 被 JDK24 安装器写成 24**（User 级为空，每个新终端都继承 24）。三处修完开箱即用：
    1. `mvnw.cmd` 自动选 JDK：解析 `JAVA_HOME` 对应 java 的主版本，在 **17-23** 内则尊重；**未设 / 无效 / 24+ 且 `%USERPROFILE%\jdk-21` 存在则自动回落**并打印 WARNING（版本解析用临时文件法，for/f 内嵌引号+管道在含空格路径下会失效）。三场景实测（继承24/显式21/未设）全部落到 21。
    2. `pom.xml` 加 **maven-enforcer-plugin 3.5.0** `requireJavaVersion [17,24)`，24+ 构建期硬失败（message 用**英文 ASCII**——中文在 GBK 控制台乱码，同 mvnw 纯 ASCII 教训）。
    3. `AGENTS.md` Commands 写明双 shell 启动法 + JDK 规则。
- **⚠️ 本机原生 MySQL80 服务占用 3306（2026-09-18 实测发现，用户已拍板共存方案）**：Windows 服务 `MySQL80`（自启，`D:\MySQL\MySQL Server 8.0`，数据 `D:\MySQLData\...`，root 密码非 root）占宿主 3306/33060，Docker MySQL 抢不到端口——**症状**：`docker compose ps` 显示 `3306/tcp` 无宿主映射（compose 文件却写了 ports），Spring 连 localhost:3306 会误连原生实例。**决策：容器改映射 `3307:3306`**（`docker-compose.yml` + `application-mysql.yml` 默认 `MYSQL_PORT:3307`），两实例共存，已 force-recreate 验通；生产无冲突时 `MYSQL_PORT=3306` 覆盖。教训：compose 端口不生效先 `netstat -ano | findstr :3306` 查宿主占用，再看 `Get-CimInstance Win32_Service | ? PathName -like '*mysqld*'` 定位服务；改 compose 配置必须 `--force-recreate`。
- **数据源形态（2026-09-17 修正）**：本地默认 **H2 内存库**（`application.yml`，零参数 `mvnw clean spring-boot:run` 即起，免 Docker 验 T1–T3 编译 + 建表）；真 MySQL 8 抽到 `application-mysql.yml`，需 `-Dspring-boot.run.profiles=mysql` 或 `SPRING_PROFILES_ACTIVE=mysql` 才生效。原 `application-h2.yml` 已删。Docker 拉镜像源超时问题仍存在、暂未修，但**不再阻塞本地验证**。
  - ⚠️ **最终生产 / 真库验收必须恢复 Docker 起 MySQL 8 + Redis 7，并走 `mysql` profile**。H2 重启即清空，仅本地验证用；Redis 生产也必须起（H2 路下若本机 Redis 未起，`redis.ok` 为 false，属预期但不代表生产可省）。
- **⚠️ R1-1 前端 JWT 未接线（2026-09-29 人审发现，阻塞浏览器可用性）**：后端 `JwtAuthFilter` 默认启用，所有 `/api/**` 除白名单（register/login/health）都要 JWT；但前端 `app.js` 既无用户登录/注册 UI，也无任何地方存/发用户 JWT（`api()` 不带 `Authorization`，SSE URL 只拼 `vaultToken`），`index.html` 只有保险箱密码框、无用户登录框。后果：R1-1 后浏览器打开 app，`start/answer/sessions/end/report` 全线 401，SSE 也连不上——后端 59 测试全绿是因为 `UserIsolationTest` 用代码直接带 token 测的，**浏览器不可用**。这不是后端 bug，是「后端鉴权完成但前端没接」的半完成态。修法（**R1-1.5 已立项 2026-09-29，见 tasks.md**）：① 加登录/注册面板；② 登录后把 JWT 存 `state.token`（内存变量、不落 localStorage）；③ `api()` 统一加 `Authorization: Bearer <jwt>`；④ SSE URL 拼 `?jwt=<jwt>`（密码模式下保留 `&vaultToken=<vt>`）。未补前，第 3 项「前端 SSE 仍能连」在浏览器层必定 FAIL，只能验后端 `?jwt=` 机制。

## 5. 下一步立即做（MVP 已完成，进入迭代期）

**MVP 全链路已打通（T1–T12 全部 ✅，48 用例全绿）**，下一阶段是「单用户本地」→「多用户可部署」。迭代总原则见 `docs/tasks.md` 与 vibe-coding 实战手册第 17 章。
- 每个新需求按轻量六阶段（规划 → feature 分支 → 单任务闭环 → 验证 Gate → 小步提交人审 → merge 回写 handoff）。
- **当前卡点：R1-1 已交付但尚未人审终审通过，先不要应允 Trea 推进 R1-2**。审完、merge、回写 handoff/tasks 后再开 R1-2，否则错误会越堆越多。
- **第一优先 R1（多用户 + 部署）+ R2（账号 + 额度）**：没有它，多用户与额度无从谈起；且现有 `InMemoryVectorStore` 内存向量库必须先换持久化，否则一部署即崩。
- 同步 R5（测试加固）。详细任务拆解与验收见 `docs/tasks.md`。

> 开工前提示：本机需 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY` 环境变量；Redis 需 docker 起。缺则 T2 会卡，先确认环境就绪。

## 6. MVP 后迭代规划（2026-09-28 拍板）

> 用户提出七大迭代点，已评估难度 / 难点 / 周期，并拆成原子 Task 落 `docs/tasks.md`。下方为结论摘要，执行细节见 tasks.md。

**七大迭代点（按推荐顺序）**：

| 需求 | 难度 | 周期 | 核心难点 / 我的建议 |
|---|---|---|---|
| R1 多用户 + 部署 | 高 | 3–5 周 | 无 User/认证/隔离；内存向量库必换；本地 KeyVault 密码模式不适用多用户；需反代/HTTPS/守护/CI。**第一优先** |
| R2 账号 + 大模型额度 | 中 | 1–1.5 周 | 额度按 token 计；并发扣减需原子（行锁/Redis INCR）；超限优雅降级；平台统一持 key |
| R3 多模型（DeepSeek 外） | 中 | 1–2 周 | 抽 ModelProvider + 路由；embedding 维度不同需隔离/重算；第一个加通义千问（已通 DashScope 通路） |
| R4 向量库 + 是否 LangChain | 中 | 1–1.5 周 | 内存无法持久化/共享；选 Redis vector 或落盘 Lucene。**结论：暂不引 LangChain**——手写 retriever 可控已跑通，框架反增不确定性 |
| R5 稳健 + 测试 | 中 | 持续 + 专项 1–2 周 | 补集成/弹性/E2E 测试；掌握 mock/stub/fake、Testcontainers、契约测试、超时重试降级测试 |
| R6 语音 / 视频 | 高 | 语音 2–4 周；视频 +3–5 周 | ASR/TTS/WebSocket 流式/VAD；视频加摄像头+带宽+隐私；与额度打通；先语音后视频 |
| R7 软性面试官 | 低 | 3–5 天 | 纯 prompt/角色（新增综合行为面试官）；难点在 STAR/压力面 rubric；评分维度扩展 |

**已核实的现状（迭代前地基，源码确认）**：
- 单用户本地：无 User 实体、无认证、数据全局共享。
- RAG：`InMemoryVectorStore` + `CosineSimilarity` 手写余弦 + 纯内存；pom 中 Lucene HNSW 仅 test 范围；**主链路无任何向量库、无 LangChain 依赖**。
- 模型：`LlmProperties` 仅绑 `deepseek.*`，单一模型；`LlmPort` 接口已有抽象雏形。
- 角色：`TECH_DIGGER` / `PROJECT_CHALLENGER` / `CRITIC`，无软性面试官。
- 测试：48 用例（46 run 2 skip），缺集成 / E2E / 弹性测试。

**我对需求的改进建议（排序逻辑）**：先 R1+R2 地基（含 R4 向量库迁移依附部署），同步 R5 织测试网；R7 低成本高回报可穿插；R3 非阻塞后做；R6 最高难度最后做。R4 明确不建议引 LangChain，保留手写检索、只换存储后端。
