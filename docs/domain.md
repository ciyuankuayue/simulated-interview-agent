# 领域模型（domain.md）

> 唯一事实源：本文件定义实体、字段、关系、可见性边界。改结构先确认，再动代码。
> 原则：明细全在 DB 给 AI 推理用；用户看到的是「状态视图」和「Critic 报告」——**精简 = 表达精炼、抓核心、不啰嗦；关键内容（每答评价/纠正/知识补充/示范回答/整体注意事项/提醒）不省**。

## 1. 实体清单（13 个，R1-3 新增 user_api_key / quota）

| # | 实体 | 用途 | 可见性 |
|---|------|------|--------|
| 0 | **app_user**（R1-1，R1-3 扩） | 用户账号：注册/登录 + JWT 鉴权，所有实体的 owner；含 key_mode / role | 不对外暴露 |
| 0a | **user_api_key**（R1-3） | 用户自带 DeepSeek key（BYOK），AES-GCM 密文落库 | 仅回 hasKey + last4 |
| 0b | **quota**（R2 token 计量） | 用户 token 额度：注册赠送、PLATFORM 按真实 token 扣、BYOK 按真实用量比例扣服务费、ADMIN 可充值 | 用户可看余额/明细 |
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
- role             # USER（公开注册恒为 USER）/ ADMIN（仅 env 引导器创建或升级，见 §9）
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
- total_tokens      # 总额度（token 单位；注册赠送 app.quota.signup-bonus-tokens，默认 100000 = 200 积分；ADMIN 可加）
- used_tokens       # 已消耗（token 当量；非负，永不超过 total，最后一次透支由 clamp 归零到 total）
- created_at / updated_at

> 积分展示（2026-10-19）：1 积分 = 500 token 当量。保留现有 token 账本与 API；页面按此比例显示，最多 3 位小数，不把 0.002 积分舍入成 0。新用户默认赠 200 积分，存量用户额度不重置。
>
> 现行计量规则（2026-10-30 调整默认赠额与服务费）：
> - **PLATFORM**：DeepSeek 与 embedding（DashScope）都走平台 key。调用**前预检**余额（=0 直接 402，不发请求）；调用**后**按响应 `usage.total_tokens` **实扣**；usage 缺失按「输入+输出字符数 1 字符≈1 token」估算兜底（偏保守，`app.quota.usage-estimate-enabled` 可关）。允许最后一次小幅超出，扣减时 clamp 到 total，之后预检拦死。
> - **BYOK**：API 费由用户自己付给 DeepSeek；调用前仅预检余额，调用后按真实 token 的十分之一收服务当量（5000 模型 token = 1 积分），向上舍入到 1 内部 token 当量；余额为零返回 402。BYOK 的 embedding 仍走平台 key → 按实际用量折算积分（与对话模式无关）。
> - 扣减与流水在 REQUIRES_NEW 独立事务提交：token 已真实烧掉，业务事务后续回滚不退款。
> - 并发安全：新结算在用户额度行写锁内刷新余额、按唯一调用号去重、扣减与流水同事务；不引 @Version/Redis INCR。旧辅助条件 UPDATE 保留兼容。

### llm_usage_log（R2 新增，用量流水）
- id (PK)
- user_id (FK NOT NULL, 索引)
- scene            # JD_PARSE / FOLLOWUP / SCORE / CRITIC / CHAT / EMBED_INGEST / EMBED_QUERY
- key_mode         # PLATFORM / BYOK（embedding 恒 PLATFORM；R3-2 起 BYOK 用户选 QWEN 时对话也记 PLATFORM）
- model            # 「厂商:模型名」标识（R3-1 起）：deepseek:<m> / qwen:<m> / dashscope:<embed-m>；历史行可能是裸 deepseek-flash（无前缀），原样保留不回填
- prompt_tokens / completion_tokens / total_tokens   # 厂商 usage 原值；embedding 只 total；估算兜底时三者可为 0 而 total=估算值并记 estimated=true 的来源（实现上以 total_tokens>0 为准）
- charged_tokens   # 扣减的 token 当量（BYOK 对话按 10% 用量结算，不等于 total_tokens；余额不足时 clamp，历史按当时费率保留）
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
- max_questions    # 主问题上限 1–8，不含追问；Agent 可根据考察结果提前结束
- resume_id        # 用户所选简历；新 Agent 场保存并使用该版本快照，旧场仅留痕
- agent_state      # V5，LONGTEXT JSON：version、本场 JD/所选简历原文 snapshot、最新 plan、lastDecision（含END最后行动/证据）；NULL=legacy 兼容路径
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
- score_json         # 判分 {track(tech/behavior), score, breakdown, missed_points, followup}；评分是Agent行动参考，失败不作为0分；不向用户全量展示
- agent_metadata     # V5，LONGTEXT JSON：action、实际 role、question、goal、reason、plan、evidenceIds、有限证据片段/工具状态及modelCalls/toolCalls/elapsedMs/tokens；不存内部推理链，NULL=旧轮次
- elapsed_ms         # 耗时
- tokens             # token 消耗
- seq               # 轮次序号（追问层数 ≤ 2）
- layer             # 0=主问题，1/2=同考点追问或澄清；追问继承主问题角色和赛道

