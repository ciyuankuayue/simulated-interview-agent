# API 契约（api.md）

> 对外契约唯一事实源。S4 契约优先产出。NEVER 未经确认修改本文件（改前先与用户确认）。
> 传输：JSON；流式用 SSE（text/event-stream）。本地默认 http://localhost:8080。
> 2026-10-21 用户确认内部 Agent 决策重构；本轮不新增 HTTP 请求/响应字段或 SSE 事件。下文更新新场与旧场的实际语义，内部模型、预算、V5迁移见 [运行时说明](agent-decision-runtime.md)。

## 1. 通用约定

- Base URL：`/api`
- 内容类型：请求/响应 `application/json; charset=utf-8`
- 鉴权（R1-1 多用户，R1-3 统一形态）：
  - **JWT 令牌**：`POST /api/auth/login` 返回 `{ token }`；所有业务接口（除 §2 的 auth 白名单）必须带 `Authorization: Bearer <token>`（HTTP 头）。SSE 的 EventSource 无法设头，允许 query 参数 `?jwt=<token>`（优先级：header > query）。
  - 缺失/失效/过期 → **401** `{code:"UNAUTHORIZED",message:"请先登录"}`。
  - 令牌结构：`{ sub: userId, username, tid: tenantId, exp: 24h }`（R2-T0 起含 `tid` 租户标识，MVP 与 userId 同值；无 `tid` 的旧令牌以 `sub` 派生，向后兼容），密钥配环境变量 `JWT_SECRET`（prod profile 缺失或不足 32 字节直接启动失败；dev/test 空则启动时自动生成临时密钥）。
  - **R1-3 起只有 JWT 一道门禁**：T11/T12 的页面保险箱 / `X-Vault-Token` / VaultAccessFilter 已整体删除。能不能调 LLM 由用户的 key 模式（PLATFORM 平台 key / BYOK 自带 key）与额度决定（见 §7），不再有第二枚令牌。
  - **auth 白名单**：`POST /api/auth/register`、`POST /api/auth/login` 不放令牌。
- 时间：ISO-8601 字符串
- 错误码：HTTP 状态码 + `{code, message}`
  - 400 参数错误 / 401 未登录或 JWT 失效 / 402 积分不足（PLATFORM/BYOK 余额不足，见 §7）/ 403 非 ADMIN 调管理接口（R2）/ 404 资源不存在 / 409 状态冲突（如会话已 DONE 再回答、用户名已注册、切 BYOK 但未存 key）/ 500 内部错误 / 504 LLM 超时（走降级）
- 环境探活（非业务）：`GET /api/health/deps` → `{ database:{ok}, redis:{ok}, deepseek:{source:"ENV",configured}, embedder:{ provider, vectorChunks, ping:{ok,dim} } }`。JWT 白名单（健康检查无身份）。R1-3 起 deepseek 只反映平台 key（env）是否配置；用户 BYOK key 不在此暴露。R4 起 `vectorChunks` 是 **Lucene 落盘索引的全局存活 doc 总数（跨用户、不含已逻辑删除待合并的旧 replace 版本，仅作粗监控，不是当前用户块数）**。

## 2. 认证（R1-1 新增）

### POST /api/auth/register
注册新用户。
- body: `{ "username": "abc", "password": "xxx" }`
- `username`：4-32 字符，唯一；已存在 → 409 `{code:"USERNAME_TAKEN"}`
- `password`：≥6 字符，BCrypt 加密存储
- resp: `{ "userId": 1, "username": "abc", "token": "eyJhbG..." }`（注册成功自动签发 JWT，免额外 login）
- **R2-T0 起注册恒为 role=USER**（「首个注册者=ADMIN」规则已删除——任何公开注册都拿不到管理员，杜绝抢注首个账号）；同时为该用户创建 quota 并赠送 100 积分（`app.quota.signup-bonus-tokens`，默认 50000 token 当量）；新用户 key_mode=PLATFORM。
- **ADMIN 的唯一产生方式：env 引导器**（不开放任何 HTTP 接口）。启动时配置 `app.security.bootstrap-admin.username` / `password`（env `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD`）：用户不存在则建 ADMIN（BCrypt + 赠额度）；已存在（含原 USER）则幂等升级为 ADMIN 并按配置更新密码、不重建额度；只配 username 不配 password 时首建生成随机密码并 WARN 回显一次；两项都不配则跳过（仅 WARN 指引，不影响启动）。
- HTTP 201 Created

