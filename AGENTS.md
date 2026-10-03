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

### Commands（命令写死，可复制；默认在 PowerShell 跑）
- 编译打包（必须用项目自带的 mvnw，**不要用 Git Bash 跑 mvn**——本机 Git Bash 的 mvn 是坏的）：`.\mvnw.cmd -DskipTests package`
- 启动（默认 H2 内存库，零外部依赖）：`.\mvnw.cmd spring-boot:run`（默认 8080）
- 启动（**MySQL 8 + Redis 生产形态**，先 `docker compose up -d`），任选一种：
  - PowerShell 环境变量（推荐）：`$env:SPRING_PROFILES_ACTIVE="mysql"; .\mvnw.cmd spring-boot:run`
  - 双 shell 通用（cmd / PowerShell 都行，**-D 参数整体加引号，不加在 PowerShell 下会被拆参报 Unknown lifecycle phase**）：`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mysql"`
- 端口被占用（报 "Port 8080 was already in use"）：查占用 `netstat -ano | findstr :8080` → 杀 `taskkill /PID <pid> /F`；或临时换端口 `.\mvnw.cmd spring-boot:run "-Dserver.port=8081"`
- 跑测试：`.\mvnw.cmd test`
- 本地访问：http://localhost:8080；依赖探活：http://localhost:8080/api/health/deps（`database`/`redis` 两个 ok 才算环境就绪）
- MySQL + Redis：`docker compose up -d`（改 compose 端口/环境后必须 `docker compose up -d --force-recreate` 才生效，旧容器不会自动套用新配置）
- **JDK 不用手动设**：mvnw.cmd 在未设 `JAVA_HOME` 时自动选 `%USERPROFILE%\jdk-21`；显式设了就尊重。pom enforcer 拒绝 JDK 24+（构建直接失败并提示）。
- **LLM key 不用每窗同步**：`DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` 已设为用户级环境变量，**新开**的 PowerShell 自动继承；只有设置前就开着的旧窗口才需要 `$env:DEEPSEEK_API_KEY=[Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')` 补一次。

### Architecture（技术栈精确版本 + 分层）
- **Java 17 是编译目标**（release 17，产物 major=61）；**本机构建 JDK 固定 21**（`C:\Users\19284\jdk-21`，mvnw 自动选择；JDK 24 被 enforcer 拒绝，Lombok 在高版本 JDK 曾静默失效）；Spring Boot 3.3.5 / Maven 3.9.9（mvnw 自带 dist）
- 存储：MySQL 8（持久化 JD、简历版本、笔记、模拟记录）/ Redis（指纹去重、会话缓存）/ **Lucene 9.12.3 FSDirectory（R4 起向量块持久化，目录 `data/rag-index`，env `APP_RAG_INDEX_DIR` 可覆盖；userId pre-filter 隔离、beginReplace/commit/rollback 批次事务；部署必须挂持久卷，详见 docs/domain.md §7）**
- 消息：RabbitMQ（D3 引入，异步判分/追问；非必须，可后加）
- LLM：
  - 生成/追问：DeepSeek `deepseek-flash`（非思考型用于追问，思考型用于判分）
  - `max_tokens`：追问 / 判分 / Critic **不卡 800**；判分与 Critic 输出长，放宽到 ≥ 4096（或按需更大、彻底不传该参数）。仍**必须校验输出非空**（思考型 reasoning 与正文共用额度，超了正文可能变空串）
  - Embedding：阿里通义 `qwen3.7-text-embedding-flash`（OpenAI 兼容，baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1），key 只从环境变量 `DASHSCOPE_API_KEY` 读（embedding 永远平台 key，与用户模式无关）
  - **密钥体系（R1-3，2026-10-02 起，替代旧页面保险箱）**：平台 key（DeepSeek/DashScope）**只从环境变量**读，运维配置，不再有 vault.json/页面保存。运行时取对话 key 一律走 `security/KeyResolver.resolveForChat(userId)`：PLATFORM→env；BYOK→解密该用户 `user_api_key`（AES-GCM，KEK 走 env `APP_DATA_KEY`，未配则启动生成临时密钥并 WARN）；userId 为 null（测试 profile）→ env。**禁止在业务代码直接读 `LlmProperties.apiKey` 绕过路由**。embedding key 直接 `DashscopeProperties::getApiKey`
  - **token 计费（R2，2026-10-02 起）**：额度单位 token，注册赠 `app.quota.signup-bonus-tokens`（默认 50 万）。**PLATFORM**：DeepSeek 对话 + DashScope embedding 都先预检余额（=0 抛 QuotaExhaustedException→402，不发请求），调用后按厂商 usage.total_tokens 实扣（缺失按字符 1:1 估算兜底）；最后一次可透支、clamp 到 total。**BYOK**：API 费用户自付，对话调用前仍扣固定服务费 `app.quota.byok-charge-per-call`（token 当量默认 1）；embedding 恒走平台 key 按 token 扣。扣减+`llm_usage_log` 流水走 REQUIRES_NEW 独立事务（业务回滚不退款）；条件 UPDATE 防超发，不引 @Version/Redis。唯一计费收口：LLM 对话在 `LlmService.chat`，embedding 在 `RagService`；402/409 必须穿透所有降级 catch（parseJd/index/编排器/Critic）。ADMIN 可经 `POST /api/admin/quota` 充值（非 ADMIN→403）
  - **鉴权只有 JWT 一道**（R1-3 起）：T11/T12 的 `KeyVault`/`VaultAccessFilter`/`/api/setup/**`/`X-Vault-Token`/`vaultToken` 已整体删除，禁止复活。JWT 走 `Authorization` 头，SSE 走 query `?jwt=`；前端 token 只存 JS 内存变量（刷新必失效，**严禁 localStorage**）。`app.auth.jwt-enabled=false` 仅测试 profile 用，生产默认启用（统一多用户形态，本地也要注册登录）
  - **静态资源 `Cache-Control: no-cache`**（application.yml `spring.web.resources`，T12）：前端更新后不靠用户手动 Ctrl+F5；配 Last-Modified 协商，未变走 304
- 包结构（分层；**领域逻辑不准直接调 LLM**，统一走 llm 层）：
  - `web/`       controller，SSE 流式推送
  - `agent/`     角色编排（技术深挖官 / 项目质疑官 / Critic）
  - `rag/`       检索（解析 / 分块 / 向量化 / 检索排序）。**VectorStore 抽象 + LuceneVectorStore（FSDirectory 落盘，唯一生产实现）+ InMemoryVectorStore（离线/实验）；向量检索按 userId pre-filter，删除必须 BooleanQuery AND（严禁 deleteDocuments(Term...) 的 OR 语义）**
  - `llm/`       LlmService（封装 DeepSeek 调用 + 超时重试；发 `tools` 字段做 function calling）。**LLM HTTP 调用统一用 OkHttp（单例 OkHttpClient + 单 ConnectionPool），不混用 JDK HttpClient**（决策 2026-09-17）
  - `memory/`    跨轮记忆 + 错题本 + 间隔重复调度
  - `billing/`   QuotaService（预检/用后实扣/条件 UPDATE 防超发 + llm_usage_log 流水 + ADMIN 充值；402）
  - `security/`  JWT（JwtService/JwtAuthFilter/UserContext）、CurrentUserService、KeyResolver（PLATFORM/BYOK 路由）、UserKeyCipher（用户 key AES-GCM）
  - `domain/`    实体与持久化（含 User / UserApiKey / Quota）

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
