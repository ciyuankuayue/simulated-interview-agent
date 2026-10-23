# 前端与双陪考交付摘要（2026-10-19）

本批已提交 `4ac0a87`；原逐段“未commit”已过期。线上版本仍需服务器证据；prod公开请求修复另见 `90445b5` 与 [更新指南](server-update-guide.md)。本说明描述实际交付，不再独立安排下一批。

## 已交付

- Vue3 CDN+Spring页面，浅色青绿公开封面、登录注册、八个工作页、桌面及360/390px布局。未迁Vite。
- JD rawText/数组响应、/api/user额度/设置/Key、简历加载启用接线；资料md/txt本地读取（≤1MB）预览确认后走文本接口。
- 问答草稿、防重复点击、真实等待/错误、客户端SSE重连去重、自然/手动结束报告入口；退出取消请求并清理账号状态、拦截迟到响应，JWT仅内存。
- 报告/逐轮问答/Markdown导出、历史筛选、分数趋势、错题复习和失败提示保留。
- 陪考小暖与通考小师独立聊天/草稿/入口，登录承接所选角色、返回原页面；StudyCoachService/Controller `/api/coach/chat|reset` 用LlmPort，最多8成功问答、30分钟闲置清理、并发guard；不自动读取报告/资料，无永久记忆。
- 积分统一：1积分=500token当量、最多3位小数；新用户100积分，DeepSeek BYOK每模型调用1积分，一轮可多次。Qwen/embedding平台实扣；DB/API仍token，存量不重置。
- 内测补额微信13481264947覆盖402/弹窗/设置，无支付接口；小暖尊重倾诉、具体回应、求办法给一个可选小动作，报喜不脑补经历。空正文返回真实错误、保留草稿；删除未经核实的硬编码热线，语境护栏仍待改进。

## 当时验证与复跑

- 最终历史全量212项，0failure/error、6MySQL skip，package成功；中间193/203/205是同批阶段记录，不重复列为当前基线。
- Chrome fixture16组覆盖JD/导入/简历/设置/BYOK/问答/重连/去重/402草稿/结束/报告/错题/双角色/换账号及迟到响应；1440px截图、360/390px八页无横向溢出。
- 真实Spring/H2验证注册/JWT/额度/设置/刷新重登；小师DeepSeek回复非空且实扣；小暖4组合成样例人工阅读，报喜脑补修正后复测。样本很小，不代表总体内容质量或线上性能。
- 本地历史日志截图 `target/frontend-*`、`target/points-*`、`target/warm-beta-*`、`target/warm-quality-*.json` 是过程文件，可能随clean消失；持久测量证据另存measurements。

浏览器脚本需要Node、Playwright和Chrome/Edge，不增加生产依赖：

```powershell
node scripts/frontend-smoke.cjs
```

Codex本机可由load_workspace_dependencies定位Node包并设置NODE_PATH。FRONTEND_BROWSER指定浏览器，FRONTEND_VUE_RUNTIME可供离线Vue运行时。脚本使用HTTP/SSE确定性数据，不调用模型。

## 尚未交付

服务端SSE游标/恢复与模型真流式、短事务/报告重试、指定简历语料隔离、PDF/docx/OCR、资料删除联动、经授权的报告教练、Vite/CDN移除、持久提醒。当前顺序统一见 [主计划](product-experience-iteration-plan.md)，细节见 [专项清单](companion-and-interview-quality-backlog.md)。
