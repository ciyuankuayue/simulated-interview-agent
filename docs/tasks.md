# 迭代任务拆解（MVP 后）

> 配套：`../AGENTS.md`（规则）、`docs/handoff.md`（进度）、`docs/domain.md`、`docs/api.md`。
> 方法论依据：vibe-coding 实战手册第 17 章（MVP 后的有序迭代）。
> 每个 Task 完成标准：**目标达成 + 验收逐条过 + 全量 `mvnw test` 绿 + 真实启动冒烟（`/api/health/deps` ok）**。
> 执行纪律：开 `feature/xxx` 分支；大改前打检查点 tag；NEVER 自动 commit，人审 diff 后提交。

## 推荐落地顺序（为什么这么排）

1. **R1 多用户 + 部署**（第一优先，地基）—— 没有它多用户与额度都无从谈起；且 `InMemoryVectorStore` 内存向量库必须先换持久化，否则一部署即崩。
2. **R2 账号 + 大模型额度**（依附 R1）—— 复用 R1 的账号体系与 MySQL。
3. **R5 测试加固**（与地基同步）—— 地基阶段就把测试网织密，后面才稳。
4. **R4 向量库迁移**（依附部署）—— 只换存储后端，检索算法（余弦 / HNSW）保留；不引 LangChain。
5. **R7 软性面试官**（低成本高回报，可穿插）—— 纯角色 / prompt 编排。
6. **R3 多模型**（非阻塞，后做）—— 第一个加通义千问（已打通 DashScope embedding 通路）。
7. **R6 语音 / 视频**（最高难度，最后做）—— 先语音后视频。

---

## R1 多用户 + 部署到服务器

目标：从"单机本地、数据全局共享"变成"多账号、数据隔离、可公网访问"。

- [~] **R1-1 引入 User 实体与认证**（Trea 已完成，待人终审）
  - 目标：新增 `User` 实体（id / username / passwordHash / createdAt），注册 / 登录 / 登出接口，JWT 或 session 鉴权；所有资料 / 会话 / 报告按 `user_id` 隔离。
  - 验收：`POST /api/auth/register|login` 返回令牌；未带令牌访问业务接口 → 401；A 用户看不到 B 用户的数据（集成测试断言）。
  - 实测：2026-09-29 全量 `mvnw test` **59 tests, 0 failures, 0 errors, 0 skipped**，`UserIsolationTest` 覆盖 401/409/隔离/跨用户 404/同用户全链路。
  - 估时：1–1.5 周　依赖：无　难度：高
- [ ] **R1-1.5 前端接入 JWT 鉴权（补 R1-1 缺口，已立项 2026-09-29）**
  - 目标：R1-1 后端鉴权 + 隔离已完成，但前端 `app.js` 完全未接 JWT（无登录 UI、不存 token、`api()` 不带 `Authorization`、SSE 只拼 `vaultToken`），浏览器当前全线 401、不可用。本任务补齐前端接线，使浏览器端到端可用。
  - 验收：
    1. **登录/注册必须做成独立模态弹窗**：页面加载时若 `state.token` 为空，立即显示居中登录弹窗（参考抖音登录弹窗），背后三栏主面板**不可见且不可操作**；登录成功后弹窗关闭、显示主应用。
    2. 登录弹窗内提供"登录 / 注册"两个 Tab 切换；字段：用户名、密码；密码支持显示/隐藏切换；点击按钮后有明确的 loading、成功、失败提示（错误信息用人话）。
    3. 登录成功把 JWT 存入 `state.token`（**内存变量，不落 localStorage**——符合 Vault 安全约定，刷新即失效）；关闭弹窗并重新加载业务数据。
    4. 登录成功后，在页面显著位置（建议右上角）显示当前用户名/昵称 + 登出按钮；登出清空 `state.token` 和 `state.vaultToken` 并回到登录弹窗。
    5. `api()` 统一附加 `Authorization: Bearer <token>`；无 token 时请求自动跳登录面板；收到 401（token 失效/未登录）自动清 `state.token` 并显示登录弹窗。
    6. SSE 的 `EventSource` URL 拼 `?jwt=<token>`（EventSource 不能设头，只能走 query）；密码模式下同时保留 `&vaultToken=<vt>`。
    7. 浏览器端到端：登录后 `start/answer/sessions/end/report` 全链路 200，SSE 能持续收到面试官消息流。
    8. 密码模式双令牌叠加验证：unlock 后登录 → 业务接口 200；未 unlock 即便带 JWT 也 401。
  - 估时：3–5 天　依赖：R1-1　难度：中