### POST /api/auth/login
登录，换取 JWT。
- body: `{ "username": "abc", "password": "xxx" }`
- resp: `{ "userId": 1, "username": "abc", "token": "eyJhbG..." }`
- 用户名不存在或密码错 → **401** `{code:"INVALID_CREDENTIALS",message:"用户名或密码错误"}`

### POST /api/auth/logout
登出（客户端扔掉 token 即可；服务端无状态）。保留接口供前端调用，未来可扩展黑名单。
- body: 无
- resp: `{ "loggedOut": true }`

> JWT 有效 24 小时，密钥 `jwt.secret` 配在 `application.yml`（生产必须显式设，不可用默认）。刷新机制待 R1-1 后续迭代补充（当前过期需重新 login）。

## 3. 资料管理（RAG 索引源）

### POST /api/jd
上传/粘贴 JD（可批量，同类一起练）。
- body: `{ "rawText": "..." , "sameTypeTag": "后端-美团" }` 或 `{ "rawTexts": ["...","..."] }`
- resp: `[{ "jdId", "keywords":[...], "jobType", "sameTypeTag" }]`

### GET /api/jd
JD 列表（精简）：`[{ "jdId", "jobType", "sameTypeTag", "createdAt" }]`

### POST /api/resume
上传简历版本（md）。resp: `{ "resumeId", "createdAt" }`
### GET /api/resume
简历版本列表（下拉复用，最新在上由前端排序）：`[{ "resumeId", "createdAt", "isActive" }]`
### POST /api/resume/{resumeId}/activate
切换启用版本（下拉选中即调用）。resp: `{ "resumeId", "isActive": true }`

### POST /api/note
加笔记。body: `{ "content": "..." }` → resp: `{ "noteId" }`
### GET /api/note
笔记列表（可看）：`[{ "noteId", "content", "createdAt" }]`

### POST /api/project-doc
上传项目文档（**外部上传**：使用者将简历中提到的项目，单独写详解后上传；质疑官弹药库，建议必传；缺失则质疑官退化为仅基于简历入口提问）。body: `{ "title", "content" }` → resp: `{ "docId" }`
### GET /api/project-doc
项目文档列表（标题级）：`[{ "docId", "title", "updatedAt" }]`
### PUT /api/project-doc/{docId}
补充「现状」。body: `{ "content" }`（覆盖或追加，MVP 覆盖）

### POST /api/real-interview
上传真实面试记录。body: `{ "content" }` → resp: `{ "recordId" }`

## 4. 模拟面试（核心流程）

### POST /api/interview/start
开始一次模拟。
- body: `{ "jdId": "...", "roles": ["技术深挖官"] , "resumeId": 12(可选，下拉选中的简历版本 id), "maxQuestions": 8(可选，主问题上限，1-8，默认 8；不含追问) }`
  - `roles` 取值：`技术深挖官` / `项目质疑官` / `行为面试官`（至少勾一个；行为面试官出 STAR 行为题、不考技术知识点）。新 Agent 按考察目标从启用集合选角色，追问/澄清继承当前角色；旧 legacy 场仍按勾选顺序轮转。
  - 新场默认启用 Agent：开场保存本人 JD 与显式所选 resume 原文快照；未选简历为空，其他简历版本不参与本场。本人项目/笔记/真实面试记录按需检索并保存所取证据片段，未承诺全部材料开场冻结。旧 legacy 场 resumeId 仍仅留痕。
  - `maxQuestions` 是上限；Agent 得到回答后可判断提前结束，也可在当前主问题剩余两层预算内继续追问/澄清。
