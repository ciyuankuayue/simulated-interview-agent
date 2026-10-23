# 项目文档导航

更新：2026-10-21。续做先读 AGENTS.md 和本地 handoff，再按任务读契约与源码。

## 当前入口

| 需要知道什么 | 唯一维护位置 |
|---|---|
| 当前迭代顺序、任务状态、下一原子任务 | [产品迭代计划](product-experience-iteration-plan.md) |
| Agent 动态决策、工具、V5与首批验收边界 | [Agent 决策运行时](agent-decision-runtime.md) |
| MI-01～09、小暖/小师、资料建设的完整验收范围 | [业务质量专项](companion-and-interview-quality-backlog.md) |
| 本地进度、工作区未提交内容、交接注意事项 | [handoff](handoff.md)（本地忽略，不随仓库分发） |
| 当前接口 / 领域与隔离机制 | [API 契约](api.md) / [领域模型](domain.md) |
| 服务器升级、备份、迁移、回退 | [服务器更新指南](server-update-guide.md) |
| 代码批次与人审规则 | [迭代纪律](iteration-discipline.md) |

主计划维护状态和先后顺序，专项清单维护验收细节；交接只记当前状态及未解决项，不继续追加逐日长日志。代码、Git 状态和当次实测优先于历史文档中的状态。

## 历史与测量

- [迭代历史摘要](archive/iteration-history.md)：已完成里程碑、撤销方案、重要机制教训及历史测试口径。
- [前端批次说明](frontend-experience-release.md)：4ac0a87 的实际交付、验证与浏览器复跑。
- [首题量化报告](interview-measurement-2026-10-20.md)：真实调用、辅助评分、局限与原始证据索引。
- [RAG 评测摘要](rag-eval-plan.md)：2026-10-07 的历史数据；不是生产全场基线。
- [领域硬编码修复摘要](r2-domain-hardcode-finding.md)：已修问题与仍未实现的范围。
- 本地 tasks.md / r2-iteration-plan.md / deployment-runbook.md / sidechat-prompt.md 保留旧入口，均不再独立安排当前迭代。
- 本地 deployment-pitfalls.md 和 l3-comparison.md 是专项历史材料，不作为当前部署命令或性能承诺。

## 2026-10-21 整理口径

压缩已完成任务的流水、重复测试数字和旧排序，保留当前约束、未完成项、决策出处和可复跑证据。没有修改 API/领域契约、业务代码、运行数据库、索引、真实用户资料或 measurements 原始样本；根目录个人面试准备材料及 .trae 计划原件保留。

本次涉及的 Markdown 原文约 241KB，整理后连同新增导航/历史摘要约 66KB，减少约 73%；handoff 从约 117KB 缩至 4KB。这里统计的是本次整理范围，不是整个仓库的文档体积。

压缩前 13 个文件（含原有未提交修改）已完整保存到本机 `docs/archive/local/2026-10-21-before-compaction.zip`；内含原路径和逐文件 SHA-256 manifest，并已校验。该目录由 .gitignore 排除，含本地过程内容，不应提交。压缩包是本机恢复材料，历史摘要随仓库保留。

以后更新只改对应事实源；测试数字写明日期、范围和 skip，发布状态需服务器证据，不能由本地 commit 推断已上线。原文在压缩包内供按需恢复，不默认加入每轮阅读。
