# 会话交接（handoff.md）

> 每轮续对话先读本文件。记录：当前进度 / 已锁决策 / 任务拆解 / 卡点 / 下一步。
> 配套：`./domain.md`（领域模型）、`./api.md`（契约）、`../AGENTS.md`（规则）。

## 1. 当前进度（截至 2026-09-17）

| 阶段 | 产物 | 状态 | 备注 |
|---|---|---|---|
| 地基 | 仓库克隆 + `.gitignore` + README 骨架 | ✅ 已推远端 master | commit 由你审 |
| 地基 | DeepSeek function calling 验通 | ✅ | 实测模型回 `deepseek-flash`，`tool_calls` 结构化打通 |
| S1 | MVP 边界确认 | ✅ | 按 `19-项目A-技术栈与形态.md` §6：2 角色 + Critic、单轮≤2 层追问、md 输入、向量先内存余弦、最简静态页 |
| S2 | `./docs/domain.md` | ✅ 已落盘 | 10 实体 + 判分结构 + 间隔重复方案 A |
| S3 | 技术选型 | ✅ | 与规划文档一致，无变更 |
| S4 | `./docs/api.md` | ✅ 已落盘 | 契约优先，待实现遵守 |
| S5 | `./docs/handoff.md`（本文件） | 🔵 当前 | 任务拆解 |
| S6 | 代码实现 | 🔵 进行中 | T1 工程骨架已建且本机编译验通（`/api/health` → UP）；T2–T10 待做 |

**未 commit 文件（遵守 NEVER 自动 commit，你审完再提）**：`AGENTS.md`、`docs/domain.md`、`docs/api.md`、`docs/handoff.md`、`pom.xml`、`mvnw`、`mvnw.cmd`、`.mvn/wrapper/`、`src/`（T1 骨架）、`scripts/deepseek-fc-demo/`（临时，验完即弃）。
**临时文件（不要 commit，验完即弃）**：`scripts/deepseek-fc-demo/`（仅验证用）。

## 2. 已锁定的关键决策

1. **模型命名与模式**（2026-09-17 拍板，覆盖原「实测回执 deepseek-flash」）：
   - 追问：`deepseek-v4-flash` **非思考模式**（快）。
   - 判分 / Critic 报告：`deepseek-v4-flash` **思考模式**起步；实测质量不够 → 升 `deepseek-v4-pro`（同 provider，只换 model 字符串，零新集成）。
   - 不引入新 provider（AGENTS.md Architecture 需同步改为 `deepseek-v4-flash`）。
   - ⚠️ 风险：早前 function calling 实测发 `deepseek-v4-flash` 时回执 `model=deepseek-flash`——T5 实现前先确认 DeepSeek 当前真实 model 字符串，避免调错。
2. **max_tokens 放宽**：追问/判分/Critic 不再卡 800，按 provider 默认或放宽到 ≥4096；仍**校验输出非空**（思考型 reasoning 与正文共用额度，超了正文可能变空串）。
3. **project_doc 是外部上传的弹药库**，独立于简历内嵌项目介绍（双源检索）；缺失则质疑官降级为仅基于简历。建议必传。
4. **角色支持部分启用**：`interview_session.roles` 为数组，可只含 `["技术深挖官"]`；Critic 始终在。
5. **实时只追问/一句纠正，不展开讲解**；讲解/纠正/知识补充/示范回答全归 Critic 报告（domain.md §4.1）。
6. **「精简」= 表达精炼、抓核心，关键内容不省**（per_turn_review / overall_notes / reminders 全在）。
7. **间隔重复方案 A 固定阶梯**：`review_stage∈{0,1,2,3}`，间隔 `[1,3,7,15]` 天（domain.md §5）。
8. **start 同步返回首题 + SSE 推后续**（避免 SSE 先连后等首题的竞态）。
9. **SSE 事件协议**：`question/score/followup/await_answer/report/done/error`，前端收 `await_answer` 才调 answer，循环到 `done`（api.md §4）。
10. **L3 对比实验纳入 MVP 关键路径**（不后置，D2 做）：RAG 先手写余弦相似度检索，再换向量库，当场记录对比数字（召回率/耗时/代码量）。这组数字是项目最硬「评估闭环」面试证据，首版 demo 就要能讲。
11. **RabbitMQ 砍出 MVP，仅作可选 stretch**：单人面试练习工具同步调用足够，MQ 的异步/削峰/重试属过度设计。D3 不强制引入；消息队列相关从已有八股知识答即可。
12. **前端用 Thymeleaf 服务端渲染 + SSE**：不引入 npm / 任何前端构建链；复用已有 `SseEmitter` + `EventSource` 经验，专注 AI 后端。