- resumeId 指向不存在的简历 → 404。
- resp: `{ "sessionId", "firstQuestion": "..." , "status": "QUESTIONING" }`
- 副作用：新场 Agent 依据 JD/所选简历制定有限考察计划、按需检索并选择启用角色产出首题；首轮不能直接结束。首题仍同步返回。关闭新场Agent开关或旧NULL会话采用原流程。

### GET /api/interview/sessions
历史会话（最近 20 场倒序，精简卡片，报告回看入口）：
`[{ "sessionId", "jdId", "jobType", "sameTypeTag", "roles", "status", "matchScore", "maxQuestions", "hasReport", "createdAt" }]`
- `hasReport=true` 才可调 report 回看；进行中/未 end 的场次为 false。

### POST /api/interview/{sessionId}/answer
提交本轮回答，触发判分及下一步决策（追问/澄清、换题或结束）。
- body: `{ "answer": "..." }`
- 结果经 SSE 推送（见 §5）；本接口返回 `{ "accepted": true }`。当前仍在本次请求完成编排后返回，未改为后台任务受理。
- 冲突：session 非 QUESTIONING → 409
- **自动结束**：Agent可根据考察情况选择END，或在题量/两层预算耗尽时结束；legacy按原题量规则自然结束。同批 SSE 直接推送 `report` + `done`，报告先于done，前端无需再调 `/end`。硬上限路径可带既有 `reason=MAX_QUESTIONS_REACHED`；Agent主动END与手动end均可不带reason。
- Agent下一步生成发生非业务失败时，经既有 `error(code=QUESTION_FAILED)` 提示，已提交回答和评分保留。提交**完全相同的原回答**可恢复出题，不再次判分；不同回答不能覆盖已保存内容。恢复仍可能新调用决策模型/检索并产生费用。402/409作为业务错误透传；answer不因这两类异常回滚已保存回答/评分，判分后下一决策受阻不丢失已付费评分。
- UI在QUESTION_FAILED后提供“继续生成下一步”，使用原回答调用同一answer接口，并提示新模型调用扣积分；也可选择结束查看已完成部分报告。未新增HTTP字段或恢复端点。

### GET /api/interview/{sessionId}/events  （SSE）
订阅流式事件。事件类型见 §5。前端连上后等待 question/score/followup/report/done。
- 前端在收到 `await_answer` 事件时，调用 §4 answer 接口提交；循环直到 `done`。

### POST /api/interview/{sessionId}/end
主动结束（中途打断/放弃；自然结束已不需要调它）。与自然结束共用同一出报告逻辑。
- **幂等**：已有 Critic 报告（含自然结束刚生成的）直接重放同一份，不重复调 LLM。
- answer/end使用同场guard及数据库会话行锁（超时hint=0）；单实例guard/H2竞争已测，多实例/MySQL NOWAIT未实测，各数据库锁异常类型可能不同。尚无请求ID/持久后台任务，不能推广为全部并发重试完整幂等；失败降级报告仍可能被缓存，报告失败单独重试未实现。
- resp: `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","track","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints":[...], "expressionIssues":[...], "overallNotes", "reminders", "behaviorReview" }`
  - `track`（R7）：`"tech"` / `"behavior"`，本轮判分赛道；老数据缺省按 `"tech"`。
  - 新生成报告按真实轮次去重并恢复权威 question/score/track；未评分的score保持null，不作为正常0分。未知个人事实/数字的示范用占位符，overallNotes说明覆盖与未评估范围；字段形状保持原样。
  - `behaviorReview`（R7）：本场含行为轮时为行为维度对象（形状见下），纯技术场/老报告为 `null`。

