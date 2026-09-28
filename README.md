# simulated-interview-agent（AI 模拟面试官）

> 状态：MVP 已跑通（T1–T12 完成，48 个测试用例全绿 + 真实 LLM 全链路验证）。

本地运行的 AI 模拟面试官：**贴 JD → 自动解析考点 → 两个面试官 Agent 轮番提问追问（技术深挖 / 项目质疑）→ Critic 出复盘报告 + 错题本（间隔重复）**。你的简历、笔记、项目文档、真实面试记录全部向量化入库，越用越懂你。

## 功能特性

- **资料入库 + RAG 检索**：JD 自动抽取关键词与岗位类型；简历（多版本下拉复用）、项目文档、笔记、真实面试记录切块后走向量检索（通义 embedding，1024 维）。
- **双角色轮番追问**：技术深挖官 / 项目质疑官按主问题序号轮转，可勾选只启用其一；Critic 始终在场。
- **分数驱动的追问深度**：每轮回答按 4 维度（正确性 / 完整性 / 深度 / 实证，0–8 分）打分；8 分不追问、6–7 分追问 1 层、≤5 分追问最多 2 层。
- **主问题数可设**：每场 1–8 道（快速 3 / 标准 5 / 完整 8，默认 8）；**答完最后一题自动出报告**，也可随时手动结束（接口幂等）。
- **Critic 复盘报告**：每轮讲解（纠正 / 知识补充 / 示范回答）、共性塌点、表达问题、整体注意事项、岗位匹配度评分。
- **历史可追溯**：最近 20 场面试列表，有报告的场次随时点开回看。
- **错题本 + 间隔重复**：≤5 分自动按知识点入队，固定阶梯 [1, 3, 7, 15] 天复习，≥6 分出队；条目可点开查看答崩现场与 Critic 四段讲解。
- **模型密钥保险箱**：页面录入密钥，AES-256-GCM 加密落盘，支持「机器绑定」与「密码保护」两模式，保存后热生效无需重启。
- **密码门禁（多设备 / 服务器部署）**：密码保护模式下，每次刷新页面 / 新设备 / 服务重启都必须重新输入密码拿到访问令牌，才能调用预设模型；锁定、改密码会让所有设备的令牌立即失效。密码框支持明文 / 圆点切换。
- **LLM 降级不中断**：判分 / 追问 / Critic 失败均有降级策略（跳过该步或占位报告），整场面试不会因一次 LLM 故障报废。
- **L3 检索对比实验**：同一语料上手写余弦 vs Lucene HNSW 向量库对比（召回率 / 耗时 / 代码量），结果见 [docs/l3-comparison.md](docs/l3-comparison.md)。

## 技术栈

| 层 | 选型 |
|---|---|
| 框架 | Spring Boot 3.3.5，Java 17 编译目标（**本机构建用 JDK 21**，JDK 24 被 enforcer 拒绝） |
| 构建 | Maven 3.9.9（仓库自带 mvnw，首次运行自解压，无需预装） |
| 存储 | MySQL 8（持久化）+ Redis 7（指纹去重 / 会话缓存）；本地默认 H2 内存库零依赖启动 |
| 检索 | 通义 `qwen3.7-text-embedding-flash` embedding；手写余弦检索（Lucene HNSW 仅用于 L3 对比实验） |
| LLM | DeepSeek `deepseek-flash`（追问非思考模式 / 判分与 Critic 思考模式），质量不足可切 `deepseek-v4-pro` |
| 前端 | Thymeleaf 服务端渲染 + 原生 JS（fetch / EventSource SSE），**无 npm、无前端构建链** |
| HTTP | OkHttp 单例（统一 LLM 调用 + 超时重试） |

## 项目结构

```
src/main/java/com/bupt/mockinterview/
├── web/       Controller + SSE 推流（面试主流程 / 资料管理 / 错题本 / 密钥设置 / 健康探活）
├── agent/     角色编排：手写 ReAct 状态机（InterviewOrchestrator）、Role、CriticService
├── rag/       解析 / 分块 / 向量化 / 余弦检索 / L3 对比实验（experiment 包）
├── llm/       LlmService（DeepSeek 封装、超时重试、降级），领域层只依赖 LlmPort
├── memory/    错题本 + 间隔重复调度（SpacedRepetition 固定阶梯）
├── security/  KeyVault（AES-GCM 保险箱）+ VaultAccessFilter（密码模式令牌门禁）
├── domain/    10 个实体 + 枚举 + JPA Repository
└── config/    OkHttp / RAG 装配
```

分层铁律：**领域 / 编排逻辑不直接调 LLM**，一律走 `llm` 层；Controller 只做协议适配，事件全部由编排器生产。

