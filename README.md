# AI Test Workbench · AI 测试协作平台

> **设计与原型评审阶段，不是已上线业务系统。** 由用户独立创建的仓库：`haseakii/ai-test-workbench`。当前为 Public，保留用户创建时的 LICENSE 与 .gitignore；本次不调整可见性或开源许可。

面向测试人员的 AI 排查工作台：通过持续对话、截图与附件补齐问题条件，读取获授权的代码、数据库和日志，交付证据与结果文件。后台配置工作流、Skill、MCP、ACP/模型、权限、审批和额度。

**独立业务平台 + 可插拔执行后端。** 总控适配器接入 `multi-agent-control-plane`；受控 ACP 直连宿主支持基础流程独立运行。不 fork 总控、不共用总控数据库、不复制完整多 Agent 引擎。

## 评审入口

| 内容 | 入口 |
| --- | --- |
| 当前决策与待审批事项 | [决策记录](docs/reviews/DECISIONS-20261025.md) |
| 全部资料导航 | [设计文档索引](docs/README.md) |
| 当前初始设计 | [设计与契约入口](docs/design/README.md) |
| 当前对话原型 | [初始原型](docs/prototype/index.html) |
| 完整需求与接入设计 | [覆盖矩阵](docs/design/DESIGN-COVERAGE.md) · [服务执行](docs/design/SERVICE-EXECUTION.md) · [模型与 ACP](docs/design/MODEL-ACP-ACCESS.md) |
| 管理端配置 | [系统、环境、模型与权限设计](docs/design/ADMIN-CONFIGURATION.md) · [配置原型](docs/prototype/index.html#admin-systems) |
| 执行契约与状态 | [ExecutionProvider](docs/design/EXECUTION-PROVIDER.md) · [分层状态表](docs/design/STATE-MACHINES.md) · [审批恢复](docs/design/APPROVAL-RECOVERY.md) |
| 整体链路走查 | [衔接合同](docs/design/END-TO-END-WALKTHROUGH.md) · [发现与修补](docs/reviews/END-TO-END-REVIEW-20261025.md) |
| 本轮变更与检查 | [变更说明](docs/reviews/INITIAL-DESIGN-CHANGELOG-20261025.md) |
| 已认可的架构方向 | [ADR-001 当前状态](docs/architecture/ADR-001.md) |
| 完整架构设计原稿 | [ADR-001 v0.1](docs/review-baseline-20261025/architecture/ADR-001_独立平台与可插拔执行后端_v0.1.md) |
| 历史页面交互规格 | [v0.2 Markdown 原稿](docs/review-baseline-20261025/docs/设计说明与交互规范_v0.2.md) |
| 历史交互原型 | [v0.2 index.html](docs/review-baseline-20261025/index.html) |
| PDF / Word 阅读版 | [生成资料目录](docs/generated/v0.2/README.md) |
| 当前原型源码与构建 | [prototype](docs/prototype/) · [构建脚本](scripts/build_initial_prototype.py) |
| 历史测试记录 | [测试报告](docs/review-baseline-20261025/tests/测试报告.md) |
| 后续开发顺序与门槛 | [design-to-build](docs/roadmap/design-to-build.md) |
| 原件与阅读版区别 | [归档说明](docs/ARCHIVE-STATUS.md) |

## 打开原型

下载仓库后，直接用 Edge 或 Chrome 打开 `docs/prototype/index.html`。当前统一按初始设计整理，不另分产品版本；原型为单文件离线演示，不需要后台服务。GitHub 文件页显示的是源码，没有部署网页。历史 v0.2 仍可从上表打开。

用户端只有一个类似 Codex 的对话工作台：选择系统和环境，通过快捷问题、文本与附件描述问题，在对话中补充条件、确认检查范围和验证结果。后台集中配置系统、环境、模型和权限，支持草稿、效果预览与应用到离线演示；用户端只选择获准的配置。管理端还可评审 ACP / MCP 接入、执行环境、上下文记忆，以及合成用户和问题记录的权限治理。执行绑定、契约和恢复诊断保留在管理端的次要设计评审入口；历史 22 页作为范围参考，不作为用户导航。全部数据和执行为合成模拟，不输入真实密码或敏感资料。刷新清空本轮模拟，不构成持久性或真实能力证明。

## 架构与职责

```text
测试工作台 / 平台管理
          │
独立业务服务（身份、问题、附件、流程、审批、额度、验收）
          │
统一执行后端契约
     ┌────┴─────┐
总控适配器    ACP 直连宿主
     │           │
multi-agent    ACP Agent
-control-plane
     └────┬─────┘
受控资源服务（代码、数据库、日志、文件）
```

一个执行单元只有一个执行拥有者。运行中固定后端；副作用未知时先核查，不静默切换重复执行。两种后端共享同样的资源权限、审批与额度要求。总控关闭后完成真实基础流程，才算直连独立运行通过验收。

## 当前状态

架构方向已获用户认可；用户已授权完善初始设计和原型。正式契约草案、分层转换表及审批恢复路径已形成，页面细节、合同冻结和技术选型仍待评审。原文中的“待审批”等内容作为历史保留，以当前决策记录说明状态。

当前仅有设计、类型声明、离线模拟及其检查，没有业务后端或真实资源接入。文档检查 workflow 只处理本仓库离线材料。

原始 PDF、Word、26 张截图和原始 ZIP 仍完整保存在资料库 `AI测试协作平台_原型与架构评审_20261025`。仓库保留所有 UTF-8 文本原稿与原型源码，并从这些源码重建 PNG/PDF/DOCX 阅读版；**生成阅读版不保证与历史二进制原件字节或分页相同**，详见归档说明。

## 源稿校验

运行 `python scripts/verify_sources.py` 做工作树逐字节源稿校验。Windows `core.autocrlf=true` 可能使它失败；本轮检查另核对 Git HEAD 中 20 份原稿哈希，并单列本地 CRLF 差异，不把换行等价称为逐字节通过。

运行 `python scripts/check_initial_design.py` 和 `node docs/prototype/tests/model.test.cjs` 检查当前初始设计与内存模拟约束；用 `python scripts/build_initial_prototype.py` 重建离线单文件。浏览器检查入口见[检查清单](docs/design/REVIEW-CHECKLIST.md)。历史记录和本轮报告分开，模拟通过不等于后端或业务验收。
