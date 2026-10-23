# 领域硬编码热修摘要

问题来自2026-10-07：JD=1111、简历=2222也出Java后端题。2026-10-08已热修，当前代码保留实现，不重复立项。原分工/逐文件行号/待执行指令已压缩；完整记录在本机备份。

## 根因与实际修复

| 原问题 | 当前实现 |
|---|---|
| 技术/项目/Critic人格写死Java后端 | Role、LlmService判分、CriticService去领域化 |
| JD无法确定方向时仍默认后端 | DomainInference规则推断；编排优先JdProfile.jobType，随后JD文本 |
| 无意义JD/资料缺失仍暗含技术栈 | buildDomainDirective给出无领域依据约束，不默认Java后端 |
| 领域信号未贯穿后续 | 首题/下一主问题/追问/Critic注入领域约束 |

相关代码：`agent/DomainInference.java`、`agent/InterviewOrchestrator.java`、`agent/Role.java`、`agent/CriticService.java`、`llm/LlmService.java`。确定性覆盖在DomainInferenceTest；真实题目是否偏域仍需固定样例人工评阅。无意义JD防御仅在对应条件命中，不宣称任意短JD都被准确理解。

## 保留的后续工作

规则推断不等于岗位能力计划；本场版本/后续证据、RAG空结果的明确未知状态仍待MI-01/02设计。旧exam_track/flow_template/question_bank是长期候选方向，未实现，也不为解决本问题先引入。

```powershell
.\mvnw.cmd test "-Dtest=DomainInferenceTest"
.\mvnw.cmd test
```

以上是复跑命令，本轮未执行。人工评阅固定无意义/前端/Python/JD缺失场景，检查不偏默认域、未知经历不脑补；不要把角色prompt去词视为总体正确性证明。当前安排见 [主计划](product-experience-iteration-plan.md)。
