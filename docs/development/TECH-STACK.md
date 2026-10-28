# 技术选型建议

日期：2026-10-26。**数据库方向更新为用户已确认的 MySQL 8.4 LTS / InnoDB；其余选型及精确依赖版本仍待验证。** 不是已安装清单或运行验收。本轮基于 `a03e4e0` 修订，不引入新的产品版本或业务工程。

## 推荐组合

| 部分 | 推荐 | 选择理由与边界 |
| --- | --- | --- |
| Java | JDK 21 LTS，发行商由部署环境统一 | 虚拟线程为可选能力；业务正确性不依赖它。默认普通受限线程池，测量后再启用，仍须独立限流 |
| Web 服务 | 沿用 Spring Boot 4.1.x 候选、Spring MVC、Spring Security、Bean Validation | REST 命令加 SSE，同步事务便于核查；精确版本在 D0 复核。由 Boot BOM 管理 Spring/Jackson，不手工拼不同主版本 |
| 构建 | Maven 3.9.x + Wrapper | Java 工程统一构建，CI 固定发行版、插件与校验值 |
| 数据库 | **MySQL 8.4 LTS + InnoDB**，冻结已发布、受支持的精确补丁及镜像摘要 | 平台唯一数据库目标；不维护 PostgreSQL / MySQL 双方言，也不承诺 5.7、8.0 或 MariaDB 兼容。业务库与 Runner 日志分 database、账号和迁移历史 |
| 数据访问 | Spring JDBC / JdbcClient + 显式 SQL | CAS、唯一约束、原子预占和 outbox 显式实现；不引入通用 CRUD 生成平台或复杂 ORM 聚合图 |
| 驱动与迁移 | MySQL Connector/J；Flyway core + MySQL 模块 | 驱动候选坐标 `com.mysql:mysql-connector-j`；Flyway 开源制品 `org.flywaydb:flyway-core` 与 `org.flywaydb:flyway-mysql` 对齐版本。与 Boot BOM 统一核验，不假定驱动主版本必须等于 Server 8.4 |
| 前端 | React 19、TypeScript、Vite 8 候选 | 对话和后台属于同一 SPA、同源发布；不需要 SSR。Node 22 满足所选 Vite 的最低补丁要求后固定，用于构建与测试 |
| UI | 业务功能分目录，复用列表、表单、抽屉及对话组件 | 可评估 Ant Design 基础组件，交互以已认可原型为准；不接入带几十个页面的管理模板 |
| 协议 | ACP Java SDK、MCP Java SDK 经兼容验证后固定版本 | SDK 只处理协议；平台仍实现审批映射、幂等、恢复及资源准入。无需强制引入 Spring AI / LangChain 编排层 |
| 文件 | BlobStore 接口；合成验证用隔离目录，真实部署可用现有 S3 兼容存储 | 业务库管理 ACL、哈希和生命周期，桶不公开；具体产品待部署评审 |
| 异步与事件 | MySQL InnoDB outbox/inbox + 受限后台工作线程 + SSE | 单体初期不需要 Kafka、Redis、独立工作流引擎；持久数据库是恢复依据，outbox 不承诺外部恰好执行一次 |
| 测试 | JUnit、Spring 测试、Testcontainers MySQL、Playwright、架构依赖测试 | 固定实际 MySQL 8.4 镜像及参数；并发、锁、约束和恢复不能用 H2、SQLite 或前端模拟代替 |
| 观测 | Actuator/Micrometer、结构化日志、关联 ID | 不向用户显示内部诊断；正文、Token 和工具原始输出不写常规日志 |

React、Vite、TypeScript、组件库、Java SDK 与包管理器锁文件仍在工程建立时一起冻结。技术评审不追逐“最新版本”；实施前复核支持期、依赖树、安全通告和实际制品兼容。MySQL 手册可能提及尚未发布补丁，不能用文档版本上限代替已发布镜像的核验。

## Java 和协议边界

推荐开发、CI、服务和宿主统一 JDK 21；若部署只能使用 17，再按 17 的 API 和编译目标重新验证，不在未验证前承诺双 JDK 支持。虚拟线程不增加数据库连接、Agent 并发或额度，也不解决重复执行。

不优先选 Node 业务后端，是维护偏好的取舍；协议层存在 Java 库，不预先增加第二套 Node 服务。某目标 Agent 的 Java SDK 兼容性若不能满足验收，可另行评审薄协议桥，不连带重写业务服务或复制总控。

选择 JDBC 而非默认 JPA，是因为本项目首先需要审阅提交、预占、CAS 和恢复 SQL，不是否认 ORM 可以做事务。领域层不依赖 SDK 自动配置，adapter 负责协议转换，Jackson 2/3 不混用同一映射器假设。

## MySQL 设计基线