> V5仅新增内部列，不新增HTTP字段/SSE事件；prod先备份，再由Flyway执行 `V5__interview_agent_state.sql`。迁移先查列是否存在，兼容本地mysql/ddl-auto:update已建列，原记录不回填。新场默认Agent，关闭开关只影响后续新场；已有Agent场按持久状态恢复。answer对402/409业务异常不回滚已保存回答/评分；并发guard/行锁与有限上下文边界见 [Agent运行时](agent-decision-runtime.md)。

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
- behavior_review    # R7 新增（TEXT，可空）：行为维度 JSON {dimensions:{STAR结构,行动细节,结果与反思,表达与抗压 各0-2}, summary, suggestions[]}；无行为轮为 NULL，老数据不回填；prod由Flyway管理迁移
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
分级：8=S｜6-7=A｜4-5=B｜0-3=C/D（记错题本）。新 Agent 场由回答诊断与证据决定继续、澄清、换题或结束，分数不直接决定行动；低分可换题，高分可核实依据，追问仍最多两层。
legacy 场保留旧规则：8 不追问、6–7 最多一层、≤5 最多两层。
判分模型输出结构化 JSON `{score, breakdown, missed_points, followup}`，供 Critic 汇总。

**行为轮（track=behavior，R7）**：STAR + 表达四维 × 0-2 分 = 0-8：
- STAR结构（情境/任务/行动/结果是否完整）/ 行动细节（个人贡献与动作是否具体）/ 结果与反思（结果是否可量化、有无复盘沉淀）/ 表达与抗压（条理、简洁、被追问时是否稳定）
- 同样 0–8 分；新 Agent 按诊断决定动作，legacy 沿用上述分数层数规则。行为追问压 STAR 细节与本人行动，未知结果先询问，不补造数字。
- 行为轮薄弱点同样入 weak_point，**不扩表**：knowledge_point 用模型输出的行为标签（如「意见分歧中的诉求对齐与方案落地」），靠标签语义与技术知识点区分；resolved 阈值仍为 score≥6。
- 判分 JSON 键结构与技术轮一致（score/breakdown/missed_points/followup），仅 breakdown 四维名称不同；外层多 `track:"behavior"`。

## 4.1 实时行为 vs 报告行为（关键区分）

- **实时（技术深挖官 / 项目质疑官）**：Agent 可继续追问/澄清、换考点或结束；评分事件可带一句纠正，追问/澄清共用≤2层预算。**不展开讲解、不给知识补充、不给示范回答**。
- **实时（行为面试官，R7）**：只出行为题（三类题池：协作冲突 / 抗压受挫 / 主动性与影响力，结合用户真实资料，不编造经历）；答题后围绕 STAR 礼貌施压追问细节或一句纠正。**红线：不考任何具体技术知识点、不现场讲大道理、不展开讲解/知识补充/示范回答**；纯行为场首题祈使句不得出现"紧扣 JD 考点"。
- 判分（技术 4 维 / 行为 STAR 4 维）实时都算；新场评分得到本场快照、有限前文与证据，作为行动参考，不替代诊断；不向用户展示 breakdown 全量。
- **报告（Critic）**：沿用逐轮评分，补充讲解、纠正、知识补充和示范回答；汇总答崩点、表述问题、整体注意事项、必要提醒；错题写入仍由实时判分驱动。**R7 起：逐轮回填 track；本场存在行为轮时额外产出 behaviorReview（STAR 四维分 + ≤120 字总评 + ≤4 条建议），无行为轮为 null；matchScore 仍单一分值，软素质纳入综合。**
- 新报告只保留实际轮次、去掉重复轮次，并恢复权威 question/score/track；未评分保持 null。证据与候选人陈述均不自动成为已核实事实，示范未知经历/数字用占位符，overallNotes 说明已考察和未评估范围；不改变既有报告 JSON 字段。

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
- 角色：支持部分启用；新 Agent 从启用角色中按考察目标选择，只有 ASK_MAIN 可换角色，PROBE/CLARIFY 继承当前角色；legacy 按勾选顺序轮转；Critic 始终在
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

