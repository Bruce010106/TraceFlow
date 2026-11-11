# P2 验收记录

验收日期：2026-11-11。目录：`D:\USYD\工作\Personal Project\TraceFlow`。

## 交付范围

React + TypeScript + Vite 前端，共四个页面：项目空间、文档导入、建议审核、任务历史。使用真实 Spring Boot API 和 PostgreSQL；只有抽取器采用 P1 的固定模拟规则。

后端增加项目统计和分页文档列表，导入响应增加 duplicate 标识。保留 P0/P1 的审核事务、幂等约束和乐观锁，未新增数据库迁移。

## 构建与回归

| 检查 | 结果 |
| --- | --- |
| TypeScript 编译与 Vite 生产构建 | 通过 |
| PostgreSQL / HTTP 集成测试 | 12 项，失败 0，错误 0，跳过 0 |
| 一键脚本 Build-Web.ps1 -Package | 前端构建通过；自动选用 JDK 17；Maven BUILD SUCCESS |
| Spring 提供静态页面和 API | 同源访问成功，JS / CSS 返回 200 |
| 前端深链接直接访问与刷新 | 任务详情和历史重新载入成功 |

后端测试涵盖原有幂等、并发版本竞争、故障回滚、冲突重审等，并新增项目统计、文档分页、文档隔离和零建议文档的验证。

## 真实浏览器闭环

在任务浏览器中操作页面，随后读取同一个后端核对结果，没有用前端假数据代替接口。

1. 创建项目，通过文件选择上传 UTF-8 TXT，产生两条新增建议；原文按实际行分开，保留末尾空行和对应引句。
2. 将第一条标题改为“接口联调（已核对）”后确认创建，拒绝第二条；最终只建立一个任务。
3. 从任务页生成更新记录，导入“截止 2026-11-15”的建议。
4. 模拟另一个请求将任务截止日改为 2026-11-16，任务从 v0 变为 v1。
5. 审核旧版本时出现冲突：保留草稿日期 2026-11-15，显示当前日期 2026-11-16，提交按钮禁用。点击“已核对，使用最新版本”后确认成功，任务变为 v2。
6. 重放同一审核请求，仍返回首次 v2 结果，历史条数保持 3，没有重复执行更新。
7. 在任务页人工修改状态为“进行中”，最终 v3，截止日 2026-11-15；四次历史按版本显示，来源记录可以回看。
8. 通过导入页再次提交完全相同原文和记录日期，提示文档已存在并打开原记录；文档总数仍为 2。
9. 导入普通描述，得到零建议；刷新页面后原文仍在，任务数仍为 1。
10. 创建第二个项目，任务列表为空；切回原项目，其 3 份文档、1 个任务、0 条待审建议保持一致。
11. 直接打开 Spring 端口的任务深链接并刷新，页面、接口和静态资产正常。
12. 在 430 × 900 仿真视口检查四页，页面宽度均为 430px，无横向溢出；移动导航可展开、切换并关闭。已查看桌面和窄屏截图。

验收数据来自临时 PostgreSQL，停止本次服务后不保留。本次启动的前端、后端及临时库已停止，测试端口已释放，浏览器任务已关闭。下面的证据文件保留在本机，`.local` 已被 Git 忽略：

- `.local/p2-backend-test.log`、`.local/p2-package.log`、`.local/p2-build-final.log`
- `.local/p2-qa/state.json`：场景验证标记和最终任务快照
- `.local/p2-qa/review-ready.png`、`review-conflict.png`、`task-history.png`、`packaged-history.png`
- `.local/p2-qa/mobile-projects.png`、`mobile-import.png`、`mobile-review.png`、`mobile-history.png`

## 本机运行说明

使用 PowerShell 7、Node.js 22.12+、JDK 17 和 Maven。两个终端分别运行：

```powershell
./scripts/Start-Backend.ps1 -Temporary
./scripts/Start-Frontend.ps1
```

前端 http://127.0.0.1:5173，后端默认 8091。持久化 PostgreSQL 配置和打包命令见 README。

本机 Windows 的默认临时目录曾导致 Java NIO 选择器启动失败。Start-Backend.ps1 在当前进程内使用项目的 `.local/sockets` 作为 `jdk.net.unixdomain.tmpdir`；不修改系统设置，退出时恢复 JAVA_TOOL_OPTIONS。临时演示入口使用 `spring-boot:test-run`。

集成测试结束时，嵌入式 PostgreSQL 库报告过临时目录清理警告及连接池关闭告警；测试结果仍为全部通过。本轮未处理这个已有的临时库清理问题。

## 当前边界

- 模拟抽取只识别 README 中的固定中文指令；不提供自然语言理解。
- 未实现登录、多人权限或线上部署。
- 任务筛选作用于当前分页，界面已标明。
- 单包验证包含 JAR 构建和 Spring 静态资源同源访问；浏览器验收使用临时演示入口，没有另外启动持久数据库上的生产 JAR。
