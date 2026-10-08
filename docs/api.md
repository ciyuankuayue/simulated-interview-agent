# API 契约（api.md）

> 对外契约唯一事实源。S4 契约优先产出。NEVER 未经确认修改本文件（改前先与用户确认）。
> 传输：JSON；流式用 SSE（text/event-stream）。本地默认 http://localhost:8080。

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
  - 400 参数错误 / 401 未登录或 JWT 失效 / 402 token 额度耗尽（PLATFORM/BYOK 余额不足，见 §7）/ 403 非 ADMIN 调管理接口（R2）/ 404 资源不存在 / 409 状态冲突（如会话已 DONE 再回答、用户名已注册、切 BYOK 但未存 key）/ 500 内部错误 / 504 LLM 超时（走降级）
- 环境探活（非业务）：`GET /api/health/deps` → `{ database:{ok}, redis:{ok}, deepseek:{source:"ENV",configured}, embedder:{ provider, vectorChunks, ping:{ok,dim} } }`。JWT 白名单（健康检查无身份）。R1-3 起 deepseek 只反映平台 key（env）是否配置；用户 BYOK key 不在此暴露。R4 起 `vectorChunks` 是 **Lucene 落盘索引的全局存活 doc 总数（跨用户、不含已逻辑删除待合并的旧 replace 版本，仅作粗监控，不是当前用户块数）**。

## 2. 认证（R1-1 新增）

### POST /api/auth/register
注册新用户。
- body: `{ "username": "abc", "password": "xxx" }`
- `username`：4-32 字符，唯一；已存在 → 409 `{code:"USERNAME_TAKEN"}`
- `password`：≥6 字符，BCrypt 加密存储
- resp: `{ "userId": 1, "username": "abc", "token": "eyJhbG..." }`（注册成功自动签发 JWT，免额外 login）
- **R2-T0 起注册恒为 role=USER**（「首个注册者=ADMIN」规则已删除——任何公开注册都拿不到管理员，杜绝抢注首个账号）；同时为该用户创建 quota 并赠送 token 额度（`app.quota.signup-bonus-tokens`，默认 500000）；新用户 key_mode=PLATFORM。
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
- body: `{ "jdId": "...", "roles": ["技术深挖官"] , "resumeId": 12(可选，下拉选中的简历版本 id；仅落库留痕，RAG 当前检索全局语料不按版本隔离), "maxQuestions": 8(可选，主问题数，1-8，默认 8；不含追问) }`
  - `roles` 取值：`技术深挖官` / `项目质疑官` / `行为面试官`（R7 新增；至少勾一个，按勾选顺序轮转，行为面试官出 STAR 行为题、不考技术知识点）。
- resumeId 指向不存在的简历 → 404。
- resp: `{ "sessionId", "firstQuestion": "..." , "status": "QUESTIONING" }`
- 副作用：系统解析 JD + 检索你的资料，由勾选顺序的第一个角色产出首题（技术角色紧扣 JD 考点，行为面试官出行为面试题）

### GET /api/interview/sessions
历史会话（最近 20 场倒序，精简卡片，报告回看入口）：
`[{ "sessionId", "jdId", "jobType", "sameTypeTag", "roles", "status", "matchScore", "maxQuestions", "hasReport", "createdAt" }]`
- `hasReport=true` 才可调 report 回看；进行中/未 end 的场次为 false。

### POST /api/interview/{sessionId}/answer
提交本轮回答，触发判分 + 可能追问。
- body: `{ "answer": "..." }`
- 结果经 SSE 推送（见 §5）；本接口仅返回受理 `{ "accepted": true }`
- 冲突：session 非 QUESTIONING → 409
- **自然结束（T12）**：当本轮回答后主问题计数达到 `maxQuestions`（含本轮追问链走完），同批 SSE 直接推送 `report` + `done(reason=MAX_QUESTIONS_REACHED)`，会话置 DONE 且 Critic 报告落库——前端无需再调 `/end`。

### GET /api/interview/{sessionId}/events  （SSE）
订阅流式事件。事件类型见 §5。前端连上后等待 question/score/followup/report/done。
- 前端在收到 `await_answer` 事件时，调用 §4 answer 接口提交；循环直到 `done`。