**strict fail-closed（R2-T0 批 3，D2：单目录，不做 per-tenant 目录）**
- 守卫收口在生产唯一编排方 `RagService`（`LuceneVectorStore` 保持通用，POJO/实验/测试构造仍可传 null）：`app.tenant.strict=true` 时，`ingest`/`retrieve` 取到的 `userId`（`CurrentUserService.currentUserId()`，与是否装配计量无关）为 null → **在切块/embed/预检/任何索引写入之前**直接抛 `IllegalStateException`（消息含 `VECTOR_USER_MISSING`，全局异常映射为 409 CONFLICT——prod 下只可能是装配错误，必须显眼）；不产生任何向量块、不发 embedding 请求。
- 非 strict（dev/test）保持 null 语义：3 参无 filter 路径放行（experiment、`StartupIndexCleaner` 白名单）。
- strict 开关由生产构造注入 `TenantProperties`（`RagConfig` 装配）；离线便利构造（无 TenantProperties）恒为非 strict。
- **null 调用面白名单（C2 审计项）**：除 test / `rag/experiment` / `StartupIndexCleaner`（dev only，不经 RagService）外，生产代码禁止向 RagService 传 null 身份。

**replace 批次事务（PUT 同 source 不产生重复块）**
- 入库唯一入口 `MaterialController.index → RagService.ingest`：预检（402 在任何索引动作前）→ `beginReplace`（缓冲删旧，提交前不可见）→ 逐块 embed+add → `commitBatch`（commit + 切 NRT reader）；异常 `rollbackBatch`（`IndexWriter.rollback()` 丢弃本批删/增并关闭 writer，锁内重建，旧已提交块保持可用）。
- 删除是 **AND 语义** `BooleanQuery MUST(userId)+MUST(sourceType)+MUST(sourceId)` 传 `deleteDocuments(Query)`；严禁 `deleteDocuments(Term...)`（变长参是 OR，会删光该用户全部资料）。
- 读一致性：NRT reader 只在构造后 / 提交后 / rollback、deleteAll 后切换；检索进行中命中旧快照。一把 `ReentrantLock` 串行化批次区间，search 走独立监视器不阻塞写。
- **将来新增「删除资料」接口时，必须同步调 `deleteBySource` 清向量**，否则成为孤儿块。

**孤儿块边界与 dev 启动清理**
- userId pre-filter 保证**跨用户不泄露**；但「资料行被删 / H2 create-drop 重建库」后，本用户旧向量若不清，仍会被**自己**检索到（悬空块）。
- `StartupIndexCleaner`（ApplicationRunner）：开关 `app.rag.clean-on-empty-sources`（默认 **true**，mysql profile 显式 **false**）打开时，启动统计四张源表（`resume_version/note/project_doc/real_interview_record`）行数之和，=0 则 `deleteAll()` 并打 WARN，>0 不动；任何异常只告警不阻断启动。生产 mysql 资料持久，不存在该问题。

