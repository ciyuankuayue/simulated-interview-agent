# simulated-interview-agent（AI 模拟面试官）

## 项目简介

一个可本地运行、也可部署到服务器的 AI 模拟面试官。用户粘贴岗位 JD、上传简历与项目资料后，系统自动解析考点，由多个面试官 Agent 轮番提问与追问（技术深挖、项目质疑、行为面试），并在每场结束后生成 Critic 复盘报告与错题本。用户私有的简历、笔记、项目文档、真实面试记录会被向量化入库，用于检索增强（RAG），使追问更贴合个人背景。

## 核心功能

- **资料入库与 RAG 检索**：JD 自动抽取关键词与岗位类型；简历（支持多版本）、项目文档、笔记、真实面试记录切块后经向量模型嵌入，按用户隔离检索。
- **多面试官角色**：技术深挖官、项目质疑官、行为面试官，可在开始前勾选启用哪些角色；Critic 始终在场生成复盘。
- **分数驱动的追问**：每轮回答按正确性 / 完整性 / 深度 / 实证四个维度评分（0–8 分），高分少追问、低分深入追问。
- **可配置主问题数**：每场 1–8 道，答完最后一题自动出报告，也可随时手动结束。
- **Critic 复盘报告**：逐轮讲解（纠正 / 补充 / 示范）、共性塌点、表达问题、整体注意事项、岗位匹配度评分。
- **错题本与间隔重复**：低分知识点按知识点入队，按固定天阶梯提示复习。
- **多模型对话**：支持 DeepSeek 与通义千问两种对话厂商，用户可在设置中切换；向量化统一使用通义 embedding。
- **配额与计费**：注册赠送 token 额度，平台模式按实际用量扣减；用户也可自带 DeepSeek 密钥（BYOK 模式，对话自付 API 费）。
- **鉴权**：基于 JWT 的注册 / 登录，令牌存于内存，不落本地存储。
- **LLM 故障降级**：判分 / 追问 / 报告任意一步失败都有降级策略，整场不会因单次模型故障报废。

## 技术栈

| 层 | 选型 |
|---|---|
| 框架 | Spring Boot 3.3.5，Java 17 编译目标（构建时使用 JDK 21，JDK 24 被拒绝） |
| 构建 | Maven（项目自带 mvnw 包装器，无需预装 Maven） |
| 存储 | MySQL 8 + Redis 7（生产 / Docker）；本地默认 H2 内存库零依赖启动 |
| 检索 | 通义文本嵌入模型（1024 维），向量索引使用 Lucene FSDirectory 落盘持久化 |
| LLM | DeepSeek（追问非思考 / 判分与报告思考），或通义千问；向量化使用通义 embedding |
| 前端 | Thymeleaf 服务端渲染 + 原生 JavaScript（fetch / EventSource SSE），无前端构建链 |
| HTTP | OkHttp 单例，统一 LLM 调用与超时重试 |

分层约束：领域与编排逻辑不直接调用 LLM，统一走 `llm` 层；Controller 只做协议适配，事件全部由编排器生产。

## 环境要求

- **操作系统**：Windows（脚本使用 PowerShell 与 `.\mvnw.cmd`）；Linux / macOS 可改用对应的 mvnw 脚本。
- **Java**：构建使用 JDK 21（mvnw 自动选择，无需手动设置 JAVA_HOME）；不要使用 JDK 24。
- **Docker（可选）**：生产形态需要，用于本地起 MySQL 与 Redis。
- **大模型密钥**：DeepSeek API Key 与阿里云百炼（DashScope）API Key。未配置时相关功能降级，但应用可正常启动。

## 部署与启动

### 方式一：零依赖体验（H2 内存库）

无需 Docker、无需密钥即可启动验证：

```powershell
.\mvnw.cmd spring-boot:run
```

启动后访问 http://localhost:8080 。注意 H2 数据重启即清空，仅用于本地验证。