### GET /api/interview/{sessionId}/report
拉取 Critic 报告（精简版，给用户看）。
- resp: `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","track","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints":[...], "expressionIssues":[{"issue","guide"}], "overallNotes", "reminders", "behaviorReview", "createdAt" }`
- `behaviorReview` 形状（R7；逐轮四维 rubric 见 domain.md §4）：
  `{ "dimensions": {"STAR结构":0-2,"行动细节":0-2,"结果与反思":0-2,"表达与抗压":0-2}, "summary": "≤120字总评", "suggestions": ["≤4 条，每条≤40字"] }`；列值为 `null` 时整卡不渲染。

### GET /api/interview/{sessionId}/status
会话状态：`{ "sessionId", "status", "roles", "turnCount", "matchScore" }`

## 5. SSE 事件协议（GET /api/interview/{sessionId}/events）

每个事件 `event: <type>\ndata: <json>\n\n`：

| event | data | 含义 | 前端动作 |
|---|---|---|---|
| `question` | `{ "turnSeq", "question", "role" }` | 新一题（技术深挖官/项目质疑官/行为面试官） | 展示，等用户作答 |
| `score` | `{ "turnSeq", "score", "track"?: "tech"\|"behavior", "oneLineCorrection" }` | 本轮评分（失败时score=null；`track=behavior` 为 STAR 四维行为分；仅概要 + 一句纠正，讲解归报告） | 展示评分与一句纠正（行为轮加角标） |
| `followup` | `{ "turnSeq", "question", "layer" }` | 追问或澄清（layer 1/2，共用两层上限；继承所属主问题角色与赛道） | 展示，等作答 |
| `await_answer` | `{ "turnSeq" }` | 等待本轮回答 | 调 answer 接口 |
| `report` | `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","track","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints", "expressionIssues", "overallNotes", "reminders", "behaviorReview" }` | Critic 报告（R7：含行为轮时多一个 behaviorReview 行为维度块） | 展示报告 |
| `done` | `{ "sessionId", "reason"?: "MAX_QUESTIONS_REACHED" }` | 结束。硬上限路径可带既有reason；Agent选择END/手动end可不带。`report`保证先于`done` | 关闭流（前端兜底：未渲染report时补调已有结果重放的`/end`） |
| `error` | `{ "code", "message" }` | 异常（含 LLM 降级） | 提示 |

事件流示例：
```
question(turn1, 技术深挖官) → await_answer
  → [用户 answer]
score(turn1, 5/B) → followup(layer1) → await_answer
  → [用户 answer]
score(turn1, 7/A) → question(turn2) → await_answer ...
  → 全部题答完 → report → done
```

## 6. 错题本

### GET /api/weak-point
复习队列（未出队），按 next_review_at 升序：
`[{ "weakPointId", "knowledgePoint", "wrongCount", "reviewStage", "nextReviewAt", "status", "resolved", "createdAt", "lastSessionId", "lastTurnSeq" }]`

### GET /api/weak-point/due?now={ISO-8601,可选}
今日待复习：`next_review_at <= now` 且未出队（不传 `now` 取当前时间）。返回字段同上。

### GET /api/weak-point/{weakPointId}
单条详情（点开回看「最近一次答崩现场」）：
`{ ...列表字段, "resolved", "createdAt", "lastSessionId", "lastTurnSeq", "latest": { "sessionId", "turnSeq", "layer", "question", "answer", "score", "review": { "explanation", "correction", "knowledgeSupplement", "exemplarAnswer" } } | null }`
- `latest=null`：旧数据/无法定位（前端降级为只显示状）；`review=null`：该场还没出 Critic 报告。

## 7. 模型调用方式 / BYOK / 额度（R2 token 计量；R1-3 保险箱接口已移除）