- [ ] **R1-2 会话 / 资料 / 报告加 user_id 并迁移**
  - 目标：`InterviewSession / JdProfile / ResumeVersion / Note / ProjectDoc / RealInterviewRecord / CriticReport / WeakPoint / KnowledgeChunk` 全部挂 `user_id`；加 DB 索引；写迁移脚本（上线前 `ddl-auto:none`）。
  - 验收：旧数据无 `user_id` 的兼容处理；单用户查询自动带 `user_id` 过滤。
  - 估时：3–5 天　依赖：R1-1　难度：中
- [x] **R1-3 双密钥模式：平台额度 / BYOK 自带 Key（2026-10-02 完成，待人审，未 commit）**
  - 目标（用户拍板的双形态）：用户可选「平台额度」（平台 env key，R1-3 不扣点，R2 做 token 计费）或「自带 Key」（用户 DeepSeek key，AES-GCM 加密入库，每次调用扣 1 服务点，余额不足 → 402）；embedding 永远走平台 DashScope key；首个注册账号 = ADMIN；注册赠 1000 点（`app.quota.signup-bonus`）。
  - 交付：删除 T11/T12 旧页面保险箱+密码门禁全套（KeyVault/VaultAccessFilter/KeySetupController/X-Vault-Token，减 16 个旧测试）；新增 `security/KeyResolver`+`UserKeyCipher`、`billing/QuotaService`（条件更新防超发）、`UserApiKey`/`Quota` 实体、`/api/user/settings|api-key|quota`（验活 GET /models：401→400、不可达→502，无效不落库，只回 last4）；前端「模型调用方式」卡片（单选切换/录入验活/额度/删除回退/402 引导）。
  - 关键修复：**402/409 穿透全部 LLM 降级边界**（MaterialController.parseJd、InterviewOrchestrator 判分/追问/出题 3 处、CriticService），否则点数耗尽被静默降级（E2E 真抓到 POST /jd 返回空 keywords）。
  - 验收：`.\mvnw.cmd -B test` **62 tests, 0 failures, 0 errors**（新增 4 个穿透回归：JdQuotaPropagationTest + 编排层 3 例）；真机 E2E（bonus=0）：BYOK 余额 0 调 `/api/jd` → **HTTP 402 QUOTA_EXHAUSTED + 切回平台引导**；bonus=1000 全链路（赠点/默认 PLATFORM/409/假 key 400/真 key 验活落库/切 BYOK 扣 1 点/切回不扣/删除强制回退）；浏览器验收单选展开、sk- 校验、绿色切换提示全通过。
  - 估时：2–3 天　依赖：R1-1　难度：中
- [ ] **R1-4 部署形态（Docker + 反向代理 + 进程守护）**
  - 目标：`Dockerfile` 打镜像；`docker-compose` 加应用服务；nginx 反代 + HTTPS；systemd / 容器编排做进程守护；CI（GitHub Actions）跑测试 + 构建。
  - 验收：`mysql` profile 真机起全栈（MySQL + Redis + 应用），公网域名 HTTPS 可达，`/api/health/deps` 两 ok。
  - 估时：1 周　依赖：R1-1/2/3　难度：高

## R2 每用户账号 + 大模型使用额度

目标：每个用户有独立账号，调用大模型按 token 计费、超限拒绝。

- [x] **R2-1 额度模型与配额表**（2026-10-03 完成，实现口径见下方「R2 实现备注」）
  - 目标：新增 `Quota` 实体（user_id / total_tokens / used_tokens / reset_at / plan）；首次注册给赠送额度；DeepSeek 响应 `usage` 字段回写消耗。
  - 验收：每次 LLM 调用后 `used_tokens` 增加；`usage` 缺失时按输入+输出估算兜底。
  - 估时：2–3 天　依赖：R1-1　难度：中
- [x] **R2-2 并发安全的额度扣减**（2026-10-03；条件 UPDATE 原子扣减，未用 @Version/Redis）
  - 目标：多请求同时扣减用 DB 行锁 / `@Version` 乐观锁 / Redis INCR；扣减失败整体回滚，不出现超发。
  - 验收：并发压测下 `used_tokens` 不超 `total_tokens`，无负额度（QuotaServiceTest 20 线程×10/总额 100，used 恰为 100）。
  - 估时：2–3 天　依赖：R2-1　难度：中
- [x] **R2-3 超限优雅降级**（2026-10-03；402 穿透所有降级边界 + 前端 tip 引导；充值页面按用户决策不做，ADMIN 用 POST /api/admin/quota 充值）
  - 目标：额度耗尽返回友好提示（含充值 / 申请入口），不抛 500；前端弹窗引导。
  - 验收：额度为 0 时调用 LLM → 返回 402 风格业务码 + 中文提示，非 500（PLATFORM/BYOK 双文案，真机 E2E 实测）。
  - 估时：1–2 天　依赖：R2-2　难度：低