**ADMIN 巡检与回收（R2-T0 批 5，C3：warn-first，不做自动清理）**
- 只读枚举能力下沉 `VectorStore.enumerateSources(userId)`（Lucene 实现扫已提交快照按 (userId, sourceType, sourceId) 聚合块数；userId=null 全量是 §9 白名单登记的唯一跨租户枚举，只允许 ADMIN 鉴权后的 `OrphanIndexInspector` 调）；纯读不改 doc。
- `OrphanIndexInspector`：孤儿三因——`VECTOR_WITHOUT_CHUNK`（有向量无 knowledge_chunk 行）、`INDEX_ANONYMOUS`（userId=null 历史/离线块）、`SOURCE_ID_NOT_NUMERIC`（sourceId 无法映射 Long 行键）。存在性校验是分区表查询：逐来源先 `TenantContext.set(块.userId)` 后开 Session（方法本身不加 @Transactional，每次 repo 调用独立事务，用完恢复上下文），谓词带 userId + @TenantId 自动分区。
- 端点（api.md §7）：`GET /api/admin/index/orphans?tenantId=`（不带=全租户）、`POST /api/admin/index/delete-by-source`（显式 tenantId，删前复验；无此来源 404、健康来源 409）。**warn-first：系统永不自动删，只报告 + 等人审单点回收**；删除走 AND 语义 `deleteBySource`。
- **业务删除联动留 F4**：将来「删除资料」接口上线时，必须同事务先后（或补偿）调 `KnowledgeChunkRepository.deleteByUserIdAndSourceTypeAndSourceId`（派生 delete 走 SQM 带分区谓词，禁用裸 delete(entity)）与 `VectorStore.deleteBySource`；ADMIN 巡检是兜底而非主路径。

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

## 9. R2-T0 多租户模型（Hibernate `@TenantId` 分区）

> 结论：SQL 侧一个账号就是一个租户（**MVP：tenantId 与 userId 同值 Long**，但是独立列 `tenant_id` + 独立上下文 `TenantContext`，不为同值就偷懒复用——为 B2B 多 seat 预留唯一分离点）。不建 tenant 表、不做 user↔tenant 层级；未来 question_bank 等系统共享表不分区。

**分区实体（13 个，全部加 `@TenantId @Column(name="tenant_id") Long tenantId`，NOT NULL）**：
`jd_profile`、`resume_version`、`note`、`project_doc`、`real_interview_record`、`interview_session`、`interview_turn`、`critic_report`、`weak_point`、`knowledge_chunk`、`quota`、`user_api_key`（以上 user_id 为外键）、`llm_usage_log`（裸 `user_id` 列）。**`app_user` 本身是租户表，不分区**。

**Hibernate 机制（discriminator 分区）**
- persist 时由 Hibernate 按当前租户**自动回填** tenant_id，业务代码不手动赋值；SELECT/UPDATE/DELETE（含 `@Query` JPQL 批量语句、派生 delete/count/exists）自动追加 `tenant_id=?` 谓词。三条 Quota 批量 UPDATE 不手写租户条件（spike 实证：跨租户调用 affected=0，同租户正常生效；个别派生查询会出现重复谓词 `tenant_id=? and tenant_id=?`，无害）。
- 手写 user 谓词的方法（`findByUser`/`findByIdAndUserId`/`countByUserId` 等）**全部保留**：自动租户谓词 + 显式 user 谓词双保险，不列入收敛清单。
- **【批 1 spike 实证缺口 1】`em.find`/裸 `findById(id)` 不带租户谓词**（hibernate-core 6.5.3，按 id 直取不走 SQM）：对 13 张分区表**禁止**使用 `JpaRepository.findById/getById/getReferenceById/findAllById/existsById/deleteById` 与 `EntityManager.find`；按 id 取数一律走 `findByIdAndUserId`/`existsByIdAndUserId` 这类 JPQL 作用域方法（代码审查清单项）。现状生产代码已全部符合，User（不分区）唯一例外。
- **【批 1 spike 实证缺口 2】租户标识在 Session/事务开启时只解析一次**：必须**先 set TenantContext、后开事务**；在已开启的事务内 set 不生效。新线程/异步线程不继承 ThreadLocal，必须显式 carry（当前无 @Async；批 3 起若 broker 异步线程触达检索，交付线程必须显式 carry）。
- **【批 2 实证缺口 3】`REQUIRES_NEW` 仅当当前存在活动事务时才挂起旧 Session 并新建物理 Session；无活动事务时直接复用 OSIV 在请求 filter/interceptor 阶段已绑定的物理 Session**（其租户在进入 controller 前已固定）。即「先 set 新租户、再调 REQUIRES_NEW 方法」并不能保证新 Session——必须保证调用栈上有一个活动事务被挂起。注册链路初版因此踩坑：controller 无 `@Transactional` 时 grant 的 REQUIRES_NEW 复用了 filter 阶段以旧租户创建的 Session，ThreadLocal 已是新用户但 `session.getTenantIdentifier()` 仍是旧值。
- **启动期门控**：Spring Data 仓储 Bean 创建期（派生查询构造校验）Hibernate 就会回调 resolver，此时无请求上下文。strict 只在 `ApplicationReadyEvent` 之后 fail-closed；就绪前一律回退兜底 0L（就绪后无真实流量，无放行窗口）。

