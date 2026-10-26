# 产品设计与交互规格

本目录是当前产品设计的主入口。技术实施细节见[开发设计](../development/README.md)；架构决策以 [ADR-001](../architecture/ADR-001.md) 和 [ADR-002](../architecture/ADR-002.md) 为准。

## 产品目标

让测试人员像使用普通 AI 助手一样提交和跟进问题，同时让管理员能控制系统环境、执行能力、资源权限、审批和额度。

- **用户端**：单一持续对话；选择获准的系统与环境；提交文本、截图和附件；在消息流中补充条件、查看进展、证据和产物，并确认结果。
- **管理端**：列表与表单优先；按需查看任务、审批和用户记录；配置系统及其所属环境、流程、Agent / 模型、Skill、MCP、代码项目、权限和额度。
- **执行治理**：业务平台负责审批与验收；被选中的执行后端负责其内部执行事实。两者状态分开保存。

## 核心阅读路径

| 主题 | 主要文档 |
| --- | --- |
| 用户对话、附件、标题和结果 | [交互规格](INTERACTION-SPEC.md) |
| 从问题到执行的完整路径 | [用户流程与配置关系](USER-FLOW-AND-CONFIG.md) · [端到端衔接合同](END-TO-END-WALKTHROUGH.md) |
| 系统、环境和管理配置 | [管理配置](ADMIN-CONFIGURATION.md) · [日常管理 UI](ADMIN-DAILY-UI.md) |
| 执行方式、审批与资源 | [执行、审批和资源](EXECUTION-APPROVAL-RESOURCES.md) · [审批恢复](APPROVAL-RECOVERY.md) |
| Agent、模型、MCP 和代码 | [模型与 ACP](MODEL-ACP-ACCESS.md) · [MCP](MCP-ACCESS.md) · [运行资源](RUNTIME-RESOURCES.md) |
| 上下文、记忆、权限和额度 | [上下文与记忆](CONTEXT-MEMORY.md) · [用户治理](USER-GOVERNANCE.md) · [系统上下文与额度](SYSTEM-CONTEXT-QUOTAS.md) |
| 证据与验收 | [证据与预算](EVIDENCE-BUDGET.md) · [需求覆盖](DESIGN-COVERAGE.md) · [评审清单](REVIEW-CHECKLIST.md) |
| 正式执行边界 | [ExecutionProvider 契约](EXECUTION-PROVIDER.md) · [分层状态转换](STATE-MACHINES.md) · [服务执行](SERVICE-EXECUTION.md) |

## 当前原型

打开[离线交互原型](../prototype/index.html)。用户端、管理端配置、审批、执行记录和结果交付均为合成演示，不连接真实模型、数据库、GitLab、ACP 或 MCP。原型用于评审页面与流程，不是可直接上线的权限服务。

## 首条验证链路

首条业务验证目标是：在一个授权的测试环境中，针对报表与明细不一致的问题，完成条件补齐、获授权只读取证、确定性差异计算、证据定位、结果文件交付和用户验证。证据不足时应明确停止推断并整理接管材料。

首条链路只限定第一阶段的验证范围，不限制平台后续支持可配置流程、Skill、MCP 和其他 ACP 后端。自由流程画布、任意脚本执行、复杂多 Agent 协作及受控写入不属于首条链路的前置条件。

## 当前边界

所有页面与执行仍为离线模拟。真实身份、持久化、资源访问、执行隔离、额度结算和两个执行后端的共同契约均需在开发阶段单独验收。历史规格和评审过程见[评审材料索引](../reviews/README.md)。