## 快速开始

### 1. 零依赖体验（H2 内存库）

无需 Docker、无需预设密钥（未配密钥时 embedding 自动降级为本地 FakeEmbedder，页面录入密钥后热切换）：

```powershell
.\mvnw.cmd spring-boot:run
```

访问 http://localhost:8080 。注意 H2 数据重启即清空，仅用于本地验证。

### 2. 生产形态（MySQL 8 + Redis）

```powershell
docker compose up -d                                   # MySQL 8 + Redis 7，含 healthcheck
$env:SPRING_PROFILES_ACTIVE="mysql"; .\mvnw.cmd spring-boot:run
```

> 本机 3306 被原生 MySQL80 服务占用，故容器 MySQL 发布在 **宿主 3307**（容器内仍 3306）；无冲突的环境用 `MYSQL_PORT=3306` 覆盖。改 compose 配置后须 `docker compose up -d --force-recreate` 才生效。

依赖探活：http://localhost:8080/api/health/deps —— `database` 与 `redis` 均为 `ok` 才算环境就绪（另含 deepseek 密钥来源与 embedder 真实 ping 信息）。

### 3. 其他常用命令（PowerShell，勿用 Git Bash 跑 mvn）

```powershell
.\mvnw.cmd -DskipTests package                        # 编译打包
.\mvnw.cmd test                                       # 全量测试（真实 LLM 用例无 key 自动 skip）
.\mvnw.cmd spring-boot:run "-Dserver.port=8081"       # 8080 被占用时临时换端口
```

8080 被占用时：`netstat -ano | findstr :8080` 取 PID → `taskkill /PID <pid> /F`。

JDK 无需手动配置：`mvnw.cmd` 在 `JAVA_HOME` 无效或指向 JDK 24+ 时，会自动回落到 `%USERPROFILE%\jdk-21`。

## 模型密钥配置

三个来源，**优先级：环境变量 > 页面保险箱**：

1. **环境变量**：`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`（设为用户级后新开终端自动继承）。
2. **页面保险箱**（左下角密钥卡片）：落盘 `~/.mock-interview/vault.json`（可用 `--app.vault.dir=` 覆盖），两种模式：
   - **机器绑定**：随机 DEK 加密，重启自动解锁，适合纯本机使用；
   - **密码保护**：PBKDF2 派生密钥，重启后锁定，必须输密码换取访问令牌（仅存浏览器内存，不落 localStorage）——刷新、换设备、重启都要重新输密码；点「锁定」或修改密码会踢掉全部设备。
3. 都未配置：应用照常启动，embedding 降级为 FakeEmbedder，调用模型相关功能时按降级策略处理。

> ⚠️ 部署到服务器供多设备访问时，**不要**在服务器设置密钥环境变量，应使用密码保护的保险箱，门禁才有意义。

任何接口都不会回传明文密钥。

## 使用流程

1. 启动后打开 http://localhost:8080 ；密码模式下先输入密码解锁。
2. 左栏粘贴 JD（自动解析考点），上传简历（可从历史版本下拉选择）、项目文档、笔记，勾选面试官角色与主问题数。
3. 开始面试：SSE 实时推送问题，在中栏作答；收到评分后系统自动决定追问或进入下一题。
4. 答完最后一题自动生成 Critic 报告（右栏），也可随时点「结束」手动出报告。
5. 低分知识点自动进错题本，按 [1/3/7/15] 天间隔提示复习；历史面试可随时回看报告。

## 测试

```powershell
.\mvnw.cmd test
```

- 当前 **48 个用例**：46 个离线可跑，2 个真实 LLM 链路用例在未设置密钥时自动 skip。
- 覆盖：编排状态机 / 判分与追问决策 / 错题本间隔重复规则 / SSE 事件契约与重放 / 资料 CRUD / 保险箱加解密与令牌门禁 / L3 检索对比。

## 文档

- [docs/domain.md](docs/domain.md) —— 领域模型（10 实体、判分结构、间隔重复）
- [docs/api.md](docs/api.md) —— HTTP / SSE API 契约（含鉴权令牌协议）
- [docs/handoff.md](docs/handoff.md) —— 迭代进度、关键决策与踩坑记录
- [docs/l3-comparison.md](docs/l3-comparison.md) —— 手写余弦 vs Lucene HNSW 对比实验数据
- [AGENTS.md](AGENTS.md) —— AI 协作者规则（命令、架构约束、禁区）

## 仓库

- 远端：https://gitee.com/yiyi-yiyi-yiyi-yiyi/simulated-interview-agent.git
- 本机路径：`C:\Users\19284\WorkBuddy\2026-08-25-17-57-13\simulated-interview-agent`
