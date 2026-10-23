# 模拟面试 Agent 决策运行时

更新：2026-10-21。用户已确认将核心决策从 Java 固定编排改为 Agent 动态判断；本批代码与验证分别登记，不把实现存在等同于真实模型质量达标。

## 1. 需求与边界

面试官结合 JD、所选简历和现场回答，决定考什么、追什么、查什么，以及何时换题或结束。分数只是依据之一：机制缺失追原理、贡献模糊追本人行动、指标缺依据追测量、矛盾先澄清、明确不会可换题、证据充分及时停止。

Java 继续管理权限、计费、状态落库与执行预算。用户本轮授权内部决策架构调整；HTTP 请求/响应字段、同步首题/报告及既有 SSE 事件形状保持兼容。不引入新的 Agent 框架、MQ 或 WebFlux。

## 2. 内部模型与存储

| 对象 | 内容与责任 |
|---|---|
| State | version、Snapshot、本场最新 plan、lastDecision；END及查证后的最后行动/证据也保留，可从 DB 重建上下文 |
| Snapshot | 开场读取本人 JD 与显式所选 resume 原文；未选简历为空，不隐式采用其他版本 |
| Decision | action、实际 role、question、goal、reason、plan、evidenceIds、有限 evidence/工具状态及 modelCalls/toolCalls/elapsedMs/tokens；不存内部推理链 |
| Evidence | id、sourceType、sourceId、有限 content；材料/回答是声明，不自动成为已核实事实 |
| TurnBrief | 向 Critic 传真实轮次、问题、回答、评分、赛道和有限证据；原五参构造兼容 |

Flyway V5 新增 `interview_session.agent_state LONGTEXT NULL` 和 `interview_turn.agent_metadata LONGTEXT NULL`，分别保存 State 和出题 Decision；迁移先查列是否存在，兼容本地mysql/ddl-auto:update已建列。评分仍独立放 `score_json`，检索ID仍放 `retrieve_refs`；不混入用户可见响应。

`app.interview.agent-enabled` 默认 true，仅决定新场是否初始化 Agent。`agent_state=NULL` 的旧场继续 legacy；已有 Agent 场按其持久化版本和角色恢复，不因全局开关切换而改用取模判分。prod 发布先备份，再运行 V5；不回填旧场。

## 3. 沿用现栈的执行循环

`InterviewAgentRuntime → LlmPort.chat → LlmService → ModelRouter/ChatProvider` 使用 FAST 非思考档和原生 `tools`。Provider/Port 返回完整 `tool_calls`；运行时注册白名单、校验参数、执行工具，将 assistant 工具请求与 tool 结果回填 messages，再调用模型作出终结行动。

| 行动 | 含义 | 代码约束 |
|---|---|---|
| ASK_MAIN | 新考点，可重新选面试官 | 必须是启用角色，不能超过 maxQuestions |
| PROBE | 深挖当前回答 | 保留当前角色/赛道，layer+1 且≤2 |
| CLARIFY | 澄清矛盾、贡献或证据 | 与 PROBE 共用两层预算，不绕过层数 |
| END | 结束并进入现有 Critic 路径 | 至少已有回答，开场不能直接结束 |

模型必须提供简短 goal/reason、非空有限 plan 和已获取的证据 ID。执行器拒绝未知动作、未启用角色、非法字段、未取得的引用、重复题及越界行动，拒绝后只在剩余调用预算内反馈修正。重复题检查是 Unicode 归一化并去标点空白后的精确比较，不保证语义去重；岗位适配与必考覆盖仍需内容评测。

## 4. 工具与证据范围

| 只读工具 | 参数 | 服务端范围 |
|---|---|---|
| search_candidate_materials | query，非空且≤300字符 | 当前用户、本场所选简历快照及本人项目/笔记/真实面试记录 |
| get_interview_turns | 可选整数turnSeq | 无参取本场最近8轮概要；传轮次时取本场该题/答（最多1000/6000字符）及评分概要 |
| get_competency_progress | 无 | 本场 plan 与已考察轨迹 |
| get_scoring_rubric | 无 | 技术/行为四维及不确定性规则，尚非完整岗位分级 rubric |

用户/租户/会话由服务端绑定，工具不接受模型传入这些 ID；未知工具拒绝，不开放改分、扣费、写资料或删除操作。工具结果区分 OK、NOT_FOUND、INVALID_ARGUMENT、NOT_ALLOWED 和 ERROR；执行失败不能解释为没有资料。资料中的提示词不能改写系统权限或行动协议。

