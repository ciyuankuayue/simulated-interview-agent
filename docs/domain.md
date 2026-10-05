# 领域模型（domain.md）

> 唯一事实源：本文件定义实体、字段、关系、可见性边界。改结构先确认，再动代码。
> 原则：明细全在 DB 给 AI 推理用；用户看到的是「状态视图」和「Critic 报告」——**精简 = 表达精炼、抓核心、不啰嗦；关键内容（每答评价/纠正/知识补充/示范回答/整体注意事项/提醒）不省**。

## 1. 实体清单（13 个，R1-3 新增 user_api_key / quota）

| # | 实体 | 用途 | 可见性 |
|---|------|------|--------|
| 0 | **app_user**（R1-1，R1-3 扩） | 用户账号：注册/登录 + JWT 鉴权，所有实体的 owner；含 key_mode / role | 不对外暴露 |
| 0a | **user_api_key**（R1-3） | 用户自带 DeepSeek key（BYOK），AES-GCM 密文落库 | 仅回 hasKey + last4 |
| 0b | **quota**（R2 token 计量） | 用户 token 额度：注册赠送、PLATFORM 按真实 token 扣、BYOK 按次扣服务费、ADMIN 可充值 | 用户可看余额/明细 |
| 0c | **llm_usage_log**（R2 新增） | 每次 LLM/embedding 调用的 token 用量与扣减流水 | 仅本人/ADMIN，内部明细 |
| 1 | jd_profile | JD 原文 + 考点关键词 + 岗位类型 + 同类标签 | 列表（精简），按 user_id 隔离 |
| 2 | resume_version | 多版本简历原文 + 上传时间 | 可切换，按 user_id 隔离 |
| 3 | note | 笔记存档点（可加可看） | 可看，按 user_id 隔离 |
| 4 | project_doc | 项目文档详解（质疑官弹药库，外部上传） | 内部，按 user_id 隔离 |
| 5 | interview_session | 一次模拟（关联 JD + 角色 + 状态） | 状态，按 user_id 隔离 |
| 6 | interview_turn | 每轮：问题/回答/检索引用/判分/耗时/token | 内部，按 user_id 隔离 |
| 7 | real_interview_record | 上传的真实面试记录 | 内部，按 user_id 隔离 |
| 8 | weak_point | 错题本：知识点 + 错几次 + 复习阶段 + 下次复习时间 | 状态（精简），按 user_id 隔离 |
| 9 | critic_report | 报告：逐轮讲解 + 纠正 + 知识补充 + 示范回答 + 整体注意事项 + 提醒 + 匹配度 | 给用户看（精炼不省关键），按 user_id 隔离 |
| 10 | knowledge_chunk | 分块文本 + 来源（**JPA 死表，实际向量存 Lucene 索引，见 §7**） | 内部，按 user_id 隔离 |

## 2. 字段定义

### app_user（R1-1 新增；R1-3 扩 key_mode / role；R3-2 扩 chat_provider）
- id (PK)
- username         # 唯一用户名，4-32 字符
- password_hash    # BCrypt 加密（10 轮）
- key_mode         # PLATFORM（平台 key，默认）/ BYOK（用户自带 DeepSeek key）
- chat_provider    # DEEPSEEK（默认）/ QWEN（通义千问）；R3-2 新增，VARCHAR(16) NOT NULL DEFAULT 'DEEPSEEK'，与 key_mode 正交
- role             # ADMIN（库中首个注册账号）/ USER（其后注册者）；R1-3 仅持久化，管理接口 R 后续迭代用
- created_at

> 表名用 `app_user` 避开 H2/MySQL `user` 保留字。密码 BCrypt，不存明文也不存加盐哈希——Spring Security 的 `BCryptPasswordEncoder` 自带盐值。

### user_api_key（R1-3 新增，BYOK）
- id (PK)
- user_id (FK UNIQUE, NOT NULL)   # 一用户至多一条
- key_ciphertext                  # DeepSeek key 的 AES-256-GCM 密文（Base64，含 12B IV/128b tag）
- key_last4                       # 明文末 4 位（展示用，如 sk-...abcd）
- created_at / updated_at

