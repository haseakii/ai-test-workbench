# 初始设计与原型

日期：2026-10-25。状态：按用户本轮授权完善的设计评审稿，尚未冻结实施。

本目录统一承载当前设计，不再按 v0.3 等产品版本拆分。`../review-baseline-20261025/` 的 v0.2 原稿和 `../generated/v0.2/` 阅读版保留为历史材料。本轮授权包含设计、离线原型和模拟检查，不包含业务后端、真实资源接入、付费模型或部署。

## 阅读顺序

1. [完整需求覆盖](DESIGN-COVERAGE.md)、[服务执行与前台 API](SERVICE-EXECUTION.md)，再读 [ExecutionProvider 正式契约草案](EXECUTION-PROVIDER.md)与[类型声明](contracts/execution-provider.d.ts)。
2. [分层状态转换](STATE-MACHINES.md)与[机器可读转换表](contracts/state-transitions.json)。
3. [审批等待与恢复](APPROVAL-RECOVERY.md)。
4. [初始交互规格](INTERACTION-SPEC.md)、[管理端配置](ADMIN-CONFIGURATION.md)与[离线原型](../prototype/index.html)。
5. [证据与预算](EVIDENCE-BUDGET.md)、[评审与验收清单](REVIEW-CHECKLIST.md)。
6. [模型与 ACP](MODEL-ACP-ACCESS.md)、[MCP](MCP-ACCESS.md)、[执行环境与代码](RUNTIME-RESOURCES.md)、[上下文与记忆](CONTEXT-MEMORY.md)、[用户治理](USER-GOVERNANCE.md)。
7. [整条链路的衔接合同](END-TO-END-WALKTHROUGH.md)与[整体走查发现](../reviews/END-TO-END-REVIEW-20261025.md)。
8. [本轮变更说明](../reviews/INITIAL-DESIGN-CHANGELOG-20261025.md)。

## 首条业务链路与首版范围

目标：一个系统、一个测试环境、一个已发布的固定流程，处理“报表与明细不一致”：提出问题 → 补齐条件 → 获授权只读取证 → 确定性差异文件与可定位证据 → 用户验证；失败则生成接管材料。

用户端收敛为一个普通模型对话界面，增加系统、环境和常用检查问题选择。追问、授权、结果和用户确认都在对话内完成；用户端形式已获认可；管理端基础配置为系统、环境、模型、权限四项，另提供接入、运行环境、上下文记忆、用户与记录治理。详细执行状态仅在次要的设计评审入口中演练。多系统选项为合成配置示例，不扩大首条真实链路的验收范围。

当前交付仅模拟上述状态和恢复规则。工作流自由编辑、Skill 发布、知识沉淀、受控写入、复杂协作和远程多节点均列为后续范围；历史 22 页不等于首版必须实现 22 页。审批恢复用合成的受限文件导出演示，不运行 SQL、不修改代码。

两个后端分别通过共同契约和真实验收后，才可宣称支持；总控关闭时真实基础流程通过之前，不宣称 ACP 直连已独立可用。模型和能力未知时保持未知。

## 当前源码与协议观察

本次新增协议观察详见[官方协议核查](PROTOCOL-OBSERVATIONS.md)。下列总控提交号为先前只读核查记录，本轮未刷新部署状态。

2026-10-25 只读刷新 GitHub 分支：总控开发分支 `codex/control-plane-reliability-20261024` 为 `c200f86e59b047e6a0b0a6d297cae41c996edf2f`，main 为 `541dbe96cf2f760592d370424e844bee68dc8821`。开发分支存在外部客户端入口，main 未提供该文件；不能将开发分支能力视为已合并或已部署。

- [开发分支客户端入口](https://github.com/haseakii/multi-agent-control-plane/blob/c200f86e59b047e6a0b0a6d297cae41c996edf2f/apps/server/src/client-gateway.ts)：仍保留原生工作台人工审批；二进制产物仅元数据；没有把本文契约实现为平台接口。
- [ACP v1 权限请求](https://agentclientprotocol.com/protocol/v1/tool-calls#requesting-permission)：权限请求可选；该定义没有统一固定超时。取消、断连及实现自身超时必须分别处理。
- [ACP 迁移说明](https://agentclientprotocol.com/protocol/v2/migration)：页面仍将 v2 整体标为 Draft，需按连接协商版本。本文不要求升级协议。

以上为源码和规范观察，不是已运行证明。具体资源隔离、账号、模型、能力、硬预算及审批桥接均待真实接入后验收。Node/TypeScript/React 仍是候选路线，数据库、服务端实现与部署拓扑未冻结。
