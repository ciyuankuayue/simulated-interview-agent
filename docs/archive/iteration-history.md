# 迭代历史摘要

整理：2026-10-21。用于追溯已完成工作和被替代的方案，**不是当前待办或线上状态**。当前排序见 [主计划](../product-experience-iteration-plan.md)，契约见api.md/domain.md。

## 里程碑与历史验证

| 阶段 | 已落地内容 / 历史证据 |
|---|---|
| MVP T1～T12（9月） | JD/资料/检索→多角色追问→Critic→错题间隔重复；实时只追问/一句纠正，讲解归报告；首题同步返回；最大8主问题、追问最多2层。T11/T12曾有页面KeyVault及双令牌，后整体删除 |
| R1（10月初） | User/JWT/归属查询/索引、前端内存JWT；R1-3平台/BYOK加密路由取代Vault；402/409贯穿降级边界 |
| R2（49529b2） | token账本/usage流水、用后实扣与BYOK固定费、条件UPDATE+REQUIRES_NEW、ADMIN补额；83项历史回归 |
| R4（edeaa10） | VectorStore抽象、Lucene9.12.3 FSDirectory持久化、用户预过滤、替换事务/AND删除/dev孤儿清理；92项历史回归；不引生产LangChain4j |
| R3（10月3日） | ChatProvider/ModelRouter、DeepSeek与Qwen、用户厂商设置与provider:model流水；Qwen恒平台key/按token；108项历史回归 |
| R7（c4f5f6c） | 行为角色、STAR四维、ScoreTrack、Critic行为段与权威赛道覆盖；127项历史回归 |
| 领域热修（10月8日） | persona去“Java后端”、DomainInference与无意义JD约束，避免无资料默认后端；领域数据驱动仍未实现 |
| R2-T0（6批） | tenant_id/13表分区、prod/Flyway、ADMIN引导、strict向量缺user拒绝、50×3并发、孤儿巡检、渗透矩阵；187项历史回归。批3 c2e09d5、批4 e9b83a8、批5 8321810、批6 e4f30e9 |
| R5-1（232001c） | 3类6个真实依赖测试：HTTP/JWT/SQL租户、PLATFORM/BYOK/InnoDB50×3、Lucene重开/预过滤。MySQL3307/Redis6379，独立IT库，TRUNCATE仅测试库；无MySQL自动skip。历史全量193项 |
| 两批已有功能（da61235/e2351a8） | 面经列表、逐轮回看、报告导出、主动复习、JD考点、趋势、历史筛选 |
| F10/F12（bc82e18） | 小暖基础聊天与短期上下文、Vue3 CDN重构，未迁Vite |
| 前端/双陪考/积分（4ac0a87，10月19日） | 公开封面与独立页面、手机布局、接口接线/账号退出清理、md/txt、小师与双入口、积分/补额提示、小暖第一轮prompt。阶段193→203→205→212项、最终6MySQL skip；有fixture浏览器及限定真实H2/模型样例验证，详见批次说明 |
| prod公开请求修复（90445b5，10月20日） | 过滤器在OSIV前为公开路径设置普通系统分区0L并finally清理，业务JWT与strict保持；新增4测试，专项16项、历史全量216项0failure/error/6skip |
| MI专项/量化（10月20日，工作区未提交） | MI-01～09全列P0/P1；首题6例×2次×2配置=24正式+2预热，原始记录与辅助评分留存；MI-09仅局部完成 |

表中测试数是各批当时记录，可能含skip，不能解释为当前通过数；本次文档整理没有重跑代码测试。每个提交是否已推送/合并/上线，需独立核对Git与服务器，不能沿用早期handoff“未提交”流水。

## 重要修订与已替代方案

- 单用户/内存向量已被多用户+Lucene落盘替代；Redis继续保留缓存等用途，不能把生产存储说成全内存。
- KeyVault/VaultAccessFilter/X-Vault-Token、保险箱密码门禁与双令牌全部废弃；现在仅JWT，前端仅内存，不落localStorage。
- “首注册者ADMIN”被公开注册恒USER+env引导ADMIN替代。
- 旧生产mysql/ddl-auto:update被prod/Flyway/strict替代；mysql仅本地真依赖联调。迁移先备份，不能只回退镜像假定schema兼容。
- 早期计费1000点/50万token、每次1token当量已过期：现默认赠50000token当量=100积分；1积分=500当量；DeepSeek BYOK每模型调用500当量=1积分；Qwen/embedding走平台实扣。DB/API保持token，旧余额不重置。
- “无前端构建链”是旧约束；实际已Vue3 CDN，Vite/Element Plus/Caddy保留为未落地工程化方向。不能将选择方向与实际完成混写。
- 向量目录最终选择单FSDirectory+用户预过滤，未采用per-tenant目录或Milvus。生产不能共享多个writer，扩容另设计。
- 原角色轮转+分数追问已可用，但不是具有完整自主工具循环的角色Agent；MI-02/03/04才是动态行动方向。透传tools不等于工具执行闭环。
- RabbitMQ不在MVP必需范围；LangChain4j仅独立对比方向，不应把框架接入当作业务能力提升。
- 旧“小暖下一批第一”被10月20日MI高优先级追加后统一收敛；小暖P0保留，当前原子任务建议评分校验。

## 机制教训（保留给后续实现）

- 构建JDK与编译目标分开：目标17、构建用21；高版本Lombok/注解处理曾失效，mvnw/enforcer现有保护，勿临时绕过。
- MySQL宿主3306曾被原生服务占用，开发Docker映射3307；改compose端口/环境要force-recreate，先看实际映射。
- `@TenantId` 不覆盖裸findById/em.find；租户必须先于Session/事务设置，公开请求也受OSIV时序影响；root仅白名单并显式谓词。
- 并发计费H2通过不替代InnoDB验证；业务回滚不会退还REQUIRES_NEW流水，失败恢复必须核对模型调用/账本次数。
- Windows Lucene测试应各用独立临时目录，关闭重开与writer锁要隔离；删源必须AND，替换分批提交可回滚。
- 全量后端测试不替代浏览器API接线与换账号迟到响应验证；fixtureE2E不等于线上性能。
- 真机评测探针曾遭Windows attach pipe权限失败，失败运行无有效样本；成功测量需保留原始数据及执行口径。

## 测量证据边界

- 历史L3 79篇/15查询/top3与历史RAG150篇/30查询、首题约19块的配置对照不是同口径实验，不直接拼成优化比例。
- 历史RAG：向量96.7%/.824，BM25 86.7%/.700，混合96.7%/.825（Recall@5/MRR@10）。不支持“混合提升21个百分点”；旧整数毫秒出现0不等于零成本。
- 首题量化有两条无依据经历前提，辅助评分不构成独立人工专家验证；同模型思考/非思考不是历史前后对比，不证明非劣效。分段分位数不能相加。
- 全部量化原始材料留在 [measurements](../measurements/2026-10-20-first-question/report.md)，汇总/复跑见 [报告](../interview-measurement-2026-10-20.md)；本次没有删除或改写样本。

## 恢复与后续记录

完整压缩前文档保存在本机忽略目录 `docs/archive/local/2026-10-21-before-compaction.zip`，含路径及SHA-256 manifest。旧 .trae 方案原件和根目录个人准备材料未动；需要历史实现细节时按需读取，不每轮加载。

以后已完成批次仅在此增加一行里程碑和证据链接；当前任务更新主计划/专项验收，本地handoff只保留当前状态。
