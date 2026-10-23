# 模拟面试提问量化测试（2026-10-20）

## 结论与适用范围

完成一次真实 API、小样本、交替顺序的配置对照：相同 `deepseek-flash`、相同 prompt 和 8192 输出预算，比较请求开启思考与当前非思考出题。**这是配置对照，不是历史优化前后，不能写成“上线优化了某个百分比”。**

六个合成业务案例 × 两次重复 × 两种配置 = 24 条正式请求，全部 HTTP 200 且首题非空；另有两条预热，不计入延迟/质量统计。没有使用真实用户资料，也没有修改生产业务代码或默认模型配置。

非思考组在本批样例中响应更快，辅助评分未显示整体得分降低；但样本少、只有六个独立业务案例，且评分者是参与设计的 Codex 助手，**不足以证明总体质量不下降或建立稳定生产 P95**。

## 实测对照

单位 ms；P50/P95 使用 nearest-rank 分位数。每组仅 12 条，P95 恰为最大值。

| 指标 | A：请求开启思考 | B：当前非思考 | 口径 |
|---|---:|---:|---|
| 单次首题 API 响应 P50 / P95 | 991.084 / 2210.096 | 873.713 / 1207.060 | 本机 HTTP POST `/api/interview/start` 至完整响应 |
| RAG 整体 P50 / P95 | 125.051 / 338.908 | 117.997 / 326.631 | 查询向量化、额度检查/结算、Lucene 检索 |
| 查询向量化 P50 / P95 | 115.879 / 328.749 | 107.900 / 314.256 | 真实 DashScope embedding 调用 |
| Lucene 检索 P50 / P95 | 2.148 / 3.172 | 2.226 / 2.983 | `VectorStore.search`，不含 embedding，有探针开销 |
| 出题服务 P50 / P95 | 846.721 / 2071.617 | 739.583 / 1035.249 | `followUp`，含路由、计费及模型调用 |
| 模型 Provider P50 / P95 | 839.027 / 2063.626 | 731.369 / 1027.976 | 含网络、模型生成、重试及响应解析，不是 GPU 推理时间 |
| 提问质量均分（0–8） | 7.58 | 7.67 | 本助手隐藏配置标签辅助评阅，不是独立专家验证 |
| 提问质量达标数 | 10/12 | 12/12 | 总分≥6且四维均≥1；阈值在调用前规定 |
| 请求成功数 | 12/12 | 12/12 | HTTP 200 且非空首题 |
| 正式样本对话 token | 6559 | 5844 | 厂商返回的 usage；未换算货币费用 |

两组使用相同检索实现；RAG 时间差只反映本次波动，**没有实施或证明 RAG 提速**。各阶段分位数不可直接相加。当前试验不测答题后的评分/追问、完整报告、浏览器渲染、首 token 时间或线上网络。

## 测试设计与可追溯材料

- 场景：MySQL 事务/MVCC、Redis 一致性、线程池、库存项目质疑、STAR 冲突处理、Python asyncio（检验是否错误套用 Java）。全部为合成资料，不代表真实候选人经历。
- 每例固定 JD、岗位级别、角色和资料；使用本场同一 JD，逐例替换候选人材料。六例内每条请求 prompt SHA-256 一致，比较结果无输入漂移。
- 两种配置使用同一厂商和模型名，仅测试替换出题的 thinking 开关/逻辑档位；未比较不同厂商或不同规模模型。配置表示请求的开关，未保存或评价模型内部推理过程。
- A/B 顺序按案例和重复次数交替；每种配置一次预热。串行低并发，避免并发负载成为混杂因素；没有固定生成随机种子，因此输出仍有随机性。
- 使用生产 Controller、编排器、LlmService、Provider、RagService 和 Lucene 路径；只在测试中加探针和切换出题配置。沿用生产 KeyResolver 与计费入口，测试账本位于独立 H2，实际外部 API 用量仍发生。
- 环境：Windows amd64、JDK 21.0.12.1、嵌入式 Tomcat 随机端口；独立 H2、Lucene ByteBuffersDirectory。通用知识来源按既有测试语料筛选出 18 条，每例再加入当前候选人资料，索引约 19 块，**不是历史 150 篇检索评测的规模**。
- 时间用 `System.nanoTime` 测量；测试探针会增加观测开销，尤其影响毫秒级纯检索；本机每请求创建 HTTP 客户端，不能当作真实浏览器持久连接时延。
- 源码基线 `90445b5b682e23ffb6f4ce33281c8eb4fdef521b`；测试源码和主要生产调用路径的 SHA-256 记录于 manifest，未提交测试代码。

固定结果与证据位于 [measurements/2026-10-20-first-question](measurements/2026-10-20-first-question/report.md)：