## 3. 任务拆解（第一版可跑闭环）

> 顺序即实现顺序。每完成一项在「状态」打 ✅，并记耗时/卡点到本文件。

- [x] **T1 初始化 Spring Boot 工程**（骨架已建；2026-09-17 本机编译 + 启动验通 `/api/health` → UP）
  - 已建：`pom.xml`（Spring Boot 3.3.5 / Java 17 / starter-web + validation；JPA/MySQL/Redis 在 T2 解注释）、`mvnw`+`mvnw.cmd`+`.mvn/wrapper/`（官方格式，首次运行自下载 Maven 3.9.9）、分层包 `web/agent/rag/llm/memory/domain/`、`MockInterviewApplication`、`web/HealthController`（`GET /api/health`）、`application.yml`（占位，T2 补 DB/Redis）。
  - 基础包：`com.bupt.mockinterview`（如需改名在 T1 阶段就改）。
  - 验收：你本机跑 `.\mvnw.cmd -DskipTests package` + `.\mvnw.cmd spring-boot:run` → 访问 http://localhost:8080/api/health 返回 `{"status":"UP"}`。沙盒外网限流，无法在此编译验证。
  - 2026-09-17 修正：原 mvnw 依赖 maven-wrapper.jar（本机下载失败留坏 jar）+ 中文 `@rem` 在 GBK cmd 下乱码当命令执行。已改为**纯 ASCII** 且**直接下载 Maven 3.9.9 发行包**解压调用（存 `.mvn/wrapper/dists/`，已 gitignore；不再用 maven-wrapper.jar）。
- [ ] **T2 配置与外部依赖接入**
  - `application.yml`：MySQL 8 / Redis 连接；`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY` 从环境变量读（`${...}`），**绝不写死 key**。
  - Redis 用 docker 起；提供 `docker run` 一行命令注到 README 或本文件。
  - 验收：应用能连 MySQL + Redis。
- [ ] **T3 domain 持久化层**
  - 10 实体（domain.md §2）建表/Entity + Repository。
  - 验收：`/api/jd`、`/api/resume` 等资料管理接口能写库、能查。
- [ ] **T4 rag 模块（D2，含 L3 手写余弦 vs 向量库对比，MVP 关键路径，决策 #10）**
  - 第一版：解析（md/文本）→ 分块 → 通义 `qwen3.7-text-embedding-flash` 向量化 → **内存手写余弦**检索 → 排序返回 top-k。
  - **对比实验**：同一查询集，先跑手写余弦、再换向量库（如 Redis vector / pgvector），当场记录 3 组数字：**召回率 / 单查询耗时 / 代码量**；存入本文件或 `docs/` 对比小节，首版 demo 即可讲。
  - 验收：给定问题能从 resume/note/project_doc 召回相关块；对比数字已记录。
- [ ] **T5 llm 层**
  - `LlmService`：封装 DeepSeek 调用 + 超时重试 + 失败降级；发 `tools` 字段做 function calling（已验通）。
  - 两个入口：追问（flash、非思考）/ 判分（思考型、结构化 JSON 输出、校验非空）。
  - 验收：单元验证「追问生成」与「判分 JSON」两条链路。
