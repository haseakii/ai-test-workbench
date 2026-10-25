# AI Test Workbench · AI 测试协作平台

> **设计与原型评审阶段，不是已上线业务系统。** 由用户独立创建的仓库：`haseakii/ai-test-workbench`。当前为 Public，保留用户创建时的 LICENSE 与 .gitignore；本次不调整可见性或开源许可。

面向测试人员的 AI 排查工作台：通过持续对话、截图与附件补齐问题条件，读取获授权的代码、数据库和日志，交付证据与结果文件。后台配置工作流、Skill、MCP、ACP/模型、权限、审批和额度。

**独立业务平台 + 可插拔执行后端。** 总控适配器接入 `multi-agent-control-plane`；受控 ACP 直连宿主支持基础流程独立运行。不 fork 总控、不共用总控数据库、不复制完整多 Agent 引擎。

## 评审入口

| 内容 | 入口 |
| --- | --- |
| 当前决策与待审批事项 | [决策记录](docs/reviews/DECISIONS-20261025.md) |
| 全部资料导航 | [设计文档索引](docs/README.md) |
| 已认可的架构方向 | [ADR-001 当前状态](docs/architecture/ADR-001.md) |
| 完整架构设计原稿 | [ADR-001 v0.1](docs/review-baseline-20261025/architecture/ADR-001_独立平台与可插拔执行后端_v0.1.md) |
| 页面交互完整规格 | [v0.2 Markdown 原稿](docs/review-baseline-20261025/docs/设计说明与交互规范_v0.2.md) |
| 可点击交互原型 | [v0.2 index.html](docs/review-baseline-20261025/index.html) |
| PDF / Word 阅读版 | [生成资料目录](docs/generated/v0.2/README.md) |
| 原型源码及构建脚本 | [source](docs/review-baseline-20261025/source) |
| 历史测试记录 | [测试报告](docs/review-baseline-20261025/tests/测试报告.md) |
| 后续开发顺序与门槛 | [design-to-build](docs/roadmap/design-to-build.md) |
| 原件与阅读版区别 | [归档说明](docs/ARCHIVE-STATUS.md) |

## 打开原型

下载仓库后，直接用 Edge 或 Chrome 打开 `docs/review-baseline-20261025/index.html`。不需要前端安装或后台服务。GitHub 文件页显示的是源码，不是已部署网页。没有启用 GitHub Pages。

前台包含发起问题、持续对话、附件、执行事件、证据、结果验证和历史；后台包含任务、审批、流程、助手、Agent/模型、Skill、MCP、系统环境、用户权限、额度及审计。全部业务数据为合成示例，AI/数据库/审批执行为模拟，不输入真实密码或敏感资料。

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

架构方向已获用户认可；v0.2 页面细节、下一轮原型、执行契约和技术选型仍待评审。原文中的“待审批”等内容作为历史保留，以当前决策记录说明状态。

本次仅入库设计与原型资料，没有实现生产后端、连接业务数据库、调用真实模型或修改总控。文档生成 workflow 只处理本仓库的离线演示与文档。

原始 PDF、Word、26 张截图和原始 ZIP 仍完整保存在资料库 `AI测试协作平台_原型与架构评审_20261025`。仓库保留所有 UTF-8 文本原稿与原型源码，并从这些源码重建 PNG/PDF/DOCX 阅读版；**生成阅读版不保证与历史二进制原件字节或分页相同**，详见归档说明。

## 源稿校验

运行 `python scripts/verify_sources.py`，校验所有入库文本原稿 SHA-256。`docs/generated/v0.2/` 的构建记录与原有 tests/ 报告分开保存，不把历史检查冒充本轮实测。下一步先评审设计，再进入业务开发。