### 方式二：生产形态（MySQL 8 + Redis 7）

先启动依赖（默认 MySQL 容器映射到宿主 3307、Redis 映射到 6379，账号见 docker-compose.yml）：

```powershell
docker compose up -d
```

再启动应用（启用 mysql profile）：

```powershell
$env:SPRING_PROFILES_ACTIVE="mysql"
.\mvnw.cmd spring-boot:run
```

依赖探活：访问 http://localhost:8080/api/health/deps ，当 `database` 与 `redis` 均为 `ok` 时环境就绪。

### 常用命令（PowerShell，不要用 Git Bash 执行 mvn）

```powershell
.\mvnw.cmd -DskipTests package                  # 编译打包
.\mvnw.cmd test                                  # 全量测试（缺少密钥的真机用例会自动跳过）
.\mvnw.cmd spring-boot:run "-Dserver.port=8081"  # 端口被占用时换端口
```

## 环境配置（环境变量）

| 变量 | 必填 | 说明 |
|---|---|---|
| `DEEPSEEK_API_KEY` | 启用 DeepSeek 对话 / 判分时必填 | DeepSeek 平台密钥 |
| `DASHSCOPE_API_KEY` | 启用向量化与通义千问对话时必填 | 阿里云百炼平台密钥，对话与向量化共用 |
| `SPRING_PROFILES_ACTIVE` | 否（默认 H2） | 设为 `mysql` 启用 MySQL + Redis |
| `JWT_SECRET` | 生产必填 | 至少 32 字节；为空时启动自动生成，但重启后旧令牌失效 |
| `APP_DATA_KEY` | 生产必填（BYOK 场景） | 用户自带密钥的加密主密钥；为空时重启后旧密文不可解 |
| `MYSQL_HOST` / `MYSQL_PORT` / `REDIS_HOST` / `REDIS_PORT` | 否 | 覆盖默认数据库连接 |
| `APP_RAG_INDEX_DIR` | 否 | 向量索引目录，多实例部署时需各自独立 |

配置方式示例（PowerShell）：

```powershell
$env:DEEPSEEK_API_KEY="你的DeepSeek密钥"
$env:DASHSCOPE_API_KEY="你的百炼密钥"
$env:SPRING_PROFILES_ACTIVE="mysql"
.\mvnw.cmd spring-boot:run
```

## 使用方式

1. 启动后打开 http://localhost:8080 ，注册账号并登录。
2. 在资料区粘贴岗位 JD（自动解析考点），上传简历、项目文档、笔记等资料，勾选要启用的面试官角色与主问题数量。
3. 开始面试：系统通过 SSE 实时推送题目，在作答区输入回答；每轮评分后自动决定追问或进入下一题。
4. 答完最后一题自动生成 Critic 报告，也可随时点「结束」手动出报告。
5. 低分知识点自动进入错题本，按间隔重复阶梯提示复习；历史面试记录可随时回看报告。

## 使用规则与注意事项

- 必须使用 **PowerShell** 执行 `.\mvnw.cmd`，不要用 Git Bash（Git Bash 下 `.cmd` 不被识别）。
- 真实调用大模型前必须配置 `DEEPSEEK_API_KEY` 与 `DASHSCOPE_API_KEY`；未配置时 embedding 与对话会降级。
- 生产部署务必启用 `mysql` profile 并显式设置 `JWT_SECRET` 与 `APP_DATA_KEY`，否则重启后登录态与加密数据会失效。
- 向量索引落盘在本地目录；Docker / 多实例部署时应将该目录挂载为持久卷，并让每个实例使用独立目录。
- 任何接口都不会回显明文密钥。
- 端口 8080 被占用时可通过 `-Dserver.port=新端口` 切换，或通过 `netstat` 查找并结束占用进程。

## 测试

```powershell
.\mvnw.cmd test
```

测试套件包含离线单元测试与部分真实大模型链路用例；缺少密钥的真机用例会自动跳过，不影响整体结果。
