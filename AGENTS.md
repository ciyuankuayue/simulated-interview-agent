# AGENTS.md — 模拟面试 Agent

> 本文件每轮对话都会被读取。只写"只有本仓库/本机才知道"的约束，不写通用废话。

## Context（背景与目标）

本地运行的 AI 模拟面试官：贴 JD → 自动解析考点 → 两个 Agent 角色轮番追问你（技术深挖 / 项目质疑）→ Critic 出报告 + 错题本（间隔重复）。你的资料（简历多版本 / 笔记 / 项目文档 / 真实面试记录）全部入库，越用越懂你。

**文件原则**：给你看的东西（报告、状态）精简；系统内部明细（检索引用、判分过程、每轮耗时 token）详细存 DB，不摊成长文件。

**参考文件**
- 技术栈与项目形态：`C:\Users\19284\WorkBuddy\2026-09-14-19-08-24\人机协同\19-项目A-技术栈与形态.md`（本机路径，规划用）
- 领域模型：`./docs/domain.md`
- API 契约：`./docs/api.md`
- 会话交接：`./docs/handoff.md`

## Agent（协作者角色）

你是本仓库的 AI 协作者，职责：
- 按 vibe-coding 指南第 5 章六阶段（需求→领域建模→技术选型→契约优先→任务拆解→分批实现）推进本项目。
- Agent 核心循环手写 ReAct（不依赖框架）；L3 对比实验单独用 LangChain4j 重写一版。
- 每轮会话开始必做：① 读本文件；② 续做前先读 `./docs/handoff.md`（当前进度 / 卡点 / 关键决策）。
- 不擅自改架构；有分歧先问。
- 改完由你审，**不自动 commit**。

## Rules（规则）

### Commands（命令写死，可复制）
- 编译打包（必须用项目自带的 mvnw，**不要用 Git Bash 跑 mvn**——本机 Git Bash 的 mvn 是坏的）：`.\mvnw.cmd -DskipTests package`
- 启动：`.\mvnw.cmd spring-boot:run`（默认 8080）
- 端口被占用（报 "Port 8080 was already in use"）：查占用 `netstat -ano | findstr :8080` → 杀 `taskkill /PID <pid> /F`；或临时换端口 `.\mvnw.cmd spring-boot:run -Dserver.port=8081`
- 跑测试：`.\mvnw.cmd test`
- 本地访问：http://localhost:8080
- redis 用 docker 起

### Architecture（技术栈精确版本 + 分层）
- Java 17 / Spring Boot 3.x / Maven（mvnw 封装）
- 存储：MySQL 8（持久化 JD、简历版本、笔记、模拟记录）/ Redis（指纹去重、会话缓存）
- 消息：RabbitMQ（D3 引入，异步判分/追问；非必须，可后加）
- LLM：
  - 生成/追问：DeepSeek `deepseek-flash`（非思考型用于追问，思考型用于判分）
  - `max_tokens`：追问 / 判分 / Critic **不卡 800**；判分与 Critic 输出长，放宽到 ≥ 4096（或按需更大、彻底不传该参数）。仍**必须校验输出非空**（思考型 reasoning 与正文共用额度，超了正文可能变空串）
  - Embedding：阿里通义 `qwen3.7-text-embedding-flash`（OpenAI 兼容，baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1），key 从环境变量 `DASHSCOPE_API_KEY` 读
- 包结构（分层；**领域逻辑不准直接调 LLM**，统一走 llm 层）：
  - `web/`       controller，SSE 流式推送
  - `agent/`     角色编排（技术深挖官 / 项目质疑官 / Critic）
  - `rag/`       检索（解析 / 分块 / 向量化 / 检索排序）
  - `llm/`       LlmService（封装 DeepSeek 调用 + 超时重试；发 `tools` 字段做 function calling）
  - `memory/`    跨轮记忆 + 错题本 + 间隔重复调度
  - `domain/`    实体与持久化

### Restrictions（NEVER）
- NEVER 把任何 API Key 写进代码或 git（代码用 `System.getenv(...)`，yml 用 `${DASHSCOPE_API_KEY}`）
- NEVER 在生成内容里编造简历/笔记里不存在的经历（本项目特有禁忌）
- NEVER 未经确认修改对外 API 契约（见 `./docs/api.md`）
- NEVER 自动 commit（改完由我审）
- NEVER 用 Git Bash 直接跑 mvn

### Standards（规范）
- commit message：`类型: 简述`（feat/fix/refactor/docs）
- 异常处理：LLM 调用一律包超时 + 重试；失败走降级（返回源摘要 / 备份并标 degraded）
- 日志：AOP 记录各步耗时（ms），格式统一，便于定位卡点

## Scope（范围与边界）
- 不擅自改架构；有分歧先问。
