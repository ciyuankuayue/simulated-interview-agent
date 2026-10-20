# 服务器更新操作指南

更新日期：2026-10-20。适用项目：模拟面试 Agent。

适用场景：Ubuntu 服务器 `/opt/app` 已通过 `docker-compose.prod.yml` 运行应用、MySQL、Redis 和 Caddy，现在升级已有站点。本文不是全新空库的首次部署教程。

前端功能基线提交为 `4ac0a87`（前端、双陪考、积分及小暖优化）。2026-10-20 已发现该基线在 prod 下的公开请求租户上下文缺口，正式发布还须包含第 9 节所述的入口修复；以当次确认的完整修复提交为目标，不只检查 `4ac0a87`。本次还包含生产 profile、Flyway V4 和多租户改动，必须在启动新版前备份。

## 1. 先分清在哪里执行

| 操作 | 执行位置 |
|---|---|
| 合并功能分支、推送 master | Windows 本地项目目录，PowerShell 或 CMD |
| 备份数据库、索引、配置、旧镜像 | Ubuntu 服务器 `/opt/app`，Bash |
| 拉取 master、构建、启动、检查 | Ubuntu 服务器 `/opt/app`，Bash |

顺序：本地合并并推送 → 服务器检查 → 停止应用并备份 → 拉取代码 → 检查生产配置 → 构建启动 → 验收。

Git 合并/推送只改变代码，不会自动更新正在运行的应用。服务器备份完成到应用重新启动期间，网站暂时不可用；建议选择低峰期。以下步骤按节执行，任何一步报错都先停下，不要继续粘贴后续命令。

## 2. Windows：合并并推送 master

进入本地项目目录：

```powershell
cd C:\Users\19284\WorkBuddy\2026-08-25-17-57-13\simulated-interview-agent
git status --short
```

工作区应干净。若有未提交改动，先明确如何处理，不要直接覆盖或清理。

```powershell
git fetch origin
git switch master
git merge --ff-only origin/master
git merge --ff-only origin/feature/R5-integration-tests
git push --set-upstream origin master
git --no-pager log --oneline -1
```

master 应包含前端基线 `4ac0a87` 及公开请求入口修复。修复若直接提交在 master，推送该新提交即可；功能分支合并可能提示已经更新，不需要重复创建提交。以发布时确认的修复哈希核对推送结果。

`--ff-only` 只允许快进。若提示不能快进、存在冲突或推送被拒绝，停止操作，保留报错再处理；不要使用 `git push --force`。master 被保护时，可以改为在 Gitee 创建从 `feature/R5-integration-tests` 到 `master` 的合并请求，经审阅后合并。

### 日志卡在 less 界面怎么办

若看到 `SUMMARY OF LESS COMMANDS` 或 `(END)`，按 `q` 退出；在帮助页可能需要再按一次。若底部是 `log file:`，先按 `Ctrl+C` 取消输入，再按 `q`，直到回到终端命令提示符。

以后用下面的命令避免进入分页器：

```powershell
git --no-pager log --oneline -5
```

## 3. Ubuntu：检查服务器当前状态

以下命令都在服务器执行：

```bash
cd /opt/app
git status --short
git branch --show-current
git --no-pager log --oneline -1
docker compose -f docker-compose.prod.yml --env-file .env ps
df -h /opt/app "$HOME"
```

确认：

- 当前为 `master`，工作区无未提交修改；尤其注意服务器的 `Caddyfile` 或部署文件是否有本地修改。
- 当前生产容器名称与下表一致，并且 MySQL、Redis 正常运行。
- 磁盘有容纳数据库导出、向量索引备份和 Docker 构建的空间。

| 项目 | 仓库中的配置 |
|---|---|
| 应用容器 | `mock-interview-app` |
| MySQL 容器 | `mock-interview-mysql` |
| Redis 容器 | `mock-interview-redis` |
| 数据库名 | `mock_interview` |
| 应用内向量索引目录 | `/app/data/rag-index` |
| 生产配置文件 | `/opt/app/.env` |

如果实际容器名或部署方式不一致，先核对实际环境，不直接套用后续备份命令。

## 4. Ubuntu：备份旧版本

### 4.1 创建备份目录

在同一个 Bash 终端中执行，后续会用到 `backup_dir` 变量：

```bash
cd /opt/app
umask 077
stamp=$(date +%Y%m%d-%H%M%S)
backup_dir="$HOME/app-backups/$stamp"
mkdir -p "$backup_dir"
echo "本次备份目录：$backup_dir"
```

备份放在项目外，避免误提交。目录内含 `.env`、数据库和个人资料，按私密数据保管，不上传到代码仓库或公开分享。

### 4.2 保存配置、旧镜像并导出数据

以下括号包住的是一个完整的 Bash 命令块。`set -euo pipefail` 在块内让失败立即中止；只有看到最后的“备份完成”才执行下一节。

