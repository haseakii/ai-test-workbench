# 数据库决定

**当前决定：MySQL 8.4 LTS / InnoDB 是平台唯一的数据库目标。**

这是设计阶段的选型，不是现有数据库迁移。采用 MySQL 的主要理由是贴合团队现有维护经验；不据此声称 MySQL 在性能或可靠性上优于 PostgreSQL。

## 范围

- 平台业务状态库与 Runner 接纳日志使用独立 database、账号和 Flyway 历史；可以同实例部署，但不跨库查询、外键或共享事务。
- 被排查业务系统使用自己的数据源；外部总控保持独立存储，不随平台选型迁移。
- 首版不维护 PostgreSQL / MySQL 双方言，不承诺 MySQL 5.7 或 MariaDB 兼容。
- Java、Spring Boot、Connector/J、Flyway 和协议 SDK 的精确版本仍需通过 D0 依赖验证后冻结。

## 必须保留的实现约束

- UUID、UTC 微秒时间、精确数值、JSON 与哈希使用明确的跨语言映射规则。
- 未关闭执行绑定、幂等命令、事件去重和审批决定由数据库唯一约束与条件更新保障。
- 额度总账、模型分项、并发槽、命令和 outbox 在同一平台事务内原子预占。
- 短事务显式验证 READ COMMITTED、行锁、CAS 和唯一约束；SKIP LOCKED 只用于队列领取。
- 死锁、锁超时、提交回执未知和迁移中断按实际数据库状态恢复，不盲目重试外部动作或清理迁移历史。

## 验收状态

数据与事务设计列出的 MY01–MY14 是待实施验收项，尚未在真实 MySQL 8.4 上执行。文档检查、离线原型和状态模拟不构成数据库并发、恢复或安全验证。

详细规范见[技术选型](../development/TECH-STACK.md)、[架构与代码层级](../development/ARCHITECTURE-AND-CODE.md)及[数据与事务](../development/DATA-AND-TRANSACTIONS.md)。