换库不改变唯一执行拥有者、审批、额度和出队重新准入的要求。详细合同见[数据与事务](DATA-AND-TRANSACTIONS.md)。

- 全部业务和接纳日志表为 InnoDB。平台状态与日志分别使用独立 database / 账号，不与业务系统或外部总控共库；运行账号与迁移账号分离。
- 平台及 Runner 的短状态事务默认按 **READ COMMITTED** 验证，结合显式行锁、条件更新和唯一约束。不得假设它就是 MySQL 默认值，也不得因此取消锁。业务数据源的报表快照另按其需求设计，不继承平台的隔离级别。
- `FOR UPDATE SKIP LOCKED` 只用于可跳过的队列领取；授权、审批、额度记录锁等待失败必须拒绝或重试本地事务，不能跳过守卫继续分派。
- UUID 使用统一 `BINARY(16)` 映射；时间使用 UTC 约定的 `DATETIME(6)`；金额使用明确精度的 `DECIMAL` / BigDecimal；快照使用 `JSON`。不可变哈希由应用合同产生，不依赖数据库 JSON 序列化。
- 未关闭 Binding 使用生成列和唯一索引落地；标识符和幂等键使用精确字节语义。类型、排序规则、缺行竞争、死锁和锁超时均需专项验收。
- DDL 与账本/审批业务事务分开。Flyway 采用小步、可核验的向前迁移；MySQL 原子 DDL 不意味着多条 DDL 可作为一个事务一起回滚。

## 必须完成的依赖验证

1. JDK 21 + 所选 Boot BOM + Connector/J + Flyway core/mysql 的构建、启动、空库迁移、重启和升级验证；不能让 MariaDB 驱动静默替代 MySQL 驱动。
2. 在真实 MySQL 8.4 上记录 `SELECT VERSION()`、存储引擎、字符集/排序规则、会话时区、事务隔离级别与 SQL mode；测试和试点配置一致。行锁竞争、活跃绑定唯一性、额度总分账、截止时间及接纳未知恢复通过。
3. Flyway 模块版本与 core 对齐；检查连接实际目标 database 和迁移历史表；权限不足、迁移中断不得留下“服务就绪”的假状态。
4. ACP SDK 的协议版本、stdio、权限回调、取消、事件与 Jackson 兼容；MCP SDK 对目标服务版本、认证和传输的支持。SDK 名称存在不等于能力齐全。
5. `.d.ts` 仍是设计声明；Java DTO、OpenAPI/JSON Schema 和跨语言黄金样例对齐后才冻结。精确时间、UUID、Decimal 字符串和规范化 JSON 哈希都参与往返测试。

## 只读核查来源与边界

本轮 MySQL / Flyway 来源用于设计依据，不构成本项目运行验收：

| 官方来源 | 核查用途 |
| --- | --- |
| [MySQL 8.4 锁定读](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html) | `FOR UPDATE`、`SKIP LOCKED` 的事务前提与队列适用边界 |
| [MySQL 事务设置](https://dev.mysql.com/doc/refman/8.4/en/set-transaction.html) | 默认 REPEATABLE READ 与显式隔离级别设置 |
| [MySQL 索引](https://dev.mysql.com/doc/refman/8.4/en/create-index.html) | 生成列索引和 UNIQUE 多 NULL 语义 |
| [Connector/J Maven](https://dev.mysql.com/doc/connector-j/en/connector-j-installing-maven.html) | 驱动坐标，以实际选定制品验证兼容 |
| [Connector/J 时间](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html) | DATETIME / JDBC 时间转换边界，不能只改 serverTimezone |
| [Flyway MySQL](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql) | MySQL 支持为独立模块；页面表格不是本项目 8.4 精确组合已验证的证据 |
| [MySQL 原子 DDL](https://dev.mysql.com/doc/refman/8.4/en/atomic-ddl.html) | 原子 DDL 与事务 DDL 的区别 |

以下是 `a03e4e0` 已有非数据库候选的核查入口，本轮没有重新宣称其为最新或已兼容：[Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)、[Java 21 虚拟线程](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)、[Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)、[ACP Java](https://agentclientprotocol.com/libraries/java)、[MCP SDK](https://modelcontextprotocol.io/docs/sdk)、[React](https://react.dev/versions)、[Vite](https://vite.dev/guide/)。

初稿之后已获授权建立 Maven 工程并完成部分兼容、真实 MySQL 与合成服务试验。当前采用 Boot 4.1.2、Maven 3.9.11、Connector/J 9.7.0、Flyway 12.4.0，Node 24.19.0；SDK 离线验证使用 ACP 0.80.0 与 MCP 2.1.0。证据及未覆盖项见[实施记录](../reviews/IMPLEMENTATION-PROGRESS-20261026.md)，不将这些结果泛化为真实 Provider 或性能验收。