> 主密钥（KEK）从环境变量 `APP_DATA_KEY` 读（Base64 32B）；未配置时启动自动生成临时密钥并 WARN（重启后旧密文不可解，BYOK 用户需重新填写——仅开发便利；多实例部署必须显式配置同一密钥）。API **永不回显明文**：GET 只回 `{hasKey, last4}`；保存=轮换（先调一次 DeepSeek 验活再落库）；删除后用户 key_mode 自动回退 PLATFORM。BYOK 仅支持 DeepSeek；embedding（DashScope）永远走平台 key。

### quota（R2：token 计量；R1-3 的 points 列已整体替换）
- id (PK)
- user_id (FK UNIQUE, NOT NULL)
- total_tokens      # 总额度（token 单位；注册赠送 app.quota.signup-bonus-tokens，默认 500000；ADMIN 可加）
- used_tokens       # 已消耗（token 当量；非负，永不超过 total，最后一次透支由 clamp 归零到 total）
- created_at / updated_at

> R2 计量规则（用户冻结）：
> - **PLATFORM**：DeepSeek 与 embedding（DashScope）都走平台 key。调用**前预检**余额（=0 直接 402，不发请求）；调用**后**按响应 `usage.total_tokens` **实扣**；usage 缺失按「输入+输出字符数 1 字符≈1 token」估算兜底（偏保守，`app.quota.usage-estimate-enabled` 可关）。允许最后一次小幅超出，扣减时 clamp 到 total，之后预检拦死。
> - **BYOK**：API 费由用户自己付给 DeepSeek；平台仍在调用**前**扣固定服务费 `app.quota.byok-charge-per-call`（token 当量，默认 1），条件 UPDATE 不超发，余额不足 402。BYOK 的 embedding 仍走平台 key → 按 token 实扣（与对话模式无关）。
> - 扣减与流水在 REQUIRES_NEW 独立事务提交：token 已真实烧掉，业务事务后续回滚不退款。
> - 并发安全：单条条件 UPDATE 原子扣减（不引 @Version/Redis INCR），并发压测保证不超发。

### llm_usage_log（R2 新增，用量流水）
- id (PK)
- user_id (FK NOT NULL, 索引)
- scene            # JD_PARSE / FOLLOWUP / SCORE / CRITIC / CHAT / EMBED_INGEST / EMBED_QUERY
- key_mode         # PLATFORM / BYOK（embedding 恒 PLATFORM；R3-2 起 BYOK 用户选 QWEN 时对话也记 PLATFORM）
- model            # 「厂商:模型名」标识（R3-1 起）：deepseek:<m> / qwen:<m> / dashscope:<embed-m>；历史行可能是裸 deepseek-flash（无前缀），原样保留不回填
- prompt_tokens / completion_tokens / total_tokens   # 厂商 usage 原值；embedding 只 total；估算兜底时三者可为 0 而 total=估算值并记 estimated=true 的来源（实现上以 total_tokens>0 为准）
- charged_tokens   # 实际从 quota 扣减的 token 当量（BYOK 对话时为服务费 1，不等于 total_tokens）
- created_at

> 用途：AGENTS.md「明细入 DB」原则下的逐次消耗凭证；支撑 GET /api/user/usage 与未来对账/管理后台。不做修改/删除接口。

### jd_profile
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1：归属用户
- raw_text         # JD 原文
- keywords         # 考点关键词（JSON 数组）
- job_type         # 后端 / AI 应用 / 全栈
- same_type_tag    # 同类标签（用于多 JD 勾选归类）
- created_at

### resume_version
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1
- content          # 简历原文（md）
- created_at        # 上传时间
- is_active         # 当前启用版本

### note
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1
- content          # 笔记正文（md）
- created_at

### project_doc
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1
- title
- content          # 项目详解（md，外部上传）：背景/目标 → 技术栈与选型理由 → 关键设计/架构 → 踩过的坑与解决 → 假设未来某问题的处理
- updated_at        # 记录/补充「现状」（质疑官可据此追问演进）

