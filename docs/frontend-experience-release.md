# 前端体验改造 · 第一批（2026-10-19）

范围：沿用现有 Vue 3 CDN + Spring 静态资源部署，只修改页面、样式和客户端交互；不变更 API、数据库、计费规则或后端编排。未 commit、未部署。

## 已完成

- 浅色青绿视觉、公开介绍/登录注册页、独立导航；桌面与 360/390px 手机布局。
- 准备页只突出 JD、简历与练习选项；选好岗位后折叠新增表单；开始成功自动进入面试。
- JD 对齐 rawText 与数组响应；额度、模型、BYOK 全部接现有 /api/user 接口；简历加载与启用接通。
- 资料独立管理，支持不超过 1 MB 的 md/txt 本地读取、检查后保存；保留简历、项目、笔记和真实面经入口。
- 面试保留回答草稿、提交互斥、真实处理状态与计时；连接中断可重连，重放题目/评分去重。
- 网络结果不明时暂停重复作答；出题失败允许主动结束；自然结束直接显示报告入口，不重复 POST end。
- 报告按总结、补强点、逐轮讲解、行为维度组织；保留历史筛选、原始问答、Markdown 导出与分数趋势。
- 错题详情修正 review 嵌套字段与 Vue 保留属性名问题；复习有加载、防重复提交与错误提示。
- 陪考入口、快捷开场、返回练习；服务错误保留输入并提示真实原因。
- 退出清空账号业务状态，取消旧请求并拦截迟到响应；JWT 仍仅内存，刷新需重登；展示内容使用 Vue 文本插值。

## 验证

- mvnw test：193 项，0 failure / 0 error / 6 skip（真实 MySQL 未启动），BUILD SUCCESS；含可运行的模型真机测试。
- mvnw -DskipTests package：BUILD SUCCESS。
- scripts/frontend-smoke.cjs：真实 Chrome + 本地 HTTP/SSE 确定性数据，无模型调用；覆盖 JD、文本文件导入、简历复用、设置/BYOK、问答/重连/去重、402 草稿、自然/手动结束、报告/导出失败、错题、陪伴错误、账号切换及迟到响应。
- 1440px 截图检查；360/390px 七页面无文档横向溢出；浏览器无运行时错误、无意外 API 路径，未使用 localStorage/sessionStorage。
- 真实 Spring/H2 浏览器冒烟：注册/JWT、额度、模型切换与回读、刷新后重登均通过。完整面试浏览器回归使用确定性数据，不将其当作线上性能验证。
- JS 语法检查、git diff --check 通过。日志与截图位于 target/frontend-*，不提交。

## 浏览器回归复跑

需要开发机 Playwright 与 Chrome/Edge；不增加生产依赖。在已能 require('playwright') 的 Node 环境运行：

```powershell
node scripts/frontend-smoke.cjs
```

Codex 本机可先把 NODE_PATH 指向 load_workspace_dependencies 返回的 Node packages 目录。脚本自动选择已安装的 Chrome/Edge；其他环境用 FRONTEND_BROWSER 指定浏览器。FRONTEND_VUE_RUNTIME 可选，用于离线提供已有 Vue global 运行时。

## 留给下一批

后端当前仍整批产生 SSE 结果；本批只改善等待展示与客户端消费，未宣称模型真流式或实际生成提速。后续独立评审短事务、任务状态、事件游标、报告恢复和流式结算。

PDF/docx/OCR、资料删除与向量联动、按指定简历隔离检索、自动引用报告的个性化训练教练仍未实现；页面明确现有限制。前端工程化、Vue 本地打包及 CDN 依赖移除另批处理。

## 双陪考搭档追加（2026-10-19，用户确认命名，未 commit / 未部署）