### POST /api/interview/{sessionId}/end
主动结束（中途打断/放弃；自然结束已不需要调它）。与自然结束共用同一出报告逻辑。
- **幂等**：已有 Critic 报告（含自然结束刚生成的）直接重放同一份，不重复调 LLM。
- resp: `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","track","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints":[...], "expressionIssues":[...], "overallNotes", "reminders", "behaviorReview" }`
  - `track`（R7）：`"tech"` / `"behavior"`，本轮判分赛道；老数据缺省按 `"tech"`。
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
| `score` | `{ "turnSeq", "score", "track"?: "tech"\|"behavior", "oneLineCorrection" }` | 本轮评分（R7：`track=behavior` 为 STAR 四维行为分；仅概要 + 一句纠正，不展示 breakdown 全量；讲解归报告） | 展示评分与一句纠正（行为轮加角标） |
| `followup` | `{ "turnSeq", "question", "layer" }` | 追问（layer 1/2，封顶 2；追问继承所属主问题角色与赛道） | 展示，等作答 |
| `await_answer` | `{ "turnSeq" }` | 等待本轮回答 | 调 answer 接口 |
| `report` | `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","track","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints", "expressionIssues", "overallNotes", "reminders", "behaviorReview" }` | Critic 报告（R7：含行为轮时多一个 behaviorReview 行为维度块） | 展示报告 |
| `done` | `{ "sessionId", "reason"?: "MAX_QUESTIONS_REACHED" }` | 结束。自然结束（答满题量）带 `reason`；手动 `/end` 不带。`report` 事件保证先于 `done` | 关闭流（前端兜底：若收到 done 时还没渲染 report，补调一次幂等 `/end`） |
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
> - **调用方式 `keyMode`**：`PLATFORM`（默认，走平台 key）/ `BYOK`（走用户自己加密存储的 DeepSeek key，每次对话扣固定服务费，默认 1 token 当量，余额不足→**402**）。
> - **对话模型厂商 `chatProvider`（R3-2 新增）**：`DEEPSEEK`（默认）/ `QWEN`（通义千问，DashScope OpenAI 兼容模式）。
> - 两维正交，共 4 种组合。**QWEN 恒走平台 `DASHSCOPE_API_KEY` 并按 PLATFORM 实扣 token**——即使 keyMode=BYOK：BYOK 自带 key 体系只支持 DeepSeek，选 QWEN 时不读用户 key、不扣每次服务费（API 费由平台额度承担）。
> - PLATFORM 下 DeepSeek 对话、Qwen 对话、DashScope embedding **均按真实 token 计费**——调用前预检余额，调用后按厂商 `usage.total_tokens` 实扣（缺失按字符估算兜底）。
> - 所有额度单位均为 **token**；注册赠送 `app.quota.signup-bonus-tokens`（默认 500000）。
> - 用户自带 key 仅支持 DeepSeek；任何接口都不回传 key 明文。

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
`{ "totalTokens": 500000, "usedTokens": 1320, "remainingTokens": 498680 }`
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
- **PLATFORM** → **402** `{code:"QUOTA_EXHAUSTED",message:"Token 额度已用完，请联系管理员充值"}`
- **BYOK** → **402** `{code:"QUOTA_EXHAUSTED",message:"服务点数已用完：删除你的 Key 切回平台模式，或联系管理员充值"}`
- 402 穿透所有「LLM/检索瞬时失败降级」边界直达全局异常处理，前端收到后刷新额度并按模式引导；条件 UPDATE 保证不超发，PLATFORM 最后一次实扣最多把 used clamp 到 total。

## 8. 契约边界

- **已实现**：用户注册/登录 + JWT 鉴权（R1-1）；PLATFORM/BYOK 双调用模式 + 自带 key 加密存储（R1-3）；**token 计量（PLATFORM 对话+embedding 按 usage 实扣、BYOK 按次服务费）、用量流水、ADMIN 充值、402/403（R2）**
- **R2 不做**：月度重置/套餐（reset_at/plan）、充值/支付页面与申请入口（只提供 ADMIN API）、用量明细前端页面（只提供查询 API）
- 历史会话只做「最近 20 场列表 + 报告回看」，不做会话编辑/删除/多场对比
- 不做 PDF/网页抓取（输入仅 md/文本）
- 报告与明细分离：响应均为「表达精炼、抓核心」的版本（关键内容不省）；检索引用/判分过程细节/token 等内部明细只在 DB（interview_turn），不进响应