- [ ] **T6 agent 角色编排**
  - 手写 ReAct 循环：技术深挖官 + 项目质疑官按 `roles` 启用；每轮判分（4 维）→ 触发追问（≤2 层 why）或一句纠正；Critic 在 end 时出报告（逐轮讲解/纠正/知识补充/示范回答 + collapsed_points + expression_issues + overall_notes + reminders）。
  - 判分结构对齐 domain.md §4；实时行为对齐 §4.1。
  - 验收：单角色（技术深挖官）能跑完一轮问答 + 出报告。
- [ ] **T7 web 层 + SSE**
  - controller 对齐 `api.md`：资料管理 CRUD、start/answer/end/report/status。
  - `GET /events` SSE 推送 `question/score/followup/await_answer/report/done/error`。
  - 验收：按 api.md §4 事件流示例跑通（question→await_answer→…→report→done）。
- [ ] **T8 memory 层（错题本 + 间隔重复）**
  - 答崩点（score<4）入 `weak_point`；方案 A 阶梯排 `next_review_at`。
  - 验收：`GET /api/weak-point` 按 `next_review_at` 升序返回。
- [ ] **T9 前端（Thymeleaf 服务端渲染 + SSE，决策 #12）**
  - 不引入 npm / 任何前端构建链；复用已有 `SseEmitter`（后端）+ `EventSource`（前端）经验，专注 AI 后端。
  - 功能：粘贴 JD、上传简历/project_doc、开始模拟、展示流式问题/评分/纠正（EventSource 收 SSE）、结束拉报告、看错题本。
  - 验收：浏览器走完首轮闭环。
- [ ] **T10 全链路验收**
  - 起服务 → 上传资料 → 跑一次完整模拟（含 1 层追问）→ 出报告 → 错题本有记录。
  - 验收：端到端无报错，报告含 per_turn_review + reminders。

## 4. 卡点 / 待确认

- **L3 对比版**：已纳入 MVP 关键路径（决策 #10）。D2 做 RAG 时先手写余弦相似度检索、再换向量库，当场记录召回率/耗时/代码量对比数字；首版 demo 就要能讲，不后置。
- **RabbitMQ**：已砍出 MVP，仅作可选 stretch（决策 #11）。单人工具同步调用足够，MQ 的异步/削峰/重试属过度设计；D3 不再引入异步判分，判分改同步 `deepseek-v4-flash` 思考模式。消息队列相关从已有八股知识答即可。
- **前端框架**：已定 **Thymeleaf 服务端渲染 + SSE**（决策 #12）。不引入 npm / 任何前端构建链；复用 `SseEmitter` + `EventSource`，专注 AI 后端。
- **判分模型真实型号名**：决策 #1 锁定 `deepseek-v4-flash` 思考模式起步、不足升 `deepseek-v4-pro`。⚠️ 早前 function calling 实测发 `deepseek-v4-flash` 回执 `model=deepseek-flash`——T5 实现前先确认 DeepSeek 当前真实 model 字符串，避免调错。
- **AGENTS.md 同步待办**：AGENTS.md Architecture 仍写 `deepseek-flash`，需随决策 #1 改为 `deepseek-v4-flash`（本回合仅指令改 handoff.md，AGENTS.md 同步待你确认）。

## 5. 下一步立即做

**从 T1 开始：初始化 Spring Boot 工程。**
- 用项目自带 `mvnw`（`NEVER` 用 Git Bash 跑 mvn）。
- 先建 `pom.xml` + 分层包空壳 + 一个最小 `/api/health` 端点，跑通 `.\mvnw.cmd spring-boot:run` 再逐步加 T2–T10。
- 每步「读→想→改→跑→报」，跑通再进下一项。

> 开工前提示：本机需 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY` 环境变量；Redis 需 docker 起。缺则 T2 会卡，先确认环境就绪。
