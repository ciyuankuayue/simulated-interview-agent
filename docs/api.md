# API 契约（api.md）

> 对外契约唯一事实源。S4 契约优先产出。NEVER 未经确认修改本文件（改前先与用户确认）。
> 传输：JSON；流式用 SSE（text/event-stream）。本地默认 http://localhost:8080。

## 1. 通用约定

- Base URL：`/api`
- 内容类型：请求/响应 `application/json; charset=utf-8`
- 鉴权：MVP 本地单机，无登录；预留 `X-User` 头（后续接用户系统）
- 时间：ISO-8601 字符串
- 错误码：HTTP 状态码 + `{code, message}`
  - 400 参数错误 / 404 资源不存在 / 409 状态冲突（如会话已 DONE 再回答）/ 500 内部错误 / 504 LLM 超时（走降级）

## 2. 资料管理（RAG 索引源）

### POST /api/jd
上传/粘贴 JD（可批量，同类一起练）。
- body: `{ "rawText": "..." , "sameTypeTag": "后端-美团" }` 或 `{ "rawTexts": ["...","..."] }`
- resp: `[{ "jdId", "keywords":[...], "jobType", "sameTypeTag" }]`

### GET /api/jd
JD 列表（精简）：`[{ "jdId", "jobType", "sameTypeTag", "createdAt" }]`

### POST /api/resume
上传简历版本（md）。resp: `{ "resumeId", "createdAt" }`
### GET /api/resume
简历版本列表：`[{ "resumeId", "createdAt", "isActive" }]`
### POST /api/resume/{resumeId}/activate
切换启用版本。resp: `{ "resumeId", "isActive": true }`

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

## 3. 模拟面试（核心流程）

### POST /api/interview/start
开始一次模拟。
- body: `{ "jdId": "...", "roles": ["技术深挖官"] , "resumeId": "..."(可选，默认 active) }`
- resp: `{ "sessionId", "firstQuestion": "..." , "status": "QUESTIONING" }`
- 副作用：系统解析 JD + 检索你的资料，产出首题（技术深挖官先出）

### POST /api/interview/{sessionId}/answer
提交本轮回答，触发判分 + 可能追问。
- body: `{ "answer": "..." }`
- 结果经 SSE 推送（见 §4）；本接口仅返回受理 `{ "accepted": true }`
- 冲突：session 非 QUESTIONING → 409

### GET /api/interview/{sessionId}/events  （SSE）
订阅流式事件。事件类型见 §4。前端连上后等待 question/score/followup/report/done。
- 前端在收到 `await_answer` 事件时，调用 §3 answer 接口提交；循环直到 `done`。

### POST /api/interview/{sessionId}/end
主动结束（答完或放弃）。触发 Critic 出报告（同步返回精简版，重报告走异步）。
- resp: `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints":[...], "expressionIssues":[...], "overallNotes", "reminders" }`

### GET /api/interview/{sessionId}/report
拉取 Critic 报告（精简版，给用户看）。
- resp: `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints":[...], "expressionIssues":[{"issue","guide"}], "overallNotes", "reminders", "createdAt" }`

### GET /api/interview/{sessionId}/status
会话状态：`{ "sessionId", "status", "roles", "turnCount", "matchScore" }`

## 4. SSE 事件协议（GET /api/interview/{sessionId}/events）

每个事件 `event: <type>\ndata: <json>\n\n`：

| event | data | 含义 | 前端动作 |
|---|---|---|---|
| `question` | `{ "turnSeq", "question", "role" }` | 新一题（技术深挖官/项目质疑官） | 展示，等用户作答 |
| `score` | `{ "turnSeq", "score", "oneLineCorrection" }` | 本轮评分（仅概要 + 一句纠正，不展示 breakdown 全量；讲解归报告） | 展示评分与一句纠正 |
| `followup` | `{ "turnSeq", "question", "layer" }` | 追问（layer 1/2，封顶 2） | 展示，等作答 |
| `await_answer` | `{ "turnSeq" }` | 等待本轮回答 | 调 answer 接口 |
| `report` | `{ "reportId", "matchScore", "perTurnReview":[{ "turnSeq","question","score","explanation","correction","knowledgeSupplement","exemplarAnswer" }], "collapsedPoints", "expressionIssues", "overallNotes", "reminders" }` | Critic 报告（精炼不省关键） | 展示报告 |
| `done` | `{ "sessionId" }` | 结束 | 关闭流 |
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

## 5. 错题本

### GET /api/weak-point
复习队列（未出队），按 next_review_at 升序：
`[{ "weakPointId", "knowledgePoint", "wrongCount", "reviewStage", "nextReviewAt", "status" }]`

### GET /api/weak-point/due?now={ISO-8601,可选}
今日待复习：`next_review_at <= now` 且未出队（不传 `now` 取当前时间）。返回字段同上。

## 6. 契约边界（MVP 不做）

- 不做用户系统（无注册/登录接口）
- 不做历史会话列表美化（仅 status 查询）
- 不做 PDF/网页抓取（输入仅 md/文本）
- 报告与明细分离：响应均为「表达精炼、抓核心」的版本（关键内容不省）；检索引用/判分过程细节/token 等内部明细只在 DB（interview_turn），不进响应
