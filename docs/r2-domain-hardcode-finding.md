# R2 C4 领域硬编码排查与热修记录

> 来源：2026-10-07 用户实测（JD 填 `1111`、简历填 `2222`，模拟面试仍出后端题，全程未提后端）。
> 状态：**已于 2026-10-08 热修，不进入下一轮迭代**，由 Trea 跑 build/test 验收。
> 纪律：本文件只读事实源 + 改码已获用户明确授权（"现在马上修，出文件，我让 trea 跑"），未自动 commit。

---

## 一句话结论

不是偶发 bug，是「面试领域」被硬编码进角色 prompt 的架构缺陷。填 `1111`/`2222` 只是把缺陷放大显现——即使填真实前端 JD，只要角色写死"Java 后端面试官"，照样偏后端。

---

## 一、根因（代码证据，4 处）

| # | 位置 | 问题 |
|---|---|---|
| 1 | `agent/Role.java` L18/28/55 | `TECH_DIGGER`/`PROJECT_CHALLENGER`/`CRITIC` 的 systemPrompt **首行写死**"你是资深 Java 后端面试官/教练"——领域锁进角色人格 |
| 2 | `Role.java` L84-87 `questionTask()` | 出题指令锁死"紧扣 JD 考点，只考技术/项目硬考点"；而 JD=1111 根本没考点 |
| 3 | `InterviewOrchestrator.java`（修复前） | JD(1111)+简历(2222) 只被当作"上下文框架"塞进 user prompt；角色身份已先锁 Java 后端，JD/简历只是可选项 |
| 4 | `LlmService.java` L61 / `CriticService.java` L31 | 判分与报告 systemPrompt 同样写死"资深 Java 后端面试官/教练"——fail-open 到默认域 |

**因果链**：领域信号(0，1111/2222 无语义) + 身份锁死(Java 后端) → 模型无领域线索，只能按写死人格出题 → 用户没写"后端"，系统却以"后端面试官"自居。

---

## 二、工程架构视角的根因

1. **关注点混淆**：领域语义（Java 后端）和角色行为（追问/纠正规则）被揉在一个 prompt 字符串里。角色该只管"怎么问"，领域该是独立数据。
2. **缺「领域识别」组件**：整条链路默认求职=Java 后端，没有任何一步从 JD/简历文本推断 target domain。
3. **fail-open 到默认域**：空 JD / 无资料时，系统选择"静默出题"而非"要求补全资料"或"走通用题库"——把不确定性偷偷变成偏见。
4. **不可配置/不可测/不可多租户**：领域写死在 `enum` 常量里，无法单测、无法按 track 差异化（考公/考研 track 上线会再踩一次）。

---

## 三、方案与本次落地（S1/S2/S3 已改码）

| 方案 | 设计意图 | 本次是否落地 | 落地内容 |
|---|---|---|---|
| **S1 角色 persona 去领域化** | 只留"怎么问"，领域词剥离 | ✅ 已落地 | `Role.java` 三处 + `CriticService.SYSTEM` + `LlmService.SCORE_SYSTEM` 移除"Java 后端"，改"资深技术面试官/面试教练" |
| **S2 新增领域识别组件** | `start` 时从 JD+简历推断 targetDomain | ✅ 已落地（规则版） | 新增 `agent/DomainInference`（零 LLM、可单测）；`InterviewOrchestrator.resolveDomain` 优先取 `JdProfile.jobType`，否则文本推断，注入"【领域约束】目标岗位方向：X" |
| **S3 空/无意义 JD 防御** | 不静默默认后端 | ✅ 已落地 | `buildDomainDirective`：JD≤8 字/纯数字且资料为空 → 显式"严禁假设任何技术栈、不得默认 Java 后端、出通用基础题" |
| S4 接入 R2 脊柱 | 领域彻底数据驱动 | ⏸ 留待 B1–B3 | exam_track + flow_template + question_bank 持久化 targetDomain |
| S5 RAG 空结果不 fail-open | 显式信号 | ⏸ 留待 R2 | 空检索改"请求补全/走通用题库"而非空串 |

> 注：`JdProfile` 本就有 `jobType` 字段（长度 64），原 `start` 流程从未使用——本次把它作为领域信号的首选来源，是最小代价的正确修复。

---

## 四、本次实际改动文件清单（Trea 验收对象）

