# P0/P1 验证回执

日期：2026-11-10

## 已核实
- Java 17.0.16、Maven 3.9.9、Spring Boot 3.5.6。
- 真实、隔离的临时 PostgreSQL 14.22；从空库执行 Flyway V1，创建六张业务表。
- 命令：mvn -o -B -ntp test。
- Maven BUILD SUCCESS；JUnit 10 项，0 失败、0 错误、0 跳过。
- 原始报告：target/surefire-reports/dev.traceflow.WorkflowIntegrationTest.txt。
- 完整日志：verify-final.log（被 Git 忽略）。

## 用例
1. 创建/更新闭环，原文证据及修改前后历史。
2. 同一文档导入10次、同一建议审核10次，数据库只有一次业务效果。
3. 过期版本409、建议保持PENDING，重新审核可用新版本提交。
4. 两个并发同版本更新，一次成功、一次409。
5. 八次并发审核，只创建一个任务、一条历史。
6. 跨项目目标拒绝，导入事务完整回滚。
7. 注入历史表写入失败，任务和建议状态完整回滚。
8. 拒绝幂等且拒绝后的建议不可应用。
9. 时区/空输入/非法日期校验；无任务文本成功产生零建议。
10. 更新必须提供版本；未知字段和无效分页/UUID拒绝。

## 环境处理与边界
Docker 引擎返回500，验证没有依赖Docker。临时库使用C locale、UTF-8，且仅在回环测试与临时演示中关闭SSL/GSS协商，以解决已核实的JDBC enableSSL握手读取超时。
Windows临时数据库关闭阶段出现库自身的关闭/目录清理告警；JUnit全部通过且Maven退出码0。原始日志保留，不能据此声称临时目录已全部清理。
Smoke.ps1 已通过PowerShell语法检查，尚未对独立启动的HTTP进程运行；集成测试通过MockMvc进入真实控制器、服务、事务和PostgreSQL。
P1 模拟抽取同步执行；异步恢复、真实模型、UI、认证尚未实现。服务仅绑定127.0.0.1。

## 打包检查
命令：mvn -s .mvn/central-settings.xml -B -ntp -DskipTests package。
结果：BUILD SUCCESS，退出码0，Spring Boot repackage成功。
产物：target/traceflow-0.1.0-SNAPSHOT.jar。
本次打包复用已通过的10项测试结果，没有重复运行数据库测试；公共依赖源配置不改动全局Maven设置。