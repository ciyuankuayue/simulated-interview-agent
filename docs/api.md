# API 契约（api.md）

> 对外契约唯一事实源。S4 契约优先产出。NEVER 未经确认修改本文件（改前先与用户确认）。
> 传输：JSON；流式用 SSE（text/event-stream）。本地默认 http://localhost:8080。

## 1. 通用约定

- Base URL：`/api`
- 内容类型：请求/响应 `application/json; charset=utf-8`
- 鉴权（R1-1 多用户，R1-3 统一形态）：
  - **JWT 令牌**：`POST /api/auth/login` 返回 `{ token }`；所有业务接口（除 §2 的 auth 白名单）必须带 `Authorization: Bearer <token>`（HTTP 头）。SSE 的 EventSource 无法设头，允许 query 参数 `?jwt=<token>`（优先级：header > query）。
  - 缺失/失效/过期 → **401** `{code:"UNAUTHORIZED",message:"请先登录"}`。
  - 令牌结构：`{ sub: userId, username, exp: 24h }`，密钥配环境变量 `JWT_SECRET`（空则启动时自动生成临时密钥，仅开发便利）。
  - **R1-3 起只有 JWT 一道门禁**：T11/T12 的页面保险箱 / `X-Vault-Token` / VaultAccessFilter 已整体删除。能不能调 LLM 由用户的 key 模式（PLATFORM 平台 key / BYOK 自带 key）与额度决定（见 §7），不再有第二枚令牌。
  - **auth 白名单**：`POST /api/auth/register`、`POST /api/auth/login` 不放令牌。
- 时间：ISO-8601 字符串
- 错误码：HTTP 状态码 + `{code, message}`
  - 400 参数错误 / 401 未登录或 JWT 失效 / 402 服务点数耗尽（R1-3，BYOK 余额不足，见 §7）/ 404 资源不存在 / 409 状态冲突（如会话已 DONE 再回答、用户名已注册、切 BYOK 但未存 key）/ 500 内部错误 / 504 LLM 超时（走降级）
- 环境探活（非业务）：`GET /api/health/deps` → `{ database:{ok}, redis:{ok}, deepseek:{source:"ENV",configured}, embedder:{ provider, vectorChunks, ping:{ok,dim} } }`。JWT 白名单（健康检查无身份）。R1-3 起 deepseek 只反映平台 key（env）是否配置；用户 BYOK key 不在此暴露。

## 2. 认证（R1-1 新增，2026-09-28）

### POST /api/auth/register
注册新用户。
- body: `{ "username": "abc", "password": "xxx" }`
- `username`：4-32 字符，唯一；已存在 → 409 `{code:"USERNAME_TAKEN"}`
- `password`：≥6 字符，BCrypt 加密存储
- resp: `{ "userId": 1, "username": "abc", "token": "eyJhbG..." }`（注册成功自动签发 JWT，免额外 login）
- **R1-3 附带效果**：库中**第一个注册账号** role=ADMIN（其后均 USER）；同时为该用户创建 quota 并赠送服务点数（`app.quota.signup-bonus`，默认 1000）；新用户 key_mode=PLATFORM。
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
- resumeId 指向不存在的简历 → 404。
- resp: `{ "sessionId", "firstQuestion": "..." , "status": "QUESTIONING" }`
- 副作用：系统解析 JD + 检索你的资料，产出首题（技术深挖官先出）

### GET /api/interview/sessions
历史会话（最近 20 场倒序，精简卡片，报告回看入口）：
`[{ "sessionId", "jdId", "jobType", "sameTypeTag", "roles", "status", "matchScore", "maxQuestions", "hasReport", "createdAt" }]`
- `hasReport=true` 才可调 report 回看；进行中/未 end 的场次为 false。

### POST /api/interview/{sessionId}/answer
提交本轮回答，触发判分 + 可能追问。
- body: `{ "answer": "..." }`
- 结果经 SSE 推送（见 §5）；本接口仅返回受理 `{ "accepted": true }`
- 冲突：session 非 QUESTIONING → 409
- **自然结束（2026-09-19 T12）**：当本轮回答后主问题计数达到 `maxQuestions`（含本轮追问链走完），同批 SSE 直接推送 `report` + `done(reason=MAX_QUESTIONS_REACHED)`，会话置 DONE 且 Critic 报告落库——前端无需再调 `/end`。

### GET /api/interview/{sessionId}/events  （SSE）
订阅流式事件。事件类型见 §5。前端连上后等待 question/score/followup/report/done。
- 前端在收到 `await_answer` 事件时，调用 §4 answer 接口提交；循环直到 `done`。