> **R2 实现备注**（2026-10-03，替代任务书里的旧设想）：
> - 计量单位直接用 token（无 reset_at/plan，月度套餐不做）；注册赠送 `app.quota.signup-bonus-tokens`（默认 50 万）。
> - PLATFORM：DeepSeek 对话 + DashScope embedding 均预检 + 按厂商 `usage.total_tokens` 实扣（缺失按字符 1:1 估算，`app.quota.usage-estimate-enabled` 可关）；最后一次可透支并 clamp 到 total，之后 402。
> - BYOK：API 费用户自付，每次对话只扣固定服务费 `app.quota.byok-charge-per-call`（默认 1 token 当量）；embedding 恒走平台 key 按 token 扣。
> - 扣费 + `llm_usage_log` 流水走 REQUIRES_NEW 独立事务（业务回滚不退款）。
> - 83 tests 绿（含并发压测、402 穿透、embedding 计量、403/404/400）；真机 E2E 实测 JD 181 token、embedding 58/24 token、BYOK 165 token 仅扣 1、充值恢复。

## R3 增加其他大模型（DeepSeek 之外）

目标：用户可选多家厂商模型；第一个加通义千问。

- [ ] **R3-1 抽象 ModelProvider + 模型路由**
  - 目标：`LlmProperties` 当前只绑 `deepseek.*`；抽 `ModelProvider` 接口（`chat` / `embed`），注册多个实现，按用户选择路由；`LlmPort` 已有雏形可复用。
  - 验收：配置切换模型后，追问 / 判分走对应 provider；单测覆盖路由分支。
  - 估时：3–4 天　依赖：R1-1　难度：中
- [ ] **R3-2 接入通义千问（Qwen）**
  - 目标：复用已打通的 DashScope embedding 通路，加 Qwen 文本生成；处理与 DeepSeek 的 prompt / 思考模式差异。
  - 验收：Qwen 跑通一整场（start→answer→report），判分 JSON 可解析。
  - 估时：2–3 天　依赖：R3-1　难度：中
- [ ] **R3-3 embedding 维度隔离 / 重算**
  - 目标：不同厂商 embedding 维度不同（如 qwen 1024 vs openai 1536）；向量按模型分桶或重算，避免混用导致召回错乱。
  - 验收：换 embedding 模型后检索 top-k 仍正确（回归 `L3ComparisonRealEmbeddingTest` 思路）。
  - 估时：2–3 天　依赖：R4-1　难度：中

## R4 RAG 存储后端替换 + 是否引入 LangChain

目标：把内存向量库换成可持久化、可共享的存储；决定是否引 LangChain。

- [x] **R4-1 选型并替换向量存储后端（2026-10-03 完成，分支 `feature/R4-vector-persistence`，92 tests 全绿 + 真机验证，未 commit 待人审）**
  - 目标：当前 `InMemoryVectorStore` + 手写余弦 + 纯内存，重启即丢、无法多实例。最省事 = **Redis vector**（已有 Redis）或 **落盘 Lucene HNSW**（纯 Java，已在 test 依赖）；如需 Postgres 才引 pgvector。检索算法（余弦 / HNSW）保留，只换存储。
  - 落地：选 **Lucene 9.12.3 FSDirectory**（`rag/LuceneVectorStore`，默认目录 `data/rag-index`，env `APP_RAG_INDEX_DIR` 可覆盖），新增 `rag/VectorStore` 抽象（`InMemoryVectorStore` 转为其离线实现）；KnowledgeChunk record 加 `userId`；登录用户 KNN 检索走 **4 参 pre-filter**（HNSW 遍历期按 userId 过滤，candidateK=max(topK*3,30)）；PUT 同 source 用 beginReplace/commit/rollback 批次事务 + AND 删除，不再产生重复块；`StartupIndexCleaner` 在 dev/H2 空库启动时清孤儿索引（mysql profile 关）。详见 `docs/domain.md` §7。
  - 验收（已达成）：✅ 同目录重开向量不丢（真机重启 vectorChunks=2 保留，单测 @TempDir 重开命中）；✅ userId 隔离（对抗式单测：u1 仅 1 块 cos0.8、u2 灌 60 个 cos≥0.85 块，u1 仍只命中自己；Spring 集成测试两用户互不可见）；✅ PUT project-doc 两次存活 doc 数不翻倍（真机 0→1→2→2→2）；✅ dev 空库启动 WARN 清空索引；✅ 全量 `.\mvnw.cmd -B test` 92 tests 全绿。多实例共享同一索引属单机 FSDirectory 限制（不支持多 writer），横向扩展再迁 Redis Stack/pgvector。
  - 估时：4–5 天　依赖：R1-2（user_id 隔离）　难度：中