- 封面突出“你只管坚定向前，备考这一路，我们陪你”，展示心态陪伴 → 知识答疑 → 模拟练习 → 成长复盘。
- **陪考小暖**：心态陪伴；**通考小师**：备考教练。各自页面顶部自我介绍，头像、聊天与草稿独立；两者可互相跳转并返回原页面。
- 首页及全部八个工作页面右上角提供双角色入口；未登录点击记住所选入口，登录后进入对应角色；不影响正在进行的面试与草稿。
- 新增 StudyCoachService/Controller 与 /api/coach/chat、/reset，契约记于 api.md §9、domain.md §10；沿用 LlmPort 路由计费。知识答疑不自动读取用户资料或报告。
- 新增 10 个后端用例，全量 203 项 0 failure / 0 error / 6 MySQL skip；打包成功。浏览器验证双角色独立发送/重置、失败草稿、跳转与返回、手机封面和八页布局。
- 真实 Spring/H2 + DeepSeek 验证小师回复非空且正常扣减额度；零浏览器/API 错误。截图 target/frontend-smoke/welcome.png、desktop-warm.png、desktop-coach.png、mobile-welcome.png。

## 积分调整（2026-10-19，未 commit / 未部署）

- 沿用原前端换算：1 积分 = 500 token 当量；余额与扣减明细统一显示积分，最多 3 位小数，最小 0.002 积分如实显示。页面隐藏原始 token 用量。
- 新注册默认赠送 100 积分（signup-bonus-tokens=50000）；自带 DeepSeek Key 每次模型调用收取 1 积分服务费（byok-charge-per-call=500）。界面注明一轮面试可能有多次模型调用；Qwen 与资料向量化仍按平台实际用量折算扣减。
- 保留现有 token 账本、字段及计费收口，无 schema/API 契约变更；已有余额、已用额、历史费率不回填，不给存量账户重新赠额。若部署环境另有覆盖配置，发布时需同步上述两个默认值。
- 全量测试 205 项，0 failures / 0 errors / 6 MySQL skip；package SUCCESS。新增不足 1 积分仍可用平台余额、旧额度不重置的用例；既有 BYOK 生命周期及混合并发回归更新为新费率。
- Chrome fixture 回归 16 组通过：含 1 积分服务费、0.002 积分余额/流水、旧账户 998 积分显示、402 草稿保留及双账号隔离；360/390px 八页面正常。
- 真实 Spring/H2 注册验证到账 100 积分（GET quota 内部 50000），顶部与设置显示一致、刷新仍需登录；未额外调用模型。截图 target/frontend-smoke/live-points-settings.png、live-points-mobile.png；日志 target/points-test.log、points-package.log。

## 内测补额与小暖第一轮优化（2026-10-19，未 commit / 未部署）

- 当前无支付接口。积分不足时的后端 402、弹窗和账号设置统一说明“平台目前处于内测阶段，请联系工作人员补充额度”，显示工作人员微信 **13481264947**；号码可选中复制，面试/小暖输入草稿保留。
- 小暖取消强制一两句话，改为具体回应用户处境；尊重只想倾诉的要求，不强行复盘、追问或鼓励。求办法时只给一个可选择的小动作；报喜时回应实际进步、不补写用户经历。模型与计费路由不变。
- 空模型回复不再伪装为固定安慰，沿用 504 错误让前端保留输入供重试。安全关键词护栏保留，移除未经核实的硬编码热线；未扩大为心理诊疗能力。
- 后续优先级与资料建设要求登记于 companion-and-interview-quality-backlog.md；下一批首位为小暖质量/相关开源调研，小师“解释—重点—例子—复述自测”与面试资料、元数据/检索设计随后推进。本次没有调研开源仓库、修改小师教学或新增数据库结构。
- 新增小暖 5 个服务测试及 2 个 JWT API 测试；全量 212 项 0 failures / 0 errors / 6 MySQL skip。Chrome fixture 16 组通过，含面试/陪伴 402 微信、手机弹窗与草稿恢复；360/390px 八页布局无溢出。
- 真实 Spring/H2 + DeepSeek 试聊 4 组场景，人工发现报喜脑补经历后补充约束，针对该例复测；最终报喜回复未再补写经历。只代表少量合成样例，后续仍需真实用户反馈。记录 target/warm-quality-*.json，截图 target/frontend-smoke/live-warm-listening.png、mobile-beta-quota.png。
- 复跑日志 target/warm-beta-test.log、warm-beta-package.log；本地8082隔离预览已更新，发布前仍由用户审阅 diff。