### POST /api/interview/{sessionId}/end
主动结束（中途打断/放弃；自然结束已不需要调它）。与自然结束共用同一出报告逻辑。
- **幂等**：已有 Critic 报告（含自然结束刚生成的）直接重放同一份，不重复调 LLM。
- resp: `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints":[...], "expressionIssues":[...], "overallNotes", "reminders" }`

### GET /api/interview/{sessionId}/report
拉取 Critic 报告（精简版，给用户看）。
- resp: `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints":[...], "expressionIssues":[{"issue","guide"}], "overallNotes", "reminders", "createdAt" }`

### GET /api/interview/{sessionId}/status
会话状态：`{ "sessionId", "status", "roles", "turnCount", "matchScore" }`

## 5. SSE 事件协议（GET /api/interview/{sessionId}/events）

每个事件 `event: <type>\ndata: <json>\n\n`：

| event | data | 含义 | 前端动作 |
|---|---|---|---|
| `question` | `{ "turnSeq", "question", "role" }` | 新一题（技术深挖官/项目质疑官） | 展示，等用户作答 |
| `score` | `{ "turnSeq", "score", "oneLineCorrection" }` | 本轮评分（仅概要 + 一句纠正，不展示 breakdown 全量；讲解归报告） | 展示评分与一句纠正 |
| `followup` | `{ "turnSeq", "question", "layer" }` | 追问（layer 1/2，封顶 2） | 展示，等作答 |
| `await_answer` | `{ "turnSeq" }` | 等待本轮回答 | 调 answer 接口 |
| `report` | `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints", "expressionIssues", "overallNotes", "reminders" }` | Critic 报告（精炼不省关键） | 展示报告 |
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

## 7. 模型调用方式 / BYOK / 额度（R1-3，替代旧 /api/setup/** 保险箱接口）

> R1-3 起平台 key 只从**环境变量**读（`DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY`）；页面保险箱、`/api/setup/**`、`X-Vault-Token` 全部移除。
> 每个登录用户有两种对话模型（DeepSeek）调用方式，由 `app_user.key_mode` 决定：
> - **PLATFORM**（默认）：走平台 key 调 DeepSeek，R1-3 不扣点（按 token 计费在 R2）；embedding 始终走平台 `DASHSCOPE_API_KEY`，与模式无关。
> - **BYOK**：走用户自己加密存储的 DeepSeek key（模型直连用户账号），**每次 DeepSeek 调用前扣 1 点服务费**；余额不足 → **402** `{code:"QUOTA_EXHAUSTED"}`，不发起调用。
> - 用户自带 key 仅支持 DeepSeek；任何接口都不回传 key 明文。

### GET /api/user/settings
`{ "keyMode": "PLATFORM|BYOK" }`

### PUT /api/user/settings
切换调用方式。body: `{ "keyMode": "BYOK" }`
- 切到 `BYOK` 但该用户未保存 key → **409** `{code:"KEY_NOT_CONFIGURED",message:"请先保存你的 API Key"}`。
- 成功 resp: 同 GET。

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
`{ "totalPoints": 1000, "usedPoints": 3, "remaining": 997 }`
- R1-3 只有 BYOK 调用会增加 usedPoints（每次 +1）；PLATFORM 不增。

### 402 语义
BYOK 模式调用任意业务接口触发 DeepSeek 且点数不足时：
- 面试流程中的追问/判分：接口返回 **402** `{code:"QUOTA_EXHAUSTED",message:"服务点数已用完：删除你的 Key 切回平台模式，或联系管理员充值"}`；前端弹提示并引导切回 PLATFORM（已有平台 key 时）。
- 扣减为条件更新（`used_points < total_points`），不超发；R2 替换为 token 级计量 + 乐观锁。

## 8. 契约边界

- **已实现**：用户注册/登录 + JWT 鉴权（R1-1）；PLATFORM/BYOK 双调用模式 + BYOK 服务费点数 + 自带 key 加密存储（R1-3，2026-10-02）
- **R1-3 不做**（留 R2）：PLATFORM 模式按 DeepSeek usage 的 token 计费、并发扣减强化（乐观锁/Redis INCR）、充值入口、配额套餐管理
- 历史会话只做「最近 20 场列表 + 报告回看」，不做会话编辑/删除/多场对比
- 不做 PDF/网页抓取（输入仅 md/文本）
- 报告与明细分离：响应均为「表达精炼、抓核心」的版本（关键内容不省）；检索引用/判分过程细节/token 等内部明细只在 DB（interview_turn），不进响应