**新增**
- `src/main/java/com/bupt/mockinterview/agent/DomainInference.java` — 领域识别组件（规则版，9 条 Rule，前端优先于后端避免误吞）
- `src/test/java/com/bupt/mockinterview/agent/DomainInferenceTest.java` — 6 个确定性单测（前端/Java后端/1111空/考公/空输入/简历片段）

**修改（生产源码，已拔掉所有"Java 后端"硬编码）**
- `src/main/java/com/bupt/mockinterview/agent/Role.java` — TECH_DIGGER / PROJECT_CHALLENGER / CRITIC 三处 persona
- `src/main/java/com/bupt/mockinterview/agent/CriticService.java` — SYSTEM 去领域化 + `buildUserPrompt` 注入领域约束
- `src/main/java/com/bupt/mockinterview/llm/LlmService.java` — SCORE_SYSTEM 去领域化
- `src/main/java/com/bupt/mockinterview/agent/InterviewOrchestrator.java` — 新增 `resolveDomain` / `buildDomainDirective` / `isMeaningless`；首题、`nextMainQuestion`、追问、Critic 四处均注入领域约束

**未动**（说明）
- `src/test/.../UserIsolationTest.java` 等测试里的"Java 后端"是**合法 JD 测试数据**，非 bug，不动。
- `rag/experiment/CorpusLoader.java` 加载 `java-backend-knowledge.md` 属 `experiment` 包，非生产链路，留待 R2 知识库重构处理。
- `web/MaterialController.java:270` 仅为接口文档注释示例，无逻辑影响，未改。

---

## 五、验证 / 验收方案（Trea 执行）

### 5.1 编译打包（必过）
```powershell
.\mvnw.cmd -DskipTests package
```

### 5.2 硬编码断言（grep 应为 0 命中）
```powershell
Select-String -Path "src/main/java/com/bupt/mockinterview/agent/Role.java",
  "src/main/java/com/bupt/mockinterview/agent/CriticService.java",
  "src/main/java/com/bupt/mockinterview/llm/LlmService.java",
  "src/main/java/com/bupt/mockinterview/agent/InterviewOrchestrator.java" -Pattern "Java 后端"
# 期望：无输出（0 匹配）
```

### 5.3 单测（领域识别组件，确定性、零 LLM）
```powershell
.\mvnw.cmd test -Dtest=DomainInferenceTest
# 期望：Tests run: 6, Failures: 0
```

### 5.4 全量回归（Trea 跑，关注不回归）
```powershell
.\mvnw.cmd test
# 期望：Failures: 0（既有 InterviewFlowTest / T6RealRoundTripTest / Critic 相关用例在 de-domain 后行为应一致）
```

### 5.5 真机 E2E（复现原 bug 的闭合验证）
1. 注册/登录 → 新建 JD 填 `1111`、简历填 `2222` → 开启模拟。
2. **断言**：首题不应出现任何后端专属知识点（如 JVM/Spring/MySQL 锁），应为通用计算机软件基础题。
3. 另开一场：JD 填"招聘前端工程师，熟练 Vue/React" → 开启模拟。
4. **断言**：出题偏向前端（Vue/React/浏览器/JS 等），不再默认后端。
5. 结束任意一场 → Critic 报告的 `matchScore` 与讲解应贴合所选领域，不套后端模板。

> E2E 2/4 为 LLM 生成内容，非确定性；以"是否明显偏后端"为人工判据，grep 断言 + 单测为确定性门禁。

---

## 六、对 R2 计划的影响

- **C4 已从「待做」转为「已热修」**：`docs/r2-iteration-plan.md` T0 表格与 C4 小节已标注 ✅ 热修，本项**不进下一轮迭代排期**。
- **B1–B3 脊柱收口规则不变**：本次 S2 是规则版轻量识别，B1–B3 仍须把 `targetDomain` 持久化进 `JdProfile`/`exam_track` 并数据驱动出题——避免"把硬编码从一处挪到另一处"（persona 拼 domain 字符串）的伪修复。
- **耗时影响**：T0 原估 C4=1.5d 已基本消耗（已改码），R2 总估算无需重排 C4 行；后续 B1–B3 仍按原计划推进。

---

## 七、给 Trea 的执行提示

- 构建用 `.\mvnw.cmd`（**勿用 Git Bash 跑 mvn**）。
- 改完由用户审，**不自动 commit**。
- 若 `.\mvnw.cmd test` 有失败，优先看是否某用例误依赖了"Java 后端"人格文本（应改为依赖 JD 数据），而非回退本次修复。