> 平台 key 只从**环境变量**读（`DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY`）；页面保险箱、`/api/setup/**`、`X-Vault-Token` 全部移除。
> 每个登录用户有两个相互**独立**的设置维度：
> - **调用方式 `keyMode`**：`PLATFORM`（默认，走平台 key）/ `BYOK`（走用户自己加密存储的 DeepSeek key，每次模型调用扣固定服务费，默认 1 积分 = 500 token 当量，余额不足→**402**）。一轮面试可包含出题、判分等多次模型调用。
> - **对话模型厂商 `chatProvider`（R3-2 新增）**：`DEEPSEEK`（默认）/ `QWEN`（通义千问，DashScope OpenAI 兼容模式）。
> - 两维正交，共 4 种组合。**QWEN 恒走平台 `DASHSCOPE_API_KEY` 并按 PLATFORM 实扣 token**——即使 keyMode=BYOK：BYOK 自带 key 体系只支持 DeepSeek，选 QWEN 时不读用户 key、不扣每次服务费（API 费由平台额度承担）。
> - PLATFORM 下 DeepSeek 对话、Qwen 对话、DashScope embedding **均按真实 token 计费**——调用前预检余额，调用后按厂商 `usage.total_tokens` 实扣（缺失按字符估算兜底）。
> - 页面统一显示**积分**，固定 **1 积分 = 500 token 当量**（沿用原换算）；数据库和 API 的 `*Tokens` 字段仍保留 token 单位，契约不变。积分最多显示 3 位小数，1 token = 0.002 积分，不整点取整。新用户赠送 `app.quota.signup-bonus-tokens`（默认 50000，即 100 积分）；已有用户总额、已用额与历史流水不重置、不迁移。
> - 用户自带 key 仅支持 DeepSeek；任何接口都不回传 key 明文。
> - Agent工具循环可在一次决策中多次调用模型；每次均经现有路由和计费。DeepSeek BYOK每次模型调用扣服务费，资料搜索的embedding恒平台按token扣；恢复生成不是零费用重放，已存报告的直接重放不新调用模型。

### GET /api/user/settings
`{ "keyMode": "PLATFORM|BYOK", "chatProvider": "DEEPSEEK|QWEN" }`

### PUT /api/user/settings
更新设置。body 两个字段都**可选**、各自独立更新（前端单选各自只 PUT 自己那项），但至少传一个：
`{ "keyMode": "BYOK" }` 或 `{ "chatProvider": "QWEN" }`，也可同传。
- 切到 `BYOK` 但该用户未保存 key → **409** `{code:"KEY_NOT_CONFIGURED",message:"请先保存你的 API Key"}`。
- 非法 `keyMode` → **400** `{code:"INVALID_KEY_MODE"}`；非法 `chatProvider` → **400** `{code:"INVALID_CHAT_PROVIDER"}`；两字段都空/缺失 → **400** `{code:"INVALID_SETTINGS"}`。
- 成功 resp: 同 GET（返回两个字段的最新值）。

### GET /api/user/api-key
`{ "hasKey": bool, "last4": "abcd"|null }`——仅末 4 位，永不回显完整明文。

### PUT /api/user/api-key
保存（首次）/轮换自带 key。body: `{ "apiKey": "sk-…" }`
- 服务端先做一次极简 DeepSeek 调用验活（失败 → **402 之外的 400/502**：key 无效返 **400** `{code:"INVALID_API_KEY"}`；网络/5xx 返 **502** `{code:"PROVIDER_UNREACHABLE"}`），验活通过才加密落库。
- 落库：AES-256-GCM（主密钥 `APP_DATA_KEY`），只存密文 + last4。幂等覆盖=轮换。
- resp: `{ "hasKey": true, "last4": "abcd" }`

### DELETE /api/user/api-key
删除自带 key；若当前 key_mode=BYOK，**自动回退 PLATFORM**。resp: `{ "hasKey": false, "last4": null, "keyMode": "PLATFORM" }`

### GET /api/user/quota
`{ "totalTokens": 50000, "usedTokens": 1320, "remainingTokens": 48680 }`
- 上例页面显示：总额 100 积分，已用 2.64 积分，剩余 97.36 积分。
- PLATFORM 每次 DeepSeek/Qwen 对话与 embedding 调用后按 usage 实增；BYOK 每次 **DeepSeek** 对话 +`byok-charge-per-call`（embedding 与 Qwen 对话按真实 token）。