> project_doc 是**使用者将他简历中提到的项目，单独写详解后外部上传**的。它是质疑官弹药库，建议必传；若缺失，质疑官退化为仅基于简历入口（resume_version 内嵌的项目介绍）提问，质量下降。两者都进 knowledge_chunk，质疑官侧重 project_doc。

### interview_session
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1
- jd_profile_id (FK)
- roles            # 启用的角色数组，如 ["技术深挖官"] 或 ["技术深挖官","项目质疑官","行为面试官"]（R7 起支持行为面试官；支持部分启用；Critic 始终在）
- status           # INIT / QUESTIONING / SCORING / DONE
- match_score      # 本 JD 当前匹配度（Critic 填）
- created_at

### interview_turn
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1（冗余自 session，方便直接查询）
- session_id (FK)
- question          # 本轮问题
- answer            # 用户回答
- retrieve_refs      # 检索引用（JSON，知识块 id 列表）
- score_json         # 判分 {track(R7: tech/behavior), score, breakdown, missed_points, followup}（内部用，决定是否追问；不向用户全量展示；track 只落此 JSON，interview_turn 不加 role 列）
- elapsed_ms         # 耗时
- tokens             # token 消耗
- seq               # 轮次序号（追问层数 ≤ 2）

### real_interview_record
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1
- content          # 真实面试记录原文
- created_at

### weak_point
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1
- knowledge_point   # 知识点
- wrong_count       # 错几次
- review_stage      # 复习阶段 0/1/2/3（固定阶梯，见 §5）
- next_review_at    # 下次提问时间（间隔重复）
- status            # 现状（可关联 project_doc 的「现状」）
- created_at        # 首次记入错题本的时间
- resolved          # 是否已出队（该知识点本轮 score>=6 即置 true）
- resolved_at       # 出队时间
- jd_profile_id (FK, 可空)

### critic_report
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1（冗余自 session，方便直接查询）
- session_id (FK)
- per_turn_review    # JSON 数组，每轮：{ question, score, track(R7: tech/behavior，缺省 tech), explanation(逐轮讲解), correction(纠正), knowledge_supplement(知识补充), exemplar_answer(示范回答) }
- collapsed_points   # 答崩点汇总
- expression_issues  # 表述问题 + 精简答案指导
- overall_notes      # 整体注意事项
- reminders          # 必要提醒面试者（如"你说用了 Redis，但没说 TTL 为什么这么设"）
- behavior_review    # R7 新增（TEXT，可空）：行为维度 JSON {dimensions:{STAR结构,行动细节,结果与反思,表达与抗压 各0-2}, summary, suggestions[]}；无行为轮为 NULL，老数据不回填；生产 ddl-auto:none 需手跑 V3 迁移
- match_score        # 匹配度（单一 0-100；含行为轮时软素质由 Critic 纳入综合，不另设分数）
- created_at

### knowledge_chunk
> ⚠️ **R4 起此 JPA 实体 / `knowledge_chunk` 表是 T8 预留的死代码**（无 service 注入，ddl-auto 仍建表但从不读写）。向量块唯一真实存储是 **Lucene FSDirectory 索引**（见 §7），字段口径以下方 Lucene 文档为准，与本表列无关。
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1
- source_type       # resume / note / project_doc / real_record
- source_id         # 来源实体 id
- chunk_text        # 分块文本
- embedding         # 历史字段；实际向量不落 DB，存 Lucene（通义 qwen3.7-text-embedding-flash，dim=1024）
- created_at

## 3. 关系

- app_user 1—1 user_api_key（BYOK 可缺省）；app_user 1—1 quota（注册即建）
- jd_profile 1—* interview_session（一次模拟基于一个 JD；同类多 JD 可勾选一起练）
- interview_session 1—* interview_turn
- resume_version / note / project_doc / real_interview_record → 经 RAG 切分为 knowledge_chunk（检索源，双源：简历入口 + project_doc 弹药库）
- interview_turn → weak_point（答崩即记错题本）
- interview_session 1—1 critic_report
- weak_point 关联 jd_profile（按 JD 维度排匹配度与复习）