- [manifest](measurements/2026-10-20-first-question/manifest.json)：环境、口径、源码与输入哈希。
- [原始逐请求记录](measurements/2026-10-20-first-question/samples.jsonl)：含预热、顺序、分段耗时、usage、问题及引用 ID；没有 Key 或推理链。
- [逐模式统计](measurements/2026-10-20-first-question/summary.json)、[合成案例](measurements/2026-10-20-first-question/cases.json)。
- [隐藏配置标签的题目](measurements/2026-10-20-first-question/quality-blind.json)、[评分与逐题理由](measurements/2026-10-20-first-question/quality-grades.json)、[评分者与锚点说明](measurements/2026-10-20-first-question/quality-review.md)、[配置映射](measurements/2026-10-20-first-question/quality-key.json)。

## 质量标准、发现与局限

调用前定义四维各 0–2：提问相关性、岗位/角色适配、问题深度、证据约束。总分至少 6 且每维至少 1 为本次达标。随后对随机编号题目评分，先落评分文件再关联配置；具体评分锚点与局限已保留。

发现两条严重无依据前提（均在本次思考组）：

1. Q03 以“你们线上”引入无界队列、8 线程、大促 RT 与 CPU 数字，资料只有报表任务和有界队列，未将题目明确写成假设。
2. Q09 假定库存条件 UPDATE 已发生失效及一致性事故，并要求复述定位修复，资料没有该经历。

另外 Q02/Q14/Q22 有轻微未核实经历语气；Q17 只问处理方式，没有明确要求原理或边界。所有原题保留，用户或独立面试官可以复评。**不能由这两条错误推导“思考模式更爱幻觉”或由非思考组达标 12/12 推导其总体无幻觉。**

下一步：扩大独立案例，增加不同岗位级别、资料缺失、矛盾声明、明确不会及多轮追问；邀请独立评分者核对评分一致性，再定义质量非劣效门槛与延迟预算。本次没有调整业务 prompt 或根据结果挑选重跑样本。

## 历史 RAG 证据（不是本次重跑）

核对 2026-10-07 留存原始日志，150 篇合成文档、30 条查询：

| 策略 | Recall@5 | MRR@10 |
|---|---:|---:|
| 纯向量 | 96.7% | 0.824 |
| BM25 | 86.7% | 0.700 |
| RRF 混合 | 96.7% | 0.825 |

历史查询 embedding 均值 101.433ms、P95 123ms。旧脚本先取整毫秒再汇总，纯向量 search P95 出现 0ms，不能理解成零成本，也不能与本次带探针、约 19 块的测试直接比较。原始连续日志摘录见 [historical-rag-log-excerpt.txt](measurements/2026-10-20-first-question/historical-rag-log-excerpt.txt)。

结论是该合成集合中混合检索召回与纯向量持平，不能声称有显著召回提升。历史 ground truth 主要按目标文档构造，并非经过独立专家全面审核的真实用户集合。

## 复跑与测试结果

真实调用必须显式开启（会消耗环境变量中的平台 API 用量）：

```powershell
.\mvnw.cmd test "-Dtest=InterviewMeasurementTest" "-Dmeasurement.live=true"
python scripts/summarize_interview_measurement.py target/interview-measurement/<本次目录>
```

生成新的 `quality-blind.json` 后应重新评分；不能沿用本次成绩。没有评分文件时汇总脚本明确显示“未评分”。`quality-grades.json` 的约定格式见脚本说明；已有归档结果也可传入脚本复算统计。

- 首次沙盒执行在 JVM attach 探针阶段因 Windows pipe 权限失败，未形成有效样本；经授权的运行成功，日志 `target/interview-measurement-live.log`：1 test，0 failure/error/skip，BUILD SUCCESS，26 次请求完整记录。
- 不传 `measurement.live=true` 的默认验证：`target/interview-measurement-default-skip.log`，1 test skipped，BUILD SUCCESS；不启动付费测量。
- 本次共 26 次成功对话，厂商报告 13367 token；50 次向量调用（含准备语料），报告 2120 token。未读取厂商账单，不声称实际人民币费用。
- 只新增测试、合成 fixture、汇总脚本与文档，未修改 `src/main`、线上配置、数据库或对外 API，未自动 commit/push。没有为本次测量重新执行无关的全量回归。

## 面试可用表述

> 我用六类合成场景做了 24 条正式首题请求，同模型、同输入、同预算交替比较思考与非思考出题。本机非思考组首题响应中位数约 874ms，思考组约 991ms；模型 Provider 中位数分别约 731ms 和 839ms。检索主体约 2ms，RAG 主要耗时在网络向量化。四维辅助评阅中非思考组没有出现整体降分，但这只是小样本和助手评分，我没有把它描述为质量非劣效证明，也没有将配置对照冒充历史优化比例。