**TenantContext 与 root 哨兵**
- `security/TenantContext`：ThreadLocal（tenantId + root 标志），请求入口 set、finally clear；`runAsRoot(Runnable)` 是唯一特权入口，只允许 ADMIN 鉴权后的 service 调用。
- **`ROOT_TENANT_ID = -1L`**（禁止用 0L）：`CurrentTenantIdentifierResolver<Long>.isRoot(T)` 仅对精确的 `-1L` 返回 true；`isRoot(0L)`/`isRoot(null)` 必须 false。root 哨兵只用于跨租户枚举/运维，**永不落库**（root 块内禁止 persist）。
- **root 块硬规则**：块内每条 JPQL/Query 的 WHERE 必须显式含 `tenantId=:tid` 或 `userId=:uid` 范围谓词；唯一例外是「操作本身即跨租户枚举」的查询（C3 孤儿检测 distinct 枚举），须白名单登记 + ADMIN 鉴权 + 人审。

**strict 与兜底租户**
- strict（prod profile，`app.tenant.strict=true`）：上下文缺失时 resolver **直接抛异常 fail-closed**，不依赖 Hibernate 对 null 的默认行为。
- 非 strict（dev/test）：上下文缺失回退固定兜底租户 **0L——是受过滤的普通租户**（查询仍带 `tenant_id=0`），不是 root。

**入口传播**：JWT 新增 `tid` claim（=userId），`JwtAuthFilter` 同时填 UserContext/TenantContext；无 tid 的旧 token 取 `sub` 派生（签名有效即可信）。2026-10-20 修复：页面/静态资源、健康检查及 register/login 等既有公开路径在过滤器内先显式置系统分区 0L，再进入 MVC/OSIV，finally 清理；只在 controller 内 set 对 OSIV 创建 Session 已太晚。0L 不是 root，不代表已认证；受保护 API 仍必须有效 JWT，缺上下文持久化仍 fail-closed。register/login 保留 controller 内的系统租户约束与 finally 清理；注册编排收口 `web/RegistrationService`（规约见下）。REQUIRES_NEW 同线程传播 ThreadLocal；当前无 @Async；SSE `/events` 只订阅内存事件不查库（SseBroker 不得持有任何 Repository/EntityManager/JdbcTemplate 字段）。

**白名单无身份路径事务规约（批 2 锁定，register/未来任何匿名写路径照此办理）**
1. controller 方法首行 `TenantContext.set(FALLBACK_TENANT_ID /*0L*/)`，finally `clear()`；方法本身**不加** `@Transactional`。
2. service 壳方法 `@Transactional`（普通 REQUIRED）：首个 Session 由此在 0L 下创建，只做查重等不分区表访问。
3. 写 `app_user`（不分区）走 `UserLifecycleService.insertUser`（**REQUIRES_NEW 独立提交**）——必须先独立提交，后续 quota 事务的外键约束才能看到这行用户。
4. `TenantContext.set(新用户id)` 后调 `QuotaService.grantForNewUser`（**REQUIRES_NEW**）：壳事务是活动事务，挂起后才真的新建物理 Session，quota 行才落本人租户（缺口 3）。
5. 赠额失败：以 `UserLifecycleService.deleteUser`（REQUIRES_NEW）补偿删用户后 rethrow，禁止留无额度孤儿账号；finally 把上下文恢复 0L。
6. 登录同理入口置 0L：只查不分区的 `app_user`，strict 下也不会因 Session 在空上下文创建而 fail-closed。
- **测试对应物**：此类真实提交的 E2E 测试（如 `RegisterQuotaTenantIntegrationTest`、批 2 改造后的 `UserIsolationTest`）**不得**类级 `@Transactional`——测试事务在方法开始前就以 TestTenantContextListener 的 testuser 租户固定 Session，会以错误租户读到独立提交的本人数据；无类事务时每个 MockMvc 请求的 OSIV Session 随 JWT tid 创建，才是生产口径。注册名须 UUID 化防跨方法撞名。