## 4. 判分结构（落 interview_turn.score_json，内部）

**技术轮（track=tech）**：4 维度 × 0-2 分 = 0-8：
- 正确性 / 完整性 / 深度 / 实证
映射：8=S（不追问）｜6-7=A（1 层 why）｜4-5=B（2 层 why）｜0-3=C/D（记错题本）
追问触发：score<6 → ≥1 层；score<4 → 2 层（封顶）
判分模型输出结构化 JSON `{score, breakdown, missed_points, followup}`，供 Critic 汇总。

**行为轮（track=behavior，R7）**：STAR + 表达四维 × 0-2 分 = 0-8：
- STAR结构（情境/任务/行动/结果是否完整）/ 行动细节（个人贡献与动作是否具体）/ 结果与反思（结果是否可量化、有无复盘沉淀）/ 表达与抗压（条理、简洁、被追问时是否稳定）
- 同样 0-8 分、同样的追问阈值（<6 追问、<4 两层）；追问内容是压细节（"当时你具体做了什么""结果数字是多少"），不是技术 why。
- 行为轮薄弱点同样入 weak_point，**不扩表**：knowledge_point 用模型输出的行为标签（如「意见分歧中的诉求对齐与方案落地」），靠标签语义与技术知识点区分；resolved 阈值仍为 score≥6。
- 判分 JSON 键结构与技术轮一致（score/breakdown/missed_points/followup），仅 breakdown 四维名称不同；外层多 `track:"behavior"`。

## 4.1 实时行为 vs 报告行为（关键区分）

- **实时（技术深挖官 / 项目质疑官）**：用户答题后只做两件事之一——「追问（≤2 层 why）」或「一句简单纠正」。**不展开讲解、不给知识补充、不给示范回答**。
- **实时（行为面试官，R7）**：只出行为题（三类题池：协作冲突 / 抗压受挫 / 主动性与影响力，结合用户真实资料，不编造经历）；答题后围绕 STAR 礼貌施压追问细节或一句纠正。**红线：不考任何具体技术知识点、不现场讲大道理、不展开讲解/知识补充/示范回答**；纯行为场首题祈使句不得出现"紧扣 JD 考点"。
- 判分（技术 4 维 / 行为 STAR 4 维）实时都算，但仅用于决定是否追问；不向用户展示 breakdown 全量。
- **报告（Critic）**：对每轮做评分 + 逐轮讲解 + 纠正 + 知识补充 + 示范回答；汇总答崩点、表述问题、整体注意事项、必要提醒；答崩点入 weak_point。**R7 起：逐轮回填 track；本场存在行为轮时额外产出 behaviorReview（STAR 四维分 + ≤120 字总评 + ≤4 条建议），无行为轮为 null；matchScore 仍单一分值，软素质纳入综合。**

## 5. 间隔重复（weak_point，方案 A 固定阶梯）

- review_stage ∈ {0,1,2,3}，对应间隔天数 [1,3,7,15]
- `next_review_at = created_at/answered_at + INTERVAL [1,3,7,15][review_stage] DAY`
- 答错（再次答崩）：review_stage = min(stage+1, 3)，wrong_count+1，重排 next_review_at
- 答对（该知识点本轮 score≥6）：从 weak_point 移除（或标记 resolved）
- MVP 不做 Leitner / SM-2，跑通后再评估

实现（T8）：`memory/SpacedRepetition.java` 排期 + `memory/WeakPointMemoryService.java` 唯一入口
`onTurnScored(jd, knowledgePoint, score, status, now)`。知识点 key 用**判分模型输出的 `knowledge_point` 短标签**
（不用 missed_points——措辞每轮都漂，会把同一知识点打散成多条），匹配前做归一化：去标点空白 → 去中文虚词 → 小写，
再精确相等或包含匹配（较短一方 ≥4 字）。

## 6. 边界（MVP，按 §6）