```bash
(
  set -euo pipefail

  git rev-parse HEAD > "$backup_dir/commit.txt"
  cp .env Caddyfile docker-compose.prod.yml "$backup_dir/"

  rollback_image="mock-interview-app:before-$stamp"
  docker image tag \
    "$(docker inspect --format '{{.Image}}' mock-interview-app)" \
    "$rollback_image"
  printf '%s\n' "$rollback_image" > "$backup_dir/image.txt"

  # 应用暂停写入，MySQL、Redis 保持运行
  docker stop mock-interview-app

  # 容器已配置 MYSQL_PWD，密码不需要写进命令
  docker exec mock-interview-mysql \
    mysqldump -uroot \
    --single-transaction --quick \
    --routines --events --triggers \
    --no-tablespaces --set-gtid-purged=OFF \
    --databases mock_interview \
    > "$backup_dir/database.sql.partial"

  test -s "$backup_dir/database.sql.partial"
  grep -q '^-- Dump completed on ' "$backup_dir/database.sql.partial"
  mv "$backup_dir/database.sql.partial" "$backup_dir/database.sql"

  # 停止应用后再复制，保证索引与数据库处于同一停写窗口
  docker cp mock-interview-app:/app/data/rag-index \
    "$backup_dir/rag-index"

  cd "$backup_dir"
  sha256sum database.sql > database.sha256
  sha256sum -c database.sha256
  echo "备份完成：$backup_dir"
)
```

说明：仅保存一个回退镜像标签，不等于已经把镜像归档到备份目录；不要清理该镜像。可选另存镜像文件（需要更多空间）：

```bash
docker image save -o "$backup_dir/app-image.tar" \
  "$(cat "$backup_dir/image.txt")"
```

### 4.3 检查并另存备份

```bash
ls -lah "$backup_dir"
tail -n 3 "$backup_dir/database.sql"
```

应包含 `.env`、`Caddyfile`、旧 Compose 文件、`commit.txt`、`image.txt`、`database.sql`、`database.sha256` 和 `rag-index/`。

将这份目录再复制到电脑或独立备份存储。文件非空、导出完成标记和 SHA256 校验不等同于恢复演练；正式迁移前，建议在独立测试 MySQL 实例恢复 SQL 并核对用户、资料、会话和额度。数据库与索引应配套恢复，不能只保留其中一个。

备份失败且尚未启动新版时，可以先恢复原容器服务：

```bash
docker start mock-interview-app
```

修正备份问题后，使用新的时间戳重新备份，再安排更新。不要把 `.partial` 文件当作成功备份。

## 5. Ubuntu：拉取已经合并的 master

只有备份成功后才继续：

```bash
cd /opt/app
git pull --ff-only origin master
git --no-pager log --oneline -1
```

本次应包含前端基线 `4ac0a87` 及入口修复，核对发布时确认的修复提交。如果仍是旧版本，先核对 Windows 的 `git push origin master` 是否成功；不要只在功能分支推送后继续拉取 master。

若 Gitee 使用合并提交或压缩合并，顶部哈希可能不同，需检查本次改动是否已包含，不能只靠哈希前缀判断。

## 6. Ubuntu：核对生产环境变量

已有配置已在第 4 节备份，此时再编辑：

```bash
nano .env
```

检查以下配置名，不要将说明文字或示例占位符当作真实值填写：

| 配置 | 要求 |
|---|---|
| `MYSQL_PASSWORD` | 保持原数据库密码 |
| `JWT_SECRET` | 保留原值；新版 prod 要求至少 32 字节 |
| `APP_DATA_KEY` | 保留原值；应为 Base64 编码的 32 字节密钥 |
| `DEEPSEEK_API_KEY` | 保持有效的对话模型 Key |
| `DASHSCOPE_API_KEY` | 保持有效的通义/向量化 Key |
| `SPRING_PROFILES_ACTIVE` | 生产使用 `prod` |
| `BOOTSTRAP_ADMIN_USERNAME` | 需要人工补额时，设置要引导的管理员用户名，可使用已有账号 |
| `BOOTSTRAP_ADMIN_PASSWORD` | 为引导管理员设置的实际强密码 |

不要为了升级重新生成 `APP_DATA_KEY`，否则旧 BYOK 密文无法解密；`JWT_SECRET` 更换会使旧登录失效。如果原部署没有固定的数据密钥，先核对旧容器实际配置和 BYOK 数据，再决定迁移方式。

本次从旧 mysql profile 升级到 prod：Hibernate 不再自动改表，由 Flyway 以版本 3 建立存量基线，再执行 V4 的 `tenant_id` 迁移。不需要手动执行 V4 SQL，也不要改回 mysql profile 来绕过迁移错误。

## 7. Ubuntu：构建并启动新版

```bash
cd /opt/app
SPRING_PROFILES_ACTIVE=prod \
docker compose -f docker-compose.prod.yml --env-file .env \
  up -d --build --no-deps app
```

此命令使用仓库 Dockerfile 构建应用镜像，并更新应用容器；前提是第 3 节确认 MySQL、Redis 和 Caddy 已正常运行。首次启动会执行所需的 Flyway 迁移。