**禁令**：新增 native SQL / JdbcTemplate 查询必须人审租户条件（现存仅探活 `select 1` 与 dev 启动四表 COUNT，不触租户数据）。向量侧隔离不走本节机制，沿用 §7 的 Lucene userId pre-filter（生产 strict 下 RagService 对 null userId fail-closed）。

**【批 6 实证】Repository 全量审计 + 渗透矩阵**
- 全仓 grep `findById/getById/em.find/getReferenceById/findAllById`：分区表零裸取，唯一命中是 `CurrentUserService.userRepo.findById`（app_user 不分区，合法）。
- 清除 11 个**无显式 user 谓词且零调用**的派生死方法（`@TenantId` 虽自动加分区谓词，但无调用方的全局作用域方法属于未来误用隐患）：WeakPoint 4 个、InterviewSession 3 个（历史列表实际走 `findByUserOrderByIdDesc`）、CriticReport `findBySessionIdIn`、ResumeVersion `findByIsActiveTrue`、KnowledgeChunk 2 个。`CriticReportRepository.findBySessionId` 保留（WeakPointController 唯一调用方，源方法注释登记 @TenantId 兜底）。
- `web/TenantIsolationPenetrationTest`（jwt=true、A/B 双真实账号、无类级 @Transactional + UUID 名 + DirtiesContext）8 个用例实证：资料/JD/会话历史/错题本列表跨租户为空；他人 id 的 start/激活/改文档/status/report/answer/end/events（SSE 走 query jwt）/weak-point 详情全 404；usage/quota/settings/api-key 只随 JWT 本人；admin 端点普通用户 403；无 token 全 401；向量路径用确定性 Embedder 证明 B 的出题 prompt 零泄露 A 的机密块、A 本人阳性召回 + answer/end/status 全链路 200。

**Schema**：V4（Flyway，仅 prod/mysql 启用）对 13 表执行「加可空 tenant_id → 回填 tenant_id=user_id → 改 NOT NULL → 加索引」；H2 create-drop 由实体自动建列，不读脚本。

## 10. 双陪考搭档（2026-10-19）

- 陪考小暖（既有 CompanionService）负责情绪陪伴；通考小师（StudyCoachService）负责知识答疑与学习方向。两个角色的内存上下文完全分离，都以请求入口解析的本人 userId 为键。
- 小暖 2026-10-19 初步优化：从强制一两句改为按需要自然展开，先回应具体处境，尊重只倾诉、不想听建议的意愿；求办法时给一个可选择的小步骤。报喜时回应已说出的进步、不补写经历。仍为短期 8 次成功问答，不自动读取档案。空模型输出抛 LlmException，前端保留输入；不写入假安慰或失败历史。既有关键词护栏保留，安全支持文本不包含未经核实的热线号码。
- 2026-10-24 两个角色均最多保留最近 8 次成功问答并限制历史 20000 字符，闲置 30 分钟按访问清理；不将原始聊天写入资料库或错题本，不自动读取全部报告。前端支持选定片段到小师草稿。
- 小师模型调用统一走 LlmPort → LlmService（FAST 非思考）；保留路由、计费及 402/409/504 语义。失败不提交历史，同账号并发请求用 in-flight guard 拒绝；重置解除旧上下文，迟到响应不重新挂回。
- UI 为两个角色提供独立入口、自我介绍和返回原页面；同页切换保留各自草稿，账号退出统一销毁。封面定位为心态陪伴、知识答疑、模拟练习、成长复盘的全流程支持，不承诺自动提醒或永久记忆。
- 当前无支付接入：402 与设置/弹窗引导联系内测工作人员微信 13481264947 补额，ADMIN 手工增加额度接口不变。后续质量工作见 companion-and-interview-quality-backlog.md。

### 10.1 可选简短画像与流式（2026-10-24）