### GET /api/user/usage?limit=50
本人最近用量流水（limit 默认/上限 50），按时间倒序：
`[{ "id", "scene", "keyMode", "model", "promptTokens", "completionTokens", "totalTokens", "chargedTokens", "createdAt" }]`
- scene：`JD_PARSE` / `FOLLOWUP` / `SCORE` / `CRITIC` / `CHAT` / `EMBED_INGEST` / `EMBED_QUERY`。
- `model` 为「厂商:模型名」流水标识（R3-1 起）：DeepSeek = `deepseek:<model>`（如 `deepseek:deepseek-flash`），Qwen = `qwen:<model>`（如 `qwen:qwen3.8-flash` / `qwen:qwen3.7-plus`），embedding = `dashscope:<model>`。**历史数据兼容**：R3-1 前写入的 DeepSeek 行无 `deepseek:` 前缀（裸模型名如 `deepseek-flash`），读取端原样回显、不做解析聚合，前端/统计方需容忍两种形态。
- `keyMode` 记录本次调用实际计费方式：BYOK 用户选 QWEN 时，对话流水也是 `keyMode=PLATFORM`（恒平台 key）。
- BYOK + DeepSeek 对话流水的 `totalTokens` 是厂商真实用量（仅展示），`chargedTokens` 才是实际扣减（=服务费）；PLATFORM/Qwen/embedding 行 `chargedTokens=totalTokens`。

### POST /api/admin/quota（R2 新增，仅 ADMIN）
body: `{ "username": "alice", "grantTokens": 200000 }`（grantTokens 必须 > 0）
- 管理 API 仍接收 token 当量：给用户加 N 积分时传 `grantTokens = N × 500`，上例为 400 积分。
- 非 ADMIN → **403** `{code:"FORBIDDEN"}`；用户不存在 → **404** `{code:"USER_NOT_FOUND"}`。
- resp: 充值后该用户的 quota 视图（同 GET /api/user/quota）。

### GET /api/admin/index/orphans（R2-T0 批 5 新增，仅 ADMIN）
向量索引孤儿块巡检（warn-first 只读；孤儿=向量有块而 knowledge_chunk 无对应行）。
- query：`tenantId` 可选——不带 = 全租户（`scope:"ALL"`，跨租户枚举白名单）；带 = 单租户（`scope:"TENANT"`）。
- resp：`{ "scope": "ALL|TENANT", "count": n, "orphans": [{ "tenantId", "sourceType", "sourceId", "indexedChunks", "reason" }] }`
- reason：`VECTOR_WITHOUT_CHUNK`（有块无行）/ `INDEX_ANONYMOUS`（历史匿名块，tenantId=null）/ `SOURCE_ID_NOT_NUMERIC`（sourceId 无法映射 Long 行键）。
- 非 ADMIN → **403** `{code:"FORBIDDEN"}`；未登录 → **401**。纯读，不删任何块。

### POST /api/admin/index/delete-by-source（R2-T0 批 5 新增，仅 ADMIN）
人工确认后回收单个孤儿来源（AND 语义删除，删前复验）。
body: `{ "tenantId": 123, "sourceType": "note", "sourceId": "7001" }`（三项必填；tenantId 必须显式，禁止匿名/隐式全量删）
- resp：`{ "deleted": true, "tenantId", "sourceType", "sourceId", "chunksRemoved": n }`
- 索引无此来源 → **404** `{code:"NOT_FOUND"}`；来源仍有知识块行（健康非孤儿，防误删活资料）→ **409** `{code:"CONFLICT"}`；非 ADMIN → **403**；未登录 → **401**；字段缺失 → **400**。