不要执行 `docker compose down -v`、删除生产数据卷或清空 MySQL/索引目录。这些操作不属于更新步骤。

## 8. Ubuntu：检查启动、迁移与页面

```bash
docker compose -f docker-compose.prod.yml --env-file .env ps
docker logs --tail=100 mock-interview-app
```

查看应用成功启动、prod 生效且没有 Flyway 失败。应用尚未完成启动时，稍后再检查，不要反复重建。日志可能包含运维敏感信息，反馈问题时先脱敏。

确认启动完成后：

```bash
docker exec mock-interview-app \
  curl -fsS http://localhost:8080/api/health/deps

docker exec mock-interview-mysql \
  mysql -uroot -e \
  'SELECT version, description, success FROM mock_interview.flyway_schema_history ORDER BY installed_rank;'
```

预期数据库和 Redis 探活均正常；迁移记录包含版本 4 且 `success=1`。

再通过实际公网域名验证：

- 首页显示陪考小暖、通考小师，登录正常。
- 老账号资料、历史报告和余额仍在。
- 新注册默认赠送 100 积分，积分规则和明细显示正常。
- 小暖、小师可回复，模型错误时保留草稿。
- 进行一场最短模拟练习，确认问答和报告可用；这一步会消耗实际积分。
- 积分不足时显示内测补额提示和工作人员微信 `13481264947`；不通过修改真实用户余额来测试，可使用专门测试账号。

确认后记录服务器代码版本和实际容器镜像：

```bash
git --no-pager log --oneline -1
docker inspect --format '{{.Image}}' mock-interview-app
```

Git 版本与容器镜像都要确认；仅拉取了新代码，不能证明运行中的应用已更新。

## 9. 更新失败时如何处理

### 打开首页即返回 TENANT_CONTEXT_MISSING

若启用 prod 后，直接访问根网址就返回 `CONFLICT / TENANT_CONTEXT_MISSING`，已确认的一处原因是旧 `JwtAuthFilter` 将公开请求直接放行，而 MVC 的 OpenEntityManagerInView 在 Controller 执行前创建了缺租户的 Session。仅在登录/注册 Controller 内 set 上下文无法解决首页和这一时序。

修复应在过滤器的公开请求入口显式设置普通系统分区 0L，并在 finally 清理；受保护 API 保持 JWT 鉴权，strict 继续开启。需要包含该修复的应用镜像重新构建后才能生效，`4ac0a87` 本身尚不包含本次补丁。不要通过关闭 strict 或改成 root 分区绕过此问题。

### 尚未创建新版容器、未开始迁移

例如构建阶段失败，原容器仍存在且数据库未变：可执行 `docker start mock-interview-app` 恢复原容器，并查看启动情况。源文件已经拉新不影响原容器里保存的旧镜像。

### 已开始新版启动或 Flyway 迁移

先停止应用，避免继续写入：

```bash
docker stop mock-interview-app
```

保留错误日志，并在恢复前另存失败后的数据库、索引和当前镜像信息。MySQL 的 DDL 可能已经部分生效，不能假定启动失败就完全没改数据库。

此时不要仅切回旧 Git 提交或旧镜像就启动。配套回退需要：

1. 确认恢复点与业务停写期间是否已有新数据；恢复旧备份会丢弃备份之后的写入。
2. 在隔离的 MySQL 实例或新数据卷验证备份恢复，确认表结构、数据与迁移历史匹配旧版本。不要盲目把 SQL 直接导入当前生产库；新增表可能残留。
3. 配套恢复同一备份目录里的索引、配置与 `image.txt` 指向的旧镜像；镜像若另存，可从 `app-image.tar` 加载。
4. 确认旧版本运行 profile 和连接目标，完成健康检查及账号/资料核验后，再恢复公网服务。

这部分应依据实际迁移结果制定恢复命令，不把删除数据库或数据卷的命令作为通用复制步骤。

## 10. 终端断开后如何继续

`backup_dir` 是 Bash 会话变量，重新登录后不会保留。先列出实际目录，再把本次备份路径重新赋值：

```bash
ls -lt "$HOME/app-backups"
# 将下一行的时间戳改为你实际保存的备份目录
backup_dir="$HOME/app-backups/20261020-实际时间戳"
```

确认目录内容与本次发布匹配，不能只挑任意一个旧备份继续操作。

## 参考与范围

- 仓库依据：`docker-compose.prod.yml`、`application-prod.yml`、`V4__add_tenant_id.sql`。
- [MySQL 8.0 mysqldump 官方说明](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html)
- [Docker：复制运行或停止容器中的文件](https://docs.docker.com/reference/cli/docker/container/cp/)
- [Git：merge 与 ff-only](https://git-scm.com/docs/git-merge)

本文提供操作步骤，没有在你的 Ubuntu 服务器实际执行备份、合并、迁移或部署；实际结果以服务器输出为准。新文档不自动提交或推送。
