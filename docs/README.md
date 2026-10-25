# 设计、原型与走查索引

当前重点：[接入与治理操作路径](design/ACCESS-GOVERNANCE.md) · [Agent / 模型接入原型](prototype/index.html#admin-integrations)。

新增：[本地目录与在线 Git](design/RUNTIME-RESOURCES.md#代码项目来源本地目录与在线-git) · [GitLab Access Token](design/EXECUTION-APPROVAL-RESOURCES.md#gitlab-access-token-配置)。

本轮补充：[任务自动命名与手动重命名](design/INTERACTION-SPEC.md#任务自动命名与手动重命名)，对话、任务和审批标题保持一致。

建议先看[初稿统一评审单](reviews/INITIAL-DRAFT-REVIEW.md)：按实际操作顺序审阅，并逐项区分设计、离线证据与待实机验证内容。

新增评审入口：[首次开通与完整使用试验](design/FIRST-USE-AND-TRIAL.md)，串起部署、成员开通、配置、对话、审批与交付，并集中列出待统一审批事项。

当前统一按初始设计整理，不另分产品版本。交付范围为设计、类型声明、离线原型和检查记录；真实模型、Agent、MCP、业务资源和服务端实现均未接入。

远程查看：GitHub 可直接阅读 Markdown 和 PNG。可先看[用户对话截图](reviews/initial-design-checks/screenshots/chat-conversation.png)、[审批列表](reviews/initial-design-checks/screenshots/approval-list.png)、[Agent 接入](reviews/initial-design-checks/screenshots/governance-agents.png)和[完整链路记录](reviews/initial-design-checks/screenshots/complete-journey-record.png)。交互原型需下载仓库后打开 `docs/prototype/index.html`，无需后台服务。截图全部为离线合成示例。

## 从这里开始

1. [完整需求覆盖](design/DESIGN-COVERAGE.md)：14 项设计需求与对应证据。
2. [整条链路的衔接合同](design/END-TO-END-WALKTHROUGH.md)：从提问、后台执行到交付和管理员排查。
3. [当前离线原型](prototype/index.html)：下载仓库后，用 Edge / Chrome 打开；GitHub 文件页只显示源码。
4. [整体走查发现与修补](reviews/END-TO-END-REVIEW-20261025.md)、[本轮检查](reviews/initial-design-checks/README.md)。

## 当前设计

| 主题 | 文档与用途 |
| --- | --- |
| 方向与授权 | [决策记录](reviews/DECISIONS-20261025.md) · [当前 ADR](architecture/ADR-001.md) · [术语](../CONTEXT.md) |
| 设计总入口 | [初始设计](design/README.md) · [需求覆盖](design/DESIGN-COVERAGE.md) |
| 用户对话 | [交互规格](design/INTERACTION-SPEC.md)：系统 / 环境选择、追问、授权、交付与验收 |
| 提交与审批 | [执行、审批与资源设计](design/EXECUTION-APPROVAL-RESOURCES.md)：用户执行方式、自动只读范围、独立执行 / 审批管理 |
| 后台配置 | [管理配置](design/ADMIN-CONFIGURATION.md)：系统、环境、模型、权限、草稿与发布边界 |
| 后台服务 | [服务执行](design/SERVICE-EXECUTION.md)：API、事务、事件、持久性和恢复 |
| 执行边界 | [ExecutionProvider](design/EXECUTION-PROVIDER.md) · [五层状态转换](design/STATE-MACHINES.md) |
| 审批与失败恢复 | [审批恢复](design/APPROVAL-RECOVERY.md)：请求失效、送达未知、重新授权与接管 |
| 模型与 ACP | [接入设计](design/MODEL-ACP-ACCESS.md)：账号、能力、宿主、会话和权限桥 |
| MCP | [工具治理](design/MCP-ACCESS.md)：协议、认证、schema、参数、风险与调用结果 |
| 运行与资源 | [环境与代码读取](design/RUNTIME-RESOURCES.md)：身份、隔离、网络、版本和只读证据 |
| 上下文与记忆 | [材料及记忆治理](design/CONTEXT-MEMORY.md)：来源、预算、摘要、审核、撤权和删除 |
| 用户及管理记录 | [用户治理](design/USER-GOVERNANCE.md)：范围授权、用户状态、正文租约与审计 |
| 证据与费用 | [证据及预算](design/EVIDENCE-BUDGET.md)：一致性、未知用量与强制边界 |
| 全链路交接 | [衔接合同](design/END-TO-END-WALKTHROUGH.md)：共同记录、修订、责任人与失效传播 |
| 协议来源 | [官方协议观察](design/PROTOCOL-OBSERVATIONS.md)：核查来源及未验证项 |
| 后续实施 | [评审清单](design/REVIEW-CHECKLIST.md) · [设计到开发的门槛](roadmap/design-to-build.md) |

## 原型与机器可读合同

| 内容 | 入口 |
| --- | --- |
| 用户端 / 管理端 | [对话](prototype/index.html#chat) · [基础配置](prototype/index.html#admin-systems) · [接入](prototype/index.html#admin-integrations) · [用户记录](prototype/index.html#admin-records) |
| 执行 / 审批 / 资源 | [执行管理](prototype/index.html#admin-executions) · [审批管理](prototype/index.html#admin-approvals) · [资源目录](prototype/index.html#admin-resources) |
| 原型源文件 | [prototype/](prototype/)：对话、配置、接入治理、共同离线记录及样式 |
| 单文件构建 | [build_initial_prototype.py](../scripts/build_initial_prototype.py) |
| 正式合同声明 | [ExecutionProvider 类型](design/contracts/execution-provider.d.ts) · [服务边界类型](design/contracts/service-boundaries.d.ts) |
| 转换与请求样例 | [状态转换 JSON](design/contracts/state-transitions.json) · [合成请求样例](design/contracts/examples.json) |
| 验收目录 | [未来共同契约目录](design/contracts/conformance-cases.json) · [需求映射 JSON](design/contracts/design-coverage.json) |

原型所有数据、回复和执行均为合成。当前页内共享会话与用户记录用于评审衔接，刷新会清空；不是已实现的业务服务或真实权限系统。合同类型未做 TypeScript 编译，真实 Provider 共同验收仍为 not_run。

## 走查、检查与交付

| 内容 | 入口 |
| --- | --- |
| 整体走查 | [8 项发现与修补](reviews/END-TO-END-REVIEW-20261025.md) |
| 执行资源补充走查 | [用户策略与可扩展环境](reviews/EXECUTION-RESOURCE-REVIEW-20261025.md) |
| 完整变更 | [初始设计变更说明](reviews/INITIAL-DESIGN-CHANGELOG-20261025.md) |
| 新配置到交付的连续走查 | [跨页配置、计划与结果引用](reviews/initial-design-checks/complete-journey.json)；离线样例目录起步，不是部署或真实执行证据 |
| 验证总览 | [检查说明](reviews/initial-design-checks/README.md)：173 项浏览器交互、33 项状态模拟、88 个视口组合 |
| 当前证据 | [浏览器报告](reviews/initial-design-checks/browser-report.json) · [静态报告](reviews/initial-design-checks/static-report.json) · [范围核查](reviews/initial-design-checks/scope-audit.json) |
| 交付摘要 | [文件与 SHA-256 清单](reviews/initial-design-checks/delivery-manifest.json) |
| 复核脚本 | [静态一致性](../scripts/check_initial_design.py) · [需求映射](../scripts/audit_design_scope.py) · [记录交付证据](../scripts/record_initial_design_checks.py) |

这些是设计及离线检查证据，不能用来宣称真实接入、安全、费用或生产问题解决率已验收。远端 CI 状态以 GitHub Actions 的实际运行记录为准。

## 历史资料

| 内容 | 入口 |
| --- | --- |
| 只读历史文本 | [review-baseline-20261025/](review-baseline-20261025/) |
| 历史架构 / 审批 | [完整原提案](review-baseline-20261025/architecture/ADR-001_独立平台与可插拔执行后端_v0.1.md) · [原审批清单](review-baseline-20261025/architecture/架构审批清单_v0.1.md) |
| 历史规格 / 原型 | [v0.2 规格](review-baseline-20261025/docs/设计说明与交互规范_v0.2.md) · [v0.2 原型](review-baseline-20261025/index.html) |
| 再生成阅读版 | [PDF / Word / 截图](generated/v0.2/README.md) |
| 来源与原件说明 | [归档状态](ARCHIVE-STATUS.md) · [历史源稿清单](../SOURCE_MANIFEST.json) |

历史文本原稿和 generated/v0.2 保留。49 件原始资料中的二进制原件与 ZIP 仍在原资料库；仓库生成阅读版不冒充原件。历史截图和测试报告不作为当前原型结果。

日常管理界面：[审批、执行、成员与记录的列表和详情设计](design/ADMIN-DAILY-UI.md)。

日常操作可用性：[本轮走查与改动](reviews/USABILITY-REVIEW-20261025.md)。

主操作与配置对应：[历史 / 简洁版取舍、对话到检查执行](design/USER-FLOW-AND-CONFIG.md)。

| 本轮系统配置与额度 | 入口 |
| --- | --- |
| 系统内环境、执行背景与结构化绑定 | [系统与执行上下文](design/SYSTEM-CONTEXT-QUOTAS.md) |
| 用户 Agent / 模型额度与全局默认 | [继承、预占及结算规则](design/SYSTEM-CONTEXT-QUOTAS.md#用户按-agent-和模型分配额度) |

本轮重点：[接入与治理完整操作设计](design/ACCESS-GOVERNANCE.md)，逐页说明 Agent / 模型、运行配置、上下文记忆、用户权限、记录排查以及 MCP 和环境绑定。

## 独立评审后的五项收敛

[本轮设计与原型优化记录](reviews/USABILITY-REFINEMENT-20261025.md)：会话不因导航停止、按对象发布、额度能力分级、理解与取证权限分离、按风险开通。原始历史报告保持历史含义，本轮结果单独记录；尚无真实业务后端。
