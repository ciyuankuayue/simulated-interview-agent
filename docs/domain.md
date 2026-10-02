# 领域模型（domain.md）

> 唯一事实源：本文件定义实体、字段、关系、可见性边界。改结构先确认，再动代码。
> 原则：明细全在 DB 给 AI 推理用；用户看到的是「状态视图」和「Critic 报告」——**精简 = 表达精炼、抓核心、不啰嗦；关键内容（每答评价/纠正/知识补充/示范回答/整体注意事项/提醒）不省**。

## 1. 实体清单（13 个，2026-10-02 R1-3 新增 user_api_key / quota）

| # | 实体 | 用途 | 可见性 |
|---|------|------|--------|
| 0 | **app_user**（R1-1，R1-3 扩） | 用户账号：注册/登录 + JWT 鉴权，所有实体的 owner；含 key_mode / role | 不对外暴露 |
| 0a | **user_api_key**（R1-3） | 用户自带 DeepSeek key（BYOK），AES-GCM 密文落库 | 仅回 hasKey + last4 |
| 0b | **quota**（R1-3 最小骨架） | 用户服务点数：注册赠送、BYOK 每次调用扣 1 点 | 用户可看余额 |
| 1 | jd_profile | JD 原文 + 考点关键词 + 岗位类型 + 同类标签 | 列表（精简），按 user_id 隔离 |
| 2 | resume_version | 多版本简历原文 + 上传时间 | 可切换，按 user_id 隔离 |
| 3 | note | 笔记存档点（可加可看） | 可看，按 user_id 隔离 |
| 4 | project_doc | 项目文档详解（质疑官弹药库，外部上传） | 内部，按 user_id 隔离 |
| 5 | interview_session | 一次模拟（关联 JD + 角色 + 状态） | 状态，按 user_id 隔离 |
| 6 | interview_turn | 每轮：问题/回答/检索引用/判分/耗时/token | 内部，按 user_id 隔离 |
| 7 | real_interview_record | 上传的真实面试记录 | 内部，按 user_id 隔离 |
| 8 | weak_point | 错题本：知识点 + 错几次 + 复习阶段 + 下次复习时间 | 状态（精简），按 user_id 隔离 |
| 9 | critic_report | 报告：逐轮讲解 + 纠正 + 知识补充 + 示范回答 + 整体注意事项 + 提醒 + 匹配度 | 给用户看（精炼不省关键），按 user_id 隔离 |
| 10 | knowledge_chunk | 分块文本 + 来源（向量存 Redis/内存） | 内部，按 user_id 隔离 |

## 2. 字段定义

### app_user（R1-1 新增；R1-3 扩 key_mode / role）
- id (PK)
- username         # 唯一用户名，4-32 字符
- password_hash    # BCrypt 加密（10 轮）
- key_mode         # PLATFORM（平台 key，默认）/ BYOK（用户自带 DeepSeek key）
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

### quota（R1-3 新增，最小骨架；R2 扩展 token 计量）
- id (PK)
- user_id (FK UNIQUE, NOT NULL)
- total_points      # 总额度（注册赠送，配置 app.quota.signup-bonus，默认 1000）
- used_points       # 已用点数
- created_at / updated_at

> R1-3 计量规则：**PLATFORM 模式不扣点**（平台 key 的按 token 计费在 R2-1/R2-2 做）；**BYOK 模式每次 DeepSeek 调用（追问/判分/Critic 各一次）调用前扣 1 点服务费**，余额不足 → 402 QUOTA_EXHAUSTED，不发起调用、不超发（条件 UPDATE `used_points < total_points` 保证）。R2 在此表上扩展 DeepSeek usage 回写、@Version 乐观锁/Redis INCR、充值与 402 降级引导。

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
- roles            # 启用的角色数组，如 ["技术深挖官"] 或 ["技术深挖官","项目质疑官"]（支持部分启用；Critic 始终在）
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
- score_json         # 判分 {score, breakdown, missed_points, followup}（内部用，决定是否追问；不向用户全量展示）
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
- per_turn_review    # JSON 数组，每轮：{ question, score, explanation(逐轮讲解), correction(纠正), knowledge_supplement(知识补充), exemplar_answer(示范回答) }
- collapsed_points   # 答崩点汇总
- expression_issues  # 表述问题 + 精简答案指导
- overall_notes      # 整体注意事项
- reminders          # 必要提醒面试者（如"你说用了 Redis，但没说 TTL 为什么这么设"）
- match_score        # 匹配度
- created_at

### knowledge_chunk
- id (PK)
- **user_id (FK, NOT NULL)**  # R1-1
- source_type       # resume / note / project_doc / real_record
- source_id         # 来源实体 id
- chunk_text        # 分块文本
- embedding         # 向量（通义 qwen3.7-text-embedding-flash，存 Redis/内存）
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

4 维度 × 0-2 分 = 0-8：
- 正确性 / 完整性 / 深度 / 实证
映射：8=S（不追问）｜6-7=A（1 层 why）｜4-5=B（2 层 why）｜0-3=C/D（记错题本）
追问触发：score<6 → ≥1 层；score<4 → 2 层（封顶）
判分模型输出结构化 JSON `{score, breakdown, missed_points, followup}`，供 Critic 汇总。

## 4.1 实时行为 vs 报告行为（关键区分）

- **实时（技术深挖官 / 项目质疑官）**：用户答题后只做两件事之一——「追问（≤2 层 why）」或「一句简单纠正」。**不展开讲解、不给知识补充、不给示范回答**。
- 判分（4 维）实时仍算，但仅用于决定是否追问；不向用户展示 breakdown 全量。
- **报告（Critic）**：对每轮做评分 + 逐轮讲解 + 纠正 + 知识补充 + 示范回答；汇总答崩点、表述问题、整体注意事项、必要提醒；答崩点入 weak_point。

## 5. 间隔重复（weak_point，方案 A 固定阶梯）

- review_stage ∈ {0,1,2,3}，对应间隔天数 [1,3,7,15]
- `next_review_at = created_at/answered_at + INTERVAL [1,3,7,15][review_stage] DAY`
- 答错（再次答崩）：review_stage = min(stage+1, 3)，wrong_count+1，重排 next_review_at
- 答对（该知识点本轮 score≥6）：从 weak_point 移除（或标记 resolved）
- MVP 不做 Leitner / SM-2，跑通后再评估

实现（T8，2026-09-18）：`memory/SpacedRepetition.java` 排期 + `memory/WeakPointMemoryService.java` 唯一入口
`onTurnScored(jd, knowledgePoint, score, status, now)`。知识点 key 用**判分模型输出的 `knowledge_point` 短标签**
（不用 missed_points——措辞每轮都漂，会把同一知识点打散成多条），匹配前做归一化：去标点空白 → 去中文虚词 → 小写，
再精确相等或包含匹配（较短一方 ≥4 字）。

## 6. 边界（MVP，按 §6）

- 输入：JD 文本 + 你的 md 文档（简历 / 笔记 / 项目文档 / 真实面试记录）
- 向量：先内存手写余弦（L3 对比点），不引向量库
- 角色：支持部分启用（可只开技术深挖官）；Critic 始终在
- 用户绝不接触 interview_turn / project_doc / real_interview_record / knowledge_chunk 原始明细
