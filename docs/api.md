# API 契约（api.md）

> 对外契约唯一事实源。S4 契约优先产出。NEVER 未经确认修改本文件（改前先与用户确认）。
> 传输：JSON；流式用 SSE（text/event-stream）。本地默认 http://localhost:8080。

## 1. 通用约定

- Base URL：`/api`
- 内容类型：请求/响应 `application/json; charset=utf-8`
- 鉴权（R1-1 多用户，2026-09-28 起）：
  - **JWT 令牌**：`POST /api/auth/login` 返回 `{ token }`；所有业务接口（除 §2 的 auth 白名单）必须带 `Authorization: Bearer <token>`（HTTP 头）。SSE 的 EventSource 无法设头，允许 query 参数 `?jwt=<token>`（优先级：header > query）。
  - 缺失/失效/过期 → **401** `{code:"UNAUTHORIZED",message:"请先登录"}`。
  - 令牌结构：`{ sub: userId, username, exp: 24h }`，密钥配 `jwt.secret`（`application.yml`）。
  - **与 KeyVault 门禁的关系**（R1-1→R1-3 过渡）：两者并行——JWT 管"谁"（多用户隔离），KeyVault 门禁管"能不能花钱调 LLM"（密码/机器绑定）。R1-3 平台统一持 key 后 KeyVault 门禁将弱化。
  - **auth 白名单**：`POST /api/auth/register`、`POST /api/auth/login` 不放令牌。
- 时间：ISO-8601 字符串
- 错误码：HTTP 状态码 + `{code, message}`
  - 400 参数错误 / 401 未登录或 JWT 失效（R1-1）或保险箱密码错误/未带访问令牌（R1-1 前，现由 JWT 接管多用户鉴权）/ 404 资源不存在 / 409 状态冲突（如会话已 DONE 再回答、用户名已注册）/ 500 内部错误 / 504 LLM 超时（走降级）
- 环境探活（非业务）：`GET /api/health/deps` → `{ database:{ok}, redis:{ok}, deepseek:{source,configured,unlocked}, embedder:{ provider, vectorChunks, ping:{ok,dim} } }`。JWT 白名单（健康检查无身份）。

## 2. 认证（R1-1 新增，2026-09-28）

### POST /api/auth/register
注册新用户。
- body: `{ "username": "abc", "password": "xxx" }`
- `username`：4-32 字符，唯一；已存在 → 409 `{code:"USERNAME_TAKEN"}`
- `password`：≥6 字符，BCrypt 加密存储
- resp: `{ "userId": 1, "username": "abc", "token": "eyJhbG..." }`（注册成功自动签发 JWT，免额外 login）
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

## 7. 模型密钥保险箱（页面录入，免终端环境变量）

> 密钥只存本机（默认 `~/.mock-interview/vault.json`，可用 `app.vault.dir` 改目录），AES-256-GCM 加密；任何接口都不回传密钥明文。
> **来源优先级：环境变量 `DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY` 优先**；已由环境变量提供的厂商，页面保存该厂商密钥 → 409。
>
> **R1-1 与 JWT 的关系**（2026-09-28 起）：JWT 过滤器先于 VaultAccessFilter 执行。JWT 校验"你是谁"（多用户隔离 + 数据访问），VaultAccessFilter 校验"你能不能花 LLM 钱"（KeyVault 密码门禁）。两者并行不互斥：
> - 纯 env 模式：VaultAccessFilter 不启用 → 只有 JWT 生效
> - 机器绑定模式：VaultAccessFilter 不启用 → 只有 JWT 生效
> - 密码保护模式：VaultAccessFilter 启用 → JWT 必须 **且** KeyVault 令牌必须（双令牌）
>
> R1-3 平台统一持 key 后，VaultAccessFilter 将被弱化或移除。

### GET /api/setup/status
`{ "deepseek": "ENV|VAULT|NONE", "dashscope": "ENV|VAULT|NONE", "passwordProtected": bool, "unlocked": bool }`
- `VAULT` + `passwordProtected=true` + `unlocked=false` = 等待解锁（重启后常态）。
- **JWT 白名单**（健康检查接口，免登录）。

**密码模式 = 双令牌门禁**（T12 → R1-1 更新）：`passwordProtected=true` 的保险箱一旦落盘，服务端即启用 VaultAccessFilter——所有业务接口需要 **JWT（身份）+ Vault 令牌（花钱门禁）** 两个令牌。JWT 走 `Authorization` 头，Vault 令牌走 `X-Vault-Token` 头（或各自的 SSE query 参数）。

### POST /api/setup/keys
保存/增量更新（需 JWT + 保险箱已解锁）。
- body: `{ "deepseekKey": "sk-…"(null=不动, ""=清除, 非空=覆盖), "dashscopeKey": "…"(同上), "password": "…"(可选) }`
- `password` 非空 = 设为/改为密码保护（PBKDF2-HMAC-SHA256 310000 轮派生 KEK，重启需 unlock），同时**作废旧令牌并签发新令牌**；不传 = 沿用现状（首次保存进入机器绑定模式：随机 DEK 存同目录 `machine.key`，重启自动解锁）。密码模式下仅增量改密钥（不带 password）不触动已有令牌。
- 两个密钥最终都空 → 409；密码箱锁定且未带密码就改 → 409。
- resp: `{ "status": <同 GET status 的对象>, "token": "…"|null }`——仅本次设了/改了密码时 token 非空；机器绑定模式恒 null。

### POST /api/setup/unlock
body: `{ "password": "…" }`；密码错误 → **401**（消息为人话「密码错误，密钥未能解锁」）。
- 成功 resp: `{ "status": {...}, "token": "…" }`（机器绑定箱调用时 token=null）。多设备各自解锁互不影响，每次签发独立新令牌。

### POST /api/setup/lock
清除内存中的密钥明文、KEK 与**全部访问令牌**（所有设备立即掉线，需重新解锁；环境变量来源不受影响）。本接口本身也要带有效令牌（防匿名锁定 DoS）。resp 同 status 对象（无 token 包装）。

> 密码无找回接口（否则保护形同虚设）：忘记密码需停止服务后删除 `vault.json` 重新填写。

## 8. 契约边界

- **已实现**：用户注册/登录 + JWT 鉴权（R1-1，2026-09-28）
- 历史会话只做「最近 20 场列表 + 报告回看」，不做会话编辑/删除/多场对比
- 不做 PDF/网页抓取（输入仅 md/文本）
- 报告与明细分离：响应均为「表达精炼、抓核心」的版本（关键内容不省）；检索引用/判分过程细节/token 等内部明细只在 DB（interview_turn），不进响应