- `BuddyChatRuntime` 共享有界执行机制，按 `(userId,mode)` 隔离；保留本次“不测/只倾诉”意愿，重置不改另一角色或长期画像。`LlmPort.streamChat` → `LlmService` → DeepSeek/Qwen 原生 SSE，仍使用单例 OkHttp 派生客户端；首输出后禁止重试，断流已有消耗仍计费，失败不落成功历史。
- V6 新增 `buddy_memory`：id、tenant_id、唯一 user_id、enabled、revision、state_json LONGTEXT。属于原 13 张 owner 表以外的第 14 张分区表，继续 `@TenantId` + 显式 userId 查询、禁裸 findById/em.find。owner 外键级联删除；prod 先备份，再由 Flyway 执行 V6，H2 create-drop 自动建表。
- 每个 owner 一行，JSON 有界至 16000 字符：最多 7 个白名单偏好字段（每值 300 字符），来源 AUTO/USER、时间、用户锁定状态，以及已删字段的阻止标记。不存原始聊天/情绪事件/模型推理链；模型候选以本次原句为依据，不读取其他用户或另一角色聊天。
- `BuddyMemoryService` 在校验 TenantContext 后以短事务操作；用户行锁协调首次启用与设置修改，refresh 作用域查到的实体避开 OSIV 陈旧缓存。设置 revision 与回写快照比较，保护关闭、删除与人工修改；用户可显式重新允许自动记录。
- 记忆默认关闭；关闭停止后续模型读取与自动整理，保留内容可见可删。共享目标仅用于两者备考背景，角色偏好按 mode 筛选。自动提取与正文同一次调用，内部尾部标记由流过滤器隐藏、从成功历史剔除。存储失败只跳过画像整理，不额外调用模型或伪报保存。
- 小师内置审核知识卡为项目资源，每次仅匹配少量相关卡并带适用版本/官方来源；不新增向量服务，不改变既有 Lucene 私有索引或 userId 预过滤。它们不构成“某公司高频”证据。


## 11. 试推广运营与反馈（2026-10-30）

- 新增4张owner表：operation_grant（补额账本）、product_feedback（每账号一份反馈及一次奖励标记）、product_event（白名单产品事件）、analytics_preference（可选前端分析/报告邀请状态）。均遵循@TenantId和显式owner谓词，owner表总数从14变18；V7迁移。
- ADMIN先鉴权，再通过OperationsTransactions切换root新Session执行受控账号枚举/聚合查询；写入前回到目标租户并新开事务，禁止root persist。额度增加和账本原子提交；用户行锁串行补额/审核，唯一requestId和反馈奖励关联防重复。
- 管理端不暴露简历、面试回答、陪聊原文、Key或JWT；只返回必要经营元数据。反馈正文为用户主动提交给开发者的内容，单独控制保留期限。
- 新赠送默认200积分；PLATFORM按真实token，自带DeepSeek按用量10%收服务当量，1积分仍为500内部token。旧余额与旧流水不重算。应扣dueTokens和实扣chargedTokens分别记录，余额不足时可减免最后调用超出部分。
- LlmUsageLog新增callId、sessionId、billingVersion、platformCostMicros、pricingVersion、status、attemptCount；V8允许旧行新增字段为空。调用ID去重不等于业务请求幂等，新模型调用继续计费。资料导入用量单列，无sessionId不伪造归因。
- platform_budget_day/platform_budget_reservation为2张全局平台运营金额表，不属于owner表；只存日期、金额和调用ID，无用户内容。独立事务预留在途最大估算，按北京时间日预算收口对话和向量化。未知失败不按零成本处理，崩溃预留首版保留待人工对账；估算不是厂商现金账单。
- 活跃=核心操作成功用户；漏斗按同一注册队列、各阶段去重用户；次日/7日回访只计算观察期完整的账号。前端报告查看等可选事件缺失不代表用户必未查看，需结合采集开关解释。
- 产品事件滚动保留90天；反馈内容180天、联系方式90天后清理，奖励标记保留。首版未建设长期匿名历史数据仓库，不能承诺90天以外的事件分析。
- 首批短问卷主动填写：体验阶段单选、具体经历必填、障碍多选（NONE/UNSURE互斥）、平台建议选填、后续联系自愿。审核后一次赠200积分；报告非模态邀请、账号页、积分不足可选入口。
- HTTP访问限制：注册10次/小时/IP、登录30次/分钟/IP、登录写操作60次/分钟/账号；同账号主要模型操作串行、10分钟租约。prod走Redis且不可用拒绝新工作，dev为有界单进程兜底。请求体最多1MiB、单资料20000字符、批量JD10条、资料总量100万字符；均可经app.launch.limits配置调整。前置代理可信地址需按部署网络配置，禁止直接信任客户端X-Forwarded-For。
