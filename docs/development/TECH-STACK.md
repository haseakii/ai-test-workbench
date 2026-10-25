# 技术选型建议

日期：2026-10-26。供评审，不是已安装的依赖清单。选择依据是事务一致性、长时间执行恢复、现有 Java 使用偏好及较低的部署成本。

## 推荐组合

| 部分 | 推荐 | 选择理由与边界 |
| --- | --- | --- |
| Java | JDK 21 LTS，发行商由部署环境统一 | 相比 17 提供虚拟线程等可选能力；业务正确性不依赖它。默认普通受限线程池，测量后再启用虚拟线程，仍须独立限流 |
| Web 服务 | Spring Boot 4.1.x 候选、Spring MVC、Spring Security、Bean Validation | REST 命令加 SSE，使用同步事务便于核查。由 Boot BOM 管理 Spring/Jackson，不手工拼不同主版本 |
| 构建 | Maven 3.9.x + Wrapper | Java 工程统一构建，CI 固定发行版、插件与校验值 |
| 数据库 | PostgreSQL 17 当前受支持补丁版 | 关系约束、事务、行锁和 JSONB 适合审批、幂等与快照；一个业务库，不共享总控数据库 |
| 数据访问 | Spring JDBC / JdbcClient + 显式 SQL，Flyway 迁移 | 关键 CAS、唯一约束、预占和 outbox 易审计；先不引入通用 CRUD 生成平台或复杂 ORM 聚合图 |
| 前端 | React 19、TypeScript、Vite 8 候选 | 对话和后台属于同一 SPA、同源发布；不需要 SSR。Node 22 满足所选 Vite 的最低补丁要求后固定，用于构建和测试 |
| UI | 业务功能分目录，复用一套列表、表单、抽屉及对话组件 | 可评估 Ant Design 基础组件，但样式和交互以已认可原型为准；不接入带几十个页面的管理模板 |
| 协议 | ACP Java SDK、MCP Java SDK 经兼容验证后固定版本 | SDK 只处理协议；平台仍实现审批映射、幂等、恢复及资源准入。无需强制引入 Spring AI / LangChain 编排层 |
| 文件 | BlobStore 接口；本地合成验证用隔离目录，真实部署可用现有 S3 兼容存储 | 业务库管理 ACL、哈希和生命周期，存储桶不公开；具体对象存储产品待部署评审 |
| 异步与事件 | PostgreSQL outbox/inbox + 受限后台工作线程 + SSE | 单体初期不需要 Kafka、Redis、独立工作流引擎；数据库才是恢复依据 |
| 测试 | JUnit、Spring 测试、Testcontainers PostgreSQL、Playwright、架构依赖测试 | 重点验证事务竞争和真实隔离，不能把前端模拟当契约测试 |
| 观测 | Actuator/Micrometer、结构化日志、关联 ID | 不向用户显示内部诊断；正文、Token 和工具原始输出不写常规日志 |

React、Vite 与组件库的精确补丁、TypeScript 版本及包管理器锁文件在工程建立时一起冻结。技术评审不要求追逐“最新版本”；创建工程前复核支持期、依赖树、安全通告和 SDK 兼容，结果写入依赖清单。

## 17 与 21 的取舍

推荐统一 21，开发、CI、服务和宿主使用同一主版本。若用户部署只能使用 17，则以 17 的 API 和编译目标重新验证依赖；没有验证之前不承诺两个 JDK 都通过。虚拟线程只改善等待型并发，不增加数据库连接、Agent 并发或额度，也不解决重试与重复执行。

不优先选 Node 后端，是为了服从 Java 维护偏好并统一身份、事务和审批实现；协议层存在 Java 库，暂不为此引入第二套 Node 服务。若某个目标 Agent 的 Java SDK 兼容性不能满足验收，可单独评审薄协议桥；不得连带改写业务服务或复制总控。

选择 JDBC 而非默认 JPA，是因为此项目最需要审阅的是提交、预占、CAS 和恢复 SQL；并非认为 ORM 不能实现事务。PostgreSQL 相比 SQLite 更适合多个后台任务与人工审批并发。MySQL 可另评估，但本稿 SQL 与锁语义以 PostgreSQL 为唯一设计目标，不承诺双数据库兼容。

## 必须完成的依赖验证

1. JDK 21 + 所选 Boot BOM + JDBC 驱动 + Flyway 的构建、启动、迁移及 PostgreSQL 集成测试。
2. ACP SDK 的协议版本、stdio、权限回调、取消、事件和 Jackson 兼容；MCP SDK 对目标服务版本、认证及传输的支持。仅有 SDK 名称不代表功能齐全。
3. 不把 SDK 的 Spring 自动配置带进领域层；在 adapter 包转换协议类型，输出平台 DTO。Jackson 2/3 不混用同一映射器假设。
4. 原型 `.d.ts` 是设计声明；未来 Java DTO、OpenAPI/JSON Schema 和跨语言黄金样例对齐后才冻结，不能手抄后各自演进。

## 本轮只读核查来源

以下页面在本轮读取，提供选型依据，不构成本项目兼容性测试。版本页面会变化，工程实施时复核。

| 官方来源 | 本轮观察 / 用途 |
| --- | --- |
| [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html) | 页面显示 4.1.2，最低 Java 17；据此选择 4.1 系列作为候选，未编译本项目 |
| [Java 21 虚拟线程](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) | 确认 21 的等待型并发选项；不将它当限流设施 |
| [PostgreSQL 支持策略](https://www.postgresql.org/support/versioning/) | 页面列 17 仍受支持，计划支持至 2029-11-08；使用受支持补丁 |
| [Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html) | 采用框架的数据访问与异常转换，业务锁和一致性仍由本项目定义 |
| [ACP Java](https://agentclientprotocol.com/libraries/java) / [SDK 仓库](https://github.com/agentclientprotocol/java-sdk) | 有 Java 客户端支持；README 说明 Java 17+ 和 Jackson 模块。具体发布制品未在本项目验证 |
| [MCP SDK 目录](https://modelcontextprotocol.io/docs/sdk) / [Java SDK](https://github.com/modelcontextprotocol/java-sdk) | 官方目录将 Java 列为 Tier 2；必须逐项验证所需协议能力，不能假设与 TypeScript 相同 |
| [React 版本](https://react.dev/versions) / [Vite 指南](https://vite.dev/guide/) | React 19 系列与当前 Vite 构建路线可供评估；按所选版本的 Node 要求锁定工具链 |

未创建 pom、安装 Java SDK、拉取容器或启动数据库。这里记录候选依据，不宣称选型已经通过实机验证。
