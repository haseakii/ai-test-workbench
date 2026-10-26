# 数据库决定与修订记录 · 2026-10-26

## 用户确认与本轮范围

用户讨论 PostgreSQL 能否改 MySQL 后，于 2026-10-26 01:19:31 UTC 明确要求：“好 你优化一下，直接在这个提交上更新”。据此将 **MySQL 8.4 LTS / InnoDB** 设为平台唯一数据库设计目标，在 `a03e4e0fb6934f6f5d78bfcd6d4ace890358332e` 所在 `design/usability-refinement-20261025` 分支继续提交。

采用追加提交保留既有审查历史，不 amend/强推、不新开设计分支、不合并 main。授权范围是数据库方案与开发文档更新，不是业务工程、模型、数据库连接或部署授权。

## 当前决定

保留 JDK 21 / Spring Boot 模块化单体、独立 Java Runner、React/TypeScript、显式 JDBC、Flyway、事务 outbox/inbox 和 SSE。数据库从原 PostgreSQL 提案改为 MySQL 8.4 LTS；精确补丁、驱动和依赖组合仍需 D0 验证。

这是尚未实施的设计改选，不是生产库迁移。平台状态库和 Runner 日志按 MySQL 分 database、账号、迁移历史；被排查业务系统的数据源、外部总控自身存储不改变。首版不做 PostgreSQL/MySQL 双方言、不增加 MySQL 5.7 或 MariaDB 兼容负担，也不借此替换其他候选技术或扩充主菜单。

## 具体优化

| 主题 | 本轮落地到设计的规则 |
| --- | --- |
| 类型与精度 | BINARY(16) UUID、DATETIME(6) UTC 约定、DECIMAL/BigDecimal、JSON 快照；字段显式映射，时间精度在签署清单前统一 |
| JSON 与身份 | 应用规范化合同决定哈希；拒绝重复键；幂等键/外部 ID 精确字节比较，不受默认排序规则或静默截断影响 |
| 唯一执行 | open_guard 生成列 + 租户/节点唯一索引；已关闭历史保留；安全关闭证明不可省略 |
| 锁与预占 | READ COMMITTED 短状态事务 + 显式锁/CAS/唯一键；稳定父/守卫行处理首次创建缺行竞争；总分账、槽和 outbox 原子提交 |
| 错误恢复 | 死锁与锁超时均完整回滚本地用例；仅重试未产生外部动作的事务；COMMIT 回执未知按原键核查 |
| 队列与事件 | SKIP LOCKED 仅用于队列，不能跳过权限/额度锁；事件序号仍由会话计数行同事务分配，不依赖自增提交顺序 |
| 权限与存储 | 平台/Runner 独立数据库与运行账号；迁移账号独立；不跨库事务、不共享外部总控库 |
| 迁移 | Flyway core/mysql + Connector/J 组合验证；原子 DDL 不等于整批迁移可回滚；中断先核查实际结构，不盲目 repair/clean |
| 验收 | D0/D1 改为真实 MySQL 8.4 基线，新增 MY01–MY14；H2/SQLite/旧版或原型检查不替代数据库证据 |

## 文档入口与解释优先级

- [开发入口](../development/README.md)：当前组合、待审批与已确认范围。
- [ADR-002](../architecture/ADR-002.md)：数据库改选原因、代价及批准边界。
- [技术选型](../development/TECH-STACK.md)：驱动、迁移、测试和官方依据。
- [架构与代码](../development/ARCHITECTURE-AND-CODE.md)：MySQL 两库、账号与模块边界。
- [数据与事务](../development/DATA-AND-TRANSACTIONS.md)：类型、唯一约束、锁、额度、事件与恢复。
- [开发验收](../development/IMPLEMENTATION-PLAN.md)：D0–D4 及 MY01–MY14 专项要求。

上述当前文件及本记录优先解释数据库选型。`DECISIONS-20261025.md`、`OVERALL-SOLUTION-REVIEW-20261026.md` 和旧提交中的 PostgreSQL 表述记录的是当时的提案，不冒充当前选择；历史原稿与既有检查报告不改写。[接入与 API](../development/INTEGRATION-AND-API.md) 的资源授权、执行所有权和契约语义不因换库改变。

## 检查与未验证项

本轮范围是文档和 MySQL 引擎语义的只读核对。原型、执行类型声明、业务代码、迁移目录、工作流配置、历史源稿和原有测试文件不在修改范围。远端提交及 PR 检查是本轮落库/静态检查依据，检查完成状态单独记录在 PR，不把计划当结果。

此前 195 项交互、33 项状态、88 组布局属于对应原型及提交的历史证据；本轮不据此宣称数据库改选通过。MY01–MY14 全部为待实施验收，SQL 片段只是设计示意，没有在 MySQL 上执行。

尚未创建 Maven 工程、pom、迁移 SQL、数据库、容器或业务服务；没有安装或验证 Connector/J/Flyway 组合，没有运行真实并发、数据库备份恢复、真实 Provider 或业务数据测试。其他 Java/前端/SDK 候选版本沿用前一稿，不能把本轮 MySQL 资料核对称为它们已经重新验证。