### 402 语义
任何触发计费调用（DeepSeek 对话 / embedding）的业务接口，预检发现额度为 0 时：
- **PLATFORM** → **402** `{code:"QUOTA_EXHAUSTED",message:"积分已用完。平台目前处于内测阶段，请联系工作人员补充额度。微信：13481264947"}`
- **BYOK** → **402** `{code:"QUOTA_EXHAUSTED",message:"积分不足以支付本次自带 Key 服务费。平台目前处于内测阶段，请联系工作人员补充额度。微信：13481264947；若仍有少量积分，可切回平台模式按用量使用"}`（低于 1 积分即拦截，并不一定余额为零）
- 当前无在线支付接口；前端的积分不足弹窗和设置页都展示内测补额说明与工作人员微信，可选中号码复制。HTTP/SSE 错误使用相同补额引导，回答/聊天草稿保留。
- 402 穿透所有「LLM/检索瞬时失败降级」边界直达全局异常处理，前端收到后刷新额度并按模式引导；条件 UPDATE 保证不超发，PLATFORM 最后一次实扣最多把 used clamp 到 total。

## 8. 契约边界

- **已实现**：用户注册/登录 + JWT 鉴权（R1-1）；PLATFORM/BYOK 双调用模式 + 自带 key 加密存储（R1-3）；**token 计量（PLATFORM 对话+embedding 按 usage 实扣、BYOK 按次服务费）、用量流水、ADMIN 充值、402/403（R2）**
- **R2 不做**：月度重置/套餐（reset_at/plan）、充值/支付页面与申请入口（只提供 ADMIN API）、用量明细前端页面（只提供查询 API）
- 历史会话只做「最近 20 场列表 + 报告回看」，不做会话编辑/删除/多场对比
- 不做 PDF/网页抓取（输入仅 md/文本）
- 报告与明细分离：响应均为「表达精炼、抓核心」的版本（关键内容不省）；检索引用/判分过程细节/token 等内部明细只在 DB（interview_turn），不进响应

## 9. 双陪考搭档（2026-10-19）

用户确认的产品角色：**陪考小暖**负责心态与情绪陪伴；**通考小师**负责知识答疑与学习方向。新增小师能力不改变小暖已有接口。

### POST /api/companion/chat / POST /api/companion/reset（既有实现补记）
- chat body：`{ "message": "..." }`；resp：`{ "reply": "...", "safe": false }`，safe=true 表示触发已有安全支持回复。
- reset 无 body，返回 `{ "ok": true }`，仅清空本人的小暖上下文。
- 2026-10-19 小暖初步优化：FAST 非思考档不变，按用户具体处境回应、尊重只倾诉的意愿、需要时给一个小步骤；不强制一两句话，不用固定追问结束。上下文仍仅保留本人最近 8 次成功问答、重启清空。
- 模型失败/空正文不写入成功历史；空正文沿用 **504 LLM_UNAVAILABLE**，前端保留输入供重试，不再以固定安慰伪装成功。`safe=true` 安全支持跳过模型/计费，不返回未经核实的硬编码热线。

### POST /api/coach/chat（新增）
- JWT 必需；body：`{ "message": "..." }`，非空、最多 6000 字符；返回 `{ "reply": "..." }`。
- 小师通过现有 LlmPort/LlmService 路由与计费收口，FAST 非思考档；402/409/504 沿用通用错误语义，不吞掉费用或模型错误。
- 仅参考用户本次发送的文本及本人短期对话，不自动读取简历、报告或小暖的聊天，不承诺自动提醒或永久记忆。
- 小师上下文独立于小暖，按当前 JWT userId 隔离；最多保留最近 8 个成功问答，闲置 30 分钟后下次访问重新开始，服务重启清空。
- 同账号已有小师请求处理中 → 409；模型失败/空正文不写入对话历史；空正文 → 504。

### POST /api/coach/reset（新增）
- JWT 必需，无 body；返回 `{ "ok": true }`，仅清空本人的小师上下文，不影响小暖。
- 重置期间如存在旧请求，其结果不再进入下一次对话的上下文。
