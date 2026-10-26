# 文档导航

本页只提供当前有效设计的阅读入口。逐次讨论、历史测试输出和旧版原稿集中在[评审与归档索引](reviews/README.md)。

## 推荐阅读顺序

| 顺序 | 文档 | 用途 |
| --- | --- | --- |
| 1 | [产品设计与交互](design/README.md) | 用户工作台、管理端、核心流程与产品范围 |
| 2 | [当前交互原型](prototype/index.html) | 离线操作页面；所有数据均为合成演示 |
| 3 | [架构决策 ADR-001](architecture/ADR-001.md) | 独立平台、可插拔执行后端及职责边界 |
| 4 | [技术栈与数据库 ADR-002](architecture/ADR-002.md) | Java 模块化单体、MySQL 8.4 和独立 Runner |
| 5 | [开发设计](development/README.md) | 技术选型、代码分层、事务、接入 API 和实施验收 |

## 按主题查阅

- **交互与产品范围**：[交互规格](design/INTERACTION-SPEC.md) · [用户流程与配置关系](design/USER-FLOW-AND-CONFIG.md) · [首次开通](design/FIRST-USE-AND-TRIAL.md)
- **系统和管理后台**：[管理配置](design/ADMIN-CONFIGURATION.md) · [日常管理 UI](design/ADMIN-DAILY-UI.md) · [用户治理](design/USER-GOVERNANCE.md)
- **执行与审批**：[服务执行](design/SERVICE-EXECUTION.md) · [执行后端契约](design/EXECUTION-PROVIDER.md) · [状态转换](design/STATE-MACHINES.md) · [审批恢复](design/APPROVAL-RECOVERY.md)
- **资源和上下文**：[代码与运行资源](design/RUNTIME-RESOURCES.md) · [MCP](design/MCP-ACCESS.md) · [模型与 ACP](design/MODEL-ACP-ACCESS.md) · [上下文与记忆](design/CONTEXT-MEMORY.md)
- **预算与证据**：[系统上下文和额度](design/SYSTEM-CONTEXT-QUOTAS.md) · [证据与预算](design/EVIDENCE-BUDGET.md)
- **实施规划**：[技术选型](development/TECH-STACK.md) · [架构与代码层级](development/ARCHITECTURE-AND-CODE.md) · [数据与事务](development/DATA-AND-TRANSACTIONS.md) · [接入与 API](development/INTEGRATION-AND-API.md) · [实施计划](development/IMPLEMENTATION-PLAN.md)

## 当前状态与验证边界

项目仍处于设计与离线原型阶段。没有正式业务服务、数据库迁移或真实资源接入。当前原型检查只说明对应合成交互在已测场景下的行为，不证明数据库并发、真实 Provider、资源隔离、费用结算或生产安全已通过。

当前技术方向以 ADR-001、ADR-002 和开发设计为准。历史记录若包含不同的数据库或实现提案，应按其历史背景阅读，不覆盖当前决策。

## 历史与归档

- [评审材料索引](reviews/README.md)：区分当前决定、历史复审和验证证据。
- [不可变历史设计基线](review-baseline-20261025/)：保留最初交付的原稿，不直接修改。
- [v0.2 阅读版](generated/v0.2/README.md)：由源码重新生成的 PDF、Word 和截图，不保证与资料库二进制原件逐字节相同。
- [原始资料归档说明](ARCHIVE-STATUS.md)：说明资料库原件与仓库阅读副本的关系。