JD 与所选简历在开场存快照；其他简历版本始终排除。项目等资料读取当前本人索引，获取的有限片段随决策保存，供后续评分和报告追溯。这是**取证时快照**，并非所有材料都在开场冻结。当前向量候选最多20条，按本人及资料来源复核后最多返回5条、每条1500字符；候选中其他简历过多可能影响召回，未实现全部来源的预过滤排序。

## 5. 预算、计费与失败

| 配置（app.interview） | 默认 | 范围/含义 |
|---|---|---|
| max-model-calls | 3 | 每次决策最多模型调用，配置约束1–5 |
| max-tool-calls | 4 | 每次决策总工具调用，配置约束0–8 |
| max-output-tokens | 4096 | 单次模型输出上限，配置约束4096–8192 |
| max-step-tokens | 64000 | 决策循环累计模型用量边界，配置约束4096–100000 |
| max-step-millis | 60000 | 步骤间检查的时间边界，配置约束1000–180000ms |

初始观测主动向约26000字符压缩：保留轮次/考察目标，旧答最多150字符、最近四轮最多1600字符，超预算时进一步压缩旧问答、评分摘要和证据；不删除DB原文，需核对时可按turnSeq读取。每次发模型请求前，messages序列化不得超过48000字符。token/time边界在步骤间及响应后检查，不能中途取消已经发出的HTTP调用；单次调用仍由Provider超时约束，可能越过步骤预算。缺usage使用字符估算。决策预算不含独立判分、Critic或embedding完整单场成本，不承诺整场60秒或绝对token硬限额。

所有对话和 embedding 沿用统一计费；一次决策可多次调用。DeepSeek BYOK 每个模型调用前扣服务费；PLATFORM/所有 Qwen 对话按 usage 实扣；每次资料检索的 embedding 仍走平台 key 按 token 扣。业务事务回滚不退已发生调用费用，工具错误或无命中不代表没有产生 embedding 成本。

402/409穿透；answer对QuotaExhaustedException/IllegalStateException设置noRollbackFor，判分已完成后若下一决策遇到402/409，已保存的回答与评分仍提交，避免已付费判分因后续失败丢失。Agent下一步生成遇到非业务失败返回QUESTION_FAILED，同样保留回答/评分。API提交完全相同的原答恢复决策，不再次评分或写错题；恢复的新模型/检索调用仍计费，不同回答不能覆盖已保存内容。UI在QUESTION_FAILED后提供“继续生成下一步”，复用原答并提示本次调用扣积分，也可结束查看已有部分报告。

同场answer/end共享进程guard和作用域session行锁（lock.timeout hint=0）；单实例guard与H2竞争已测，多实例/MySQL NOWAIT尚未实测。H2锁竞争可能断连接并翻译成JpaSystemException，测试只保证竞争者不触发第二次判分，不假定各数据库异常类型相同。仍无请求ID/持久任务系统，不能宣称全部重试幂等；长事务、异步、正文流式、事件游标和报告失败单独重试仍待专项，现有降级报告缓存语义保留。

## 6. 交付任务与验收

1. Provider 原生工具返回→执行→回填→再决策；测试非法工具、参数/归属/调用与时间token预算，不只断言 tools 被发送。
2. 新场动态选角色和动作、低分换题、高分核实、矛盾查证、明确不会停止；角色/两层/题量/重复题守卫覆盖离线测试。
3. V5 字段持久化 State/Decision；重建运行时仍保留实际角色/计划/有限证据；旧 NULL 场流程回归。
4. 多版简历排除、JD/简历快照、本人索引检索和双账号隔离；有限上下文不冒充全量材料冻结。
5. 技术/行为评分结构严格校验、失败 null 分、Critic 权威轮次与证据输入；不补造经历和数字，内容效果另做人工评阅。
6. 非业务出题失败后同文回答恢复、402/409穿透、并发实际模型调用与流水次数；不把恢复决策称为零费用重放。

本轮验证：最终全量310项0failure/error、8skip（6MySQL+2个opt-in），package成功；Chrome fixture17组/360与390px通过，真实jar首页/注册/鉴权额度通过（H2，Redis未起）。新增 `AgentLiveSmokeTest` 默认skip，显式 `-Dagent.live=true` 才运行；既有真机用例沿旧规则。同一合成案例真实DeepSeek前后两次、9对话/19283token，跑通决策/判分/追问/报告；样例未主动调用工具，原生工具闭环由离线provider/Runtime用例验证。人工仍需复核预设经历/复合问法/岗位覆盖；小样本不证明总体内容质量。日志与样例在 `target/agent-*`，复跑 `.\mvnw.cmd test "-Dtest=AgentLiveSmokeTest" "-Dagent.live=true"` 会发生平台用量。MySQL/Flyway、多实例锁、全场质量/成本对照和生产发布仍待独立验收，未自动提交/部署。
