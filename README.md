# TraceFlow

P3 已接入 DeepSeek 真实自然语言抽取；项目空间、文档导入、建议审核、任务历史四个页面使用真实后端。模型只生成待审建议，任务仍由人工确认。

## 开发启动（PowerShell 7）
需要 PowerShell 7、Node.js 22.12+、JDK 17、Maven 3.9+。先配置本机 DEEPSEEK_API_KEY，或将 docs/ai.properties.example 复制为 .local/ai.properties 填写密钥（已被 Git 忽略）。在项目根目录开两个终端：

```powershell
./scripts/Start-Backend.ps1 -Temporary
```
```powershell
./scripts/Start-Frontend.ps1
```
浏览器打开 http://127.0.0.1:5173 。临时库退出后不保留数据；有独立PostgreSQL时按下文设置连接并去掉-Temporary。

```powershell
./scripts/Build-Web.ps1 -Package
```
将前端打包到Spring静态资源，再生成同源可执行JAR。生成的静态资源和node_modules不提交，克隆后先执行构建脚本。

[P3 接入与事务说明](docs/P3.md) · [P3 验收记录](docs/P3-VERIFICATION.md) · [P2 页面说明](docs/P2.md)

当前流程：真实模型抽取 → 人工审核 → 任务更新 → 历史追溯。抽取失败保留原文，可在详情页明确重试。

## 环境
Java 17、Maven 3.9+。运行服务需要 PostgreSQL 14+；集成测试会自行启动临时 PostgreSQL，不需要 Docker，不连接已有数据库。
Windows 默认 Java 可能是 8，执行 Maven 前确认 `mvn -v` 显示 Java 17。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn test
mvn package
```

首次测试需要从 Maven 仓库下载嵌入式 PostgreSQL 的本机二进制。测试只使用随机端口及临时数据目录，测试结束后关闭。

## 无 Docker 临时演示
以下命令启动真实的临时 PostgreSQL 和 HTTP 服务，退出后数据丢弃；仅供本机演示。

```powershell
./scripts/Start-Backend.ps1 -Temporary
```

另开终端执行 `./scripts/Smoke.ps1`。需要持久保存数据时使用下面的专用数据库方式。

## 启动服务
提供 PostgreSQL Compose 配置，数据库端口仅绑定本机：

```powershell
$env:TRACEFLOW_DB_PASSWORD = '<自行设置本地开发密码>'
docker compose up -d db
./scripts/Start-Backend.ps1
```

已有专用开发数据库时，设置 TRACEFLOW_DB_URL、TRACEFLOW_DB_USER、TRACEFLOW_DB_PASSWORD。默认 JDBC URL 为 jdbc:postgresql://localhost:55432/traceflow。
Flyway 启动时自动创建六张业务表并迁移模型审计字段。请仅指定 TraceFlow 的独立数据库。
启动脚本默认 http://127.0.0.1:8091；直接使用 Maven 启动默认为 8080。当前未实现登录，仅用于本机开发。

## 运行完整 API 示例
脚本为UTF-8，请使用PowerShell 7执行。
```powershell
./scripts/Smoke.ps1
```
脚本默认连接启动脚本的 8091 端口，可传 -BaseUrl 修改；创建独立示例项目，真实模型模式发起两次抽取调用，验证自然语言、相对日期、文档去重、重复审核、冲突重审和来源历史，不操作已有项目。例子保留在开发库便于检查。

## 离线模拟抽取协议（仅 -Mock 模式）
仅显式启动 ./scripts/Start-Backend.ps1 -Temporary -Mock 时使用以下固定格式；默认真实模型可直接分析自然语言。
每行使用一种固定格式（中文标点）：

```text
新增任务：接口联调；负责人：李明；截止：2026-11-12
更新任务：<已有任务UUID>；负责人：李明；截止：2026-11-15
```

这是确定性测试夹具，不具备自然语言理解能力。不匹配的文本得到零条建议。导入请求提供 referenceDate，日期不做推断。
返回的 candidate_json 使用 title / assigneeName / dueDate，可经人工修改后提交；接口资源元数据沿用数据库 snake_case。
CREATE 审核不传 expectedTaskVersion；UPDATE 审核必须传当前任务版本。审核字段为完整替换，null 表示清空负责人或截止日。

## API
- POST /api/projects — {name, timezone}
- GET /api/projects — 项目列表和文档/任务/待审统计
- GET /api/projects/{id}/documents?page=0&size=20 — 分页文档列表
- POST /api/projects/{id}/documents — {title, content, referenceDate}
- GET /api/documents/{id} — 不可变原文、作业、待审核建议
- POST /api/documents/{id}/extract — 仅为 FAILED 文档创建新抽取尝试
- POST /api/proposals/{id}/approve — {title, assigneeName, dueDate, expectedTaskVersion?}
- POST /api/proposals/{id}/reject
- GET /api/projects/{id}/tasks?page=0&size=20
- GET /api/tasks/{id}
- PATCH /api/tasks/{id} — {title, assigneeName, dueDate, status, expectedTaskVersion}
- GET /api/tasks/{id}/history — 前后快照、来源文档、原始证据
- GET /api/health

响应码：400字段/模拟协议错误；404不存在；409版本冲突、已关闭建议或任务上限。
相同建议重复确认返回首次应用结果，不再次执行更新。输入去重为项目+换行规范化文本哈希+参考日期+时区。
版本冲突后建议保持 PENDING，刷新任务并重新审核后提交新版本。

## 设计与验证
[P0/P1 契约](docs/P0-P1.md) · [固定案例](docs/fixtures.json)
真实 PostgreSQL 集成测试位于 src/test/java/dev/traceflow/WorkflowIntegrationTest.java。
覆盖 HTTP 闭环、证据历史、去重、并发审核、并发版本竞争、冲突重审、跨项目隔离、事务故障回滚、拒绝终态和输入边界。
P3 模型调用在数据库事务外同步执行，短事务保存原文和快照，再原子写入待审建议；失败留存并支持手动重试。P4 的队列、租约与崩溃恢复，以及登录尚未实现。

本机验证说明：临时测试库和临时演示入口使用 UTF-8/C locale，关闭仅回环连接的 SSL/GSS 协商以避开本机 JDBC 握手超时；这些参数不修改外部数据库连接配置。

## 独立公共依赖源
如本机全局 Maven 配置了不可用的企业镜像，可使用项目内配置（不含账号、凭据）：

```powershell
mvn -s .mvn/central-settings.xml test
mvn -s .mvn/central-settings.xml -DskipTests package
```

[P0/P1 验证回执](docs/VERIFICATION.md) 记录真实测试结果及已知环境限制。