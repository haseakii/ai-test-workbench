# AI Test Workbench｜AI 测试协作平台

> **当前状态：设计与离线原型评审中，尚未进入业务开发或部署。**

面向测试团队的 AI 问题排查工作台。测试人员通过持续对话提交问题、截图和附件；平台在获授权的范围内组织代码与数据取证，交付可复核的证据和结果文件。管理员通过后台维护系统环境、执行流程、Agent / 模型、Skill、MCP、审批、权限和额度。

## 架构原则

- **独立业务平台**：平台拥有用户、问题会话、业务流程、审批责任、预算、证据和验收。
- **执行后端可插拔**：可通过适配器接入 multi-agent-control-plane，也可通过受控执行宿主直连 ACP Agent。
- **不重复建设总控**：不 fork 总控、不共享其数据库、不复制完整的多 Agent 调度引擎。
- **执行可追溯**：一个执行单元只有一个执行拥有者；外部结果未知时先核查，不静默重放。
- **权限落实到资源**：审批、身份、额度和资源访问由服务端及受控工具执行，不只依赖提示词或前端按钮。

## 当前技术方向

| 层次 | 当前方向 |
| --- | --- |
| 后端 | JDK 21、Spring Boot 模块化单体 |
| 平台数据库 | MySQL 8.4 LTS / InnoDB |
| 数据访问与迁移 | Spring JDBC、Flyway |
| 前端 | React / TypeScript |
| Agent 执行 | 独立 Java Runner；总控适配器与 ACP 直连适配器 |

MySQL 8.4 是已确认的平台数据库方向。Spring Boot 等精确版本、SDK 组合和运行环境仍需在 D0 验证后冻结。

## 从这里开始

1. [产品设计与交互规格](docs/design/README.md)
2. [离线交互原型](docs/prototype/index.html)
3. [开发设计与实施门槛](docs/development/README.md)
4. [架构决策](docs/architecture/ADR-001.md) · [技术栈与数据库决策](docs/architecture/ADR-002.md)
5. [评审材料索引](docs/reviews/README.md)

## 打开原型

克隆仓库后，用 Edge 或 Chrome 打开 docs/prototype/index.html。原型无需安装依赖或启动服务。GitHub 文件页面显示的是源码，不是已部署网站。

原型中的用户、回复、模型、配置、审批和执行记录均为合成演示；刷新会清空模拟状态。不要在原型中输入真实凭据或客户数据。

## 当前边界

- **已有**：产品与技术设计、离线原型、合同草案和合成数据检查。
- **尚未有**：正式业务后端、数据库迁移、真实身份、真实模型 / ACP / MCP 接入、业务数据访问和部署。
- **待实施验收**：MySQL 专项验收 MY01–MY14。文档检查和原型测试不能替代数据库、Provider 或安全验收。

详细范围与未完成项见[开发设计](docs/development/README.md)。历史评审过程与旧版资料集中在[评审材料索引](docs/reviews/README.md)，不作为当前设计的替代来源。