- 输入：JD 文本 + 你的 md 文档（简历 / 笔记 / 项目文档 / 真实面试记录）
- 向量：**R4 起落盘 Lucene FSDirectory（持久化 + userId pre-filter，见 §7）**；此前的内存手写余弦仅 L3 对比实验保留
- 角色：支持部分启用（可只开技术深挖官 / 只开行为面试官）；角色按勾选顺序轮转（追问继承所属主问题角色）；Critic 始终在
- 用户绝不接触 interview_turn / project_doc / real_interview_record / knowledge_chunk 原始明细

## 7. R4 向量持久化（Lucene FSDirectory）

> 结论：向量块从「进程内 `InMemoryVectorStore`（重启即丢、向量层跨用户可见）」迁到 **Lucene 9.12.3 FSDirectory 落盘索引**，并在向量检索层做用户隔离。检索算法仍是归一化向量上的最近邻（Lucene HNSW，等价余弦）。

**存储位置与配置**
- 索引目录默认 `data/rag-index`（相对路径基于进程 `user.dir` 解析为绝对路径；env `APP_RAG_INDEX_DIR` 可覆盖），`.gitignore` 忽略 `data/`。
- 打开模式显式 `CREATE_OR_APPEND`：重启追加、绝不静默重建（重建=丢数据）。维度由启动时校验：现有索引向量维度与 `app.rag.dim`（默认 1024）不一致直接抛带中文指引的异常，不混用。
- **Docker / 部署**：索引目录必须挂持久卷并随应用容器生命周期（`APP_RAG_INDEX_DIR=/app/data/rag-index` + `./data/rag-index` 卷挂载，docker-compose 已放注释态 app 模板，R1-4 写 Dockerfile 时启用）；不挂卷容器重启即丢。FSDirectory 不支持多机多 writer，横向扩展需迁 Redis Stack / pgvector。

**Lucene 文档字段**：`id`(=`sourceType#sourceId#chunkIdx`) / `userId` / `sourceType` / `sourceId`（StringField，索引+存储）、`chunkIdx`(IntPoint)、`content`(StoredField)、`vec`(KnnVectorField，**不**回存向量，读取侧 embedding=null)。写入/查询统一 pad-0/truncate 到 1024 后 L2 归一化（零向量原样），归一化后 Lucene 默认 EUCLIDEAN 排序等价余弦。

**用户隔离（pre-filter）**
- 登录用户用 4 参 `KnnVectorQuery("vec", q, candidateK, TermQuery(userId))`，filter 在 **HNSW 图遍历期**生效——别人的块不占召回名额，单用户资料极少也能召回自己；`candidateK=max(topK*3,30)`。离线（userId=null）用 3 参无 filter。
- 跨用户互不可见 + 自己必能召回，由 `LuceneVectorStoreTest` 的极端 post-filter 场景（u1 仅 1 块 cos 0.8、u2 灌 60 个 cos≥0.85 更近块，u1 仍命中自己）与 `RagServiceUserIsolationTest` 保证。

**replace 批次事务（PUT 同 source 不产生重复块）**
- 入库唯一入口 `MaterialController.index → RagService.ingest`：预检（402 在任何索引动作前）→ `beginReplace`（缓冲删旧，提交前不可见）→ 逐块 embed+add → `commitBatch`（commit + 切 NRT reader）；异常 `rollbackBatch`（`IndexWriter.rollback()` 丢弃本批删/增并关闭 writer，锁内重建，旧已提交块保持可用）。
- 删除是 **AND 语义** `BooleanQuery MUST(userId)+MUST(sourceType)+MUST(sourceId)` 传 `deleteDocuments(Query)`；严禁 `deleteDocuments(Term...)`（变长参是 OR，会删光该用户全部资料）。
- 读一致性：NRT reader 只在构造后 / 提交后 / rollback、deleteAll 后切换；检索进行中命中旧快照。一把 `ReentrantLock` 串行化批次区间，search 走独立监视器不阻塞写。
- **将来新增「删除资料」接口时，必须同步调 `deleteBySource` 清向量**，否则成为孤儿块。