- [x] **R4-2 关于 LangChain 的决策（结论：暂不引入，2026-10-03 随 R4-1 一并评估确认）**
  - 目标：主链路当前**无任何 LangChain 依赖**（pom 无 langchain4j；`experiment/` 包只是对比实验，未进生产）。手写 retriever 你完全可控、已跑通、易调试；引入 LangChain4j 会带来抽象层与版本耦合，对"稳健"目标反而增加不确定性。仅当某能力（document loader / chain 编排）能显著省事时才局部试用，不作为全局重构。
  - 验收：R4-1 在不引入 LangChain 的前提下完成；在 handoff 记一条"已评估、决定不引"的决策。→ **已记录于本节与 `docs/handoff.md` R4 小节：已评估，决定不引 LangChain4j。**
  - 估时：0（决策动作）　依赖：无　难度：低

## R5 系统稳健性 + 补测试开发知识

目标：把"运行不稳健"的体感，转成可执行的测试加固清单。

- [ ] **R5-1 集成测试（真实依赖）**
  - 目标：用 Testcontainers 起真实 MySQL / Redis（或复用 H2 profile），覆盖多用户隔离、配额扣减、RAG 入库-检索闭环。
  - 验收：新增集成测试套件全绿；CI 默认跑（无 key 时 LLM 相关自动 skip）。
  - 估时：3–4 天　依赖：R1 / R2　难度：中
- [ ] **R5-2 弹性测试（超时 / 重试 / 降级）**
  - 目标：LLM 层已有超时重试，补测试证明：超时 → 重试 → 仍失败 → 降级（不 500、出占位报告）；SSE 断线重连路径有测试。
  - 验收：注入故障的测试断言系统降级而非崩溃。
  - 估时：2–3 天　依赖：无　难度：中
- [ ] **R5-3 E2E 冒烟脚本化**
  - 目标：把浏览器 E1–E10 手工验收沉淀为可重复脚本（curl / Playwright），每次大改后跑一遍。
  - 验收：脚本覆盖首题不重复、追问 2 层封顶、报告四段齐全、降级、409 等核心路径。
  - 估时：3–4 天　依赖：R1　难度：中

## R6 语音 / 视频面试模拟

目标：在文本面试之外，支持语音（先）与视频（后）模态。

- [ ] **R6-1 语音：ASR 接入 + 文本面试链路打通**
  - 目标：前端录音 / 流式上传 → ASR（Whisper / 通义听悟 / 讯飞）转文本 → 接入现有文本面试；可选 TTS 回放。WebSocket 流式音频 + VAD。
  - 验收：说一句 → 转文本 → 面试官追问 → （可选）语音回复；端到端延迟可接受（< 2s 级）。
  - 估时：2–4 周　依赖：R1 / R2（额度打通 ASR/TTS 计费）　难度：高
- [ ] **R6-2 视频：摄像头采集 + 画面渲染**
  - 目标：在语音基础上加摄像头采集、画面渲染；可选表情 / 姿态分析。带宽与延迟陡增；存储与隐私合规。
  - 验收：视频面试一场可录可回看；隐私告知与数据留存策略落地。
  - 估时：+3–5 周　依赖：R6-1　难度：高

## R7 软性面试官（自我介绍 / 个性 / 压力面）

目标：当前 `Role` 只有技术深挖官 / 项目质疑官 / Critic，偏技术岗；新增"综合行为面试官"。

- [ ] **R7-1 新增行为面试官角色**
  - 目标：`Role` 枚举加 `BEHAVIOR_INTERVIEWER`（自我介绍追问 / 个性评估 / 压力面施压策略）；system prompt 设计 STAR 法则评分与压力面"追问施压"话术。
  - 验收：该角色可独立启用；产出行为维度评分，不与技术分混淆。
  - 估时：2–3 天　依赖：无　难度：低
- [ ] **R7-2 扩展 Critic 报告维度**
  - 目标：报告新增行为 / 表达 / 抗压维度（独立于现有技术 4 维），前端右栏可展示。
  - 验收：启用行为面试官的一场，报告含行为维度且结构可解析。
  - 估时：1–2 天　依赖：R7-1　难度：低

---

## 估时汇总（粗略，单人节奏）

| 需求 | 难度 | 周期 | 关键前置 |
|---|---|---|---|
| R1 多用户 + 部署 | 高 | 3–5 周 | — |
| R2 账号 + 额度 | 中 | 1–1.5 周 | R1 |
| R3 多模型 | 中 | 1–2 周 | R1 |
| R4 向量库迁移 | 中 | 1–1.5 周 | R1（隔离） |
| R5 测试加固 | 中 | 持续 + 专项 1–2 周 | 随地基 |
| R6 语音 / 视频 | 高 | 语音 2–4 周；视频 +3–5 周 | R1 / R2 |
| R7 软性面试官 | 低 | 3–5 天 | — |

> 经验法则：R1 是"地基中的地基"，建议单独成一个里程碑，跑通后再开 R2/R4；R5 不要等最后，地基阶段就同步织测试网。