**孤儿块边界与 dev 启动清理**
- userId pre-filter 保证**跨用户不泄露**；但「资料行被删 / H2 create-drop 重建库」后，本用户旧向量若不清，仍会被**自己**检索到（悬空块）。
- `StartupIndexCleaner`（ApplicationRunner）：开关 `app.rag.clean-on-empty-sources`（默认 **true**，mysql profile 显式 **false**）打开时，启动统计四张源表（`resume_version/note/project_doc/real_interview_record`）行数之和，=0 则 `deleteAll()` 并打 WARN，>0 不动；任何异常只告警不阻断启动。生产 mysql 资料持久，不存在该问题。

**监控语义**：`GET /api/health/deps` 的 `embedder.vectorChunks` = 全局**存活** Lucene doc 总数（`reader.numDocs()`，跨用户、不含已逻辑删除待合并的旧 replace 版本），仅作粗监控，**不是**当前用户块数。

## 8. R3 多模型路由

> 目标：对话 LLM 从「DeepSeek 单厂焊死」重构为「Provider 抽象 + 用户级厂商路由」，首批接入通义千问（DashScope OpenAI 兼容模式）。embedding 不动（仍 DashScope 恒平台 key）。

**分层**
- `llm/chat/`：`ChatProvider`（`id()` + `chat(ChatRequest, apiKey)`）、`ChatRequest(model, messages, thinking, jsonMode, tools, maxTokens)` / `ChatResponse(content, reasoning, usage)`、`ModelRole(FAST/REASONER)`。
- 两个实现：`DeepSeekChatProvider`（id=`deepseek`，思考开关 `thinking:{type:enabled/disabled}` 对象）与 `QwenChatProvider`（id=`qwen`，思考开关为**顶层布尔** `enable_thinking`，思维链回 `reasoning_content`；`response_format/messages/max_tokens/tools/usage` 同 OpenAI 形状）。差异在 provider 内部消化，不泄漏到编排层。
- `ModelRouter`（Spring 收集 `List<ChatProvider>` 成 id→provider map）：登录用户按 `app_user.chat_provider` 路由；user=null（测试 profile/内部）走 `app.llm.default-provider`（默认 deepseek）。角色→模型名：DeepSeek 取 LlmProperties 的 model-flash/model-reasoner；Qwen 取 DashscopeProperties 的 `chat-model-fast`（默认 qwen3.8-flash）/`chat-model-reasoner`（默认 qwen3.7-plus，**以百炼控制台实测为准**）。
- `KeyResolver.resolveForChat(user, providerId)` 决定 key 与计费模式：`qwen` 恒返回平台 `DASHSCOPE_API_KEY` + PLATFORM（不看 user.keyMode、不读 user_api_key）；`deepseek` 维持 PLATFORM env / BYOK 解密两路。
- 唯一对话收口仍是 `LlmService.chat`：route → resolve key → 计费预检 → provider.chat → settle（流水 model 写 `route.label()`=`providerId:model`）。

**已验证的 Qwen 协议要点（真机）**
- FAST `qwen3.8-flash` 非思考约 1s；REASONER `qwen3.7-plus` 思考+`response_format:json_object` 组合可用（实测 ~13s，返回合法判分 JSON，usage 带 `completion_tokens_details.reasoning_tokens`，total_tokens 完整可供实扣）。
- 思考期首字节静默可超 10s：QwenChatProvider 显式 connect 10s / read 60s / call 90s，不依赖注入客户端默认值。
- 缺 key → 中文 `LlmException`（点名 DASHSCOPE_API_KEY）→ 504 `LLM_UNAVAILABLE`；4xx 不重试，5xx/IO 重试 3 次。

**设置与流水**
- 前端设置区「对话模型」单选即时生效（无会话锁定）；PUT /api/user/settings 两字段可分别部分更新。
- BYOK 用户 + QWEN：流水 `keyMode=PLATFORM`、`model=qwen:<m>`、charged=total（真机 E2E 实测全程 5k+ token 实扣、0 行服务费 1）。
- R3-3（embedding 维度按厂商隔离）拍板延后：当前 Qwen 仅对话，embedding 继续恒 DashScope 1024 维。

