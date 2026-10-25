# 初始设计离线检查

本目录仅记录当前设计、合成内存模拟与浏览器交互检查；不是业务执行、Provider 共同契约或实机能力验收。

## 本轮结果（2026-10-25）

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 静态一致性 | 链接、合同、构建、清单一致性通过；49 条转换；22 项未来契约目录 | [static-report.json](static-report.json) |
| 内存模拟约束 | 33/33 通过，含未知接纳、取消竞态及审批恢复 | [model-tests.tap](model-tests.tap) |
| 实际 file:// 与 DOM 交互 | 81/81 通过，含 17 项配置、14 项接入 / 治理及 17 项整条链路检查 | [browser-report.json](browser-report.json) |
| 布局 | 1 个对话界面 + 9 个配置 / 接入 / 治理 + 7 个契约演练视图，4 视口 = 68 组合；整页横向溢出 0 | browser-report.json 的 layouts |
| 浏览器 | Edge 156.0.4314.42；页面脚本错误 0；网页外网请求 0 | browser-report.json |
| 需求覆盖核查 | 11 项设计章节与指定离线用例匹配，浏览器源码哈希一致；真实实现未开始 | [scope-audit.json](scope-audit.json) |
| 历史源稿 | Git HEAD 中 20/20 SHA-256 匹配；本地 20 份 CRLF 差异单列 | static-report.json |

视口：1440×1000、1366×768、1024×900、390×844。已查看[对话首页](screenshots/chat.png)、[对话过程](screenshots/chat-conversation.png)、[窄屏对话](screenshots/chat-mobile.png)、[系统配置](screenshots/admin-systems.png)、[权限配置](screenshots/admin-permissions.png)、[窄屏管理](screenshots/admin-mobile.png)和管理审批截图；检查不构成完整视觉/无障碍认证。当前源码和输出摘要见 [delivery-manifest.json](delivery-manifest.json)。

本轮结果与历史 32 项检查分开。TypeScript 声明未编译；真实 Provider 共同契约、身份/ACL、事务持久性、收费阻断、真实证据正确性及总控关闭后的直连均未验收。CI 配置已加入静态和模拟检查，本次整理已获用户提交授权，远端运行结果另以 GitHub Actions 为准。

检查入口：

- `python scripts/check_initial_design.py`：链接、正式方法目录、转换表/文档一致性、构建一致性及历史源稿 Git HEAD 哈希；单列工作树 CRLF 差异。
- `node docs/prototype/tests/model.test.cjs`：模拟状态与恢复约束；报告为 model-tests.tap。
- `node docs/prototype/tests/browser-check.cjs`：环境提供 Playwright（可使用 NODE_PATH）和 CHROMIUM_PATH；实际 file:// 导航、DOM 操作、下载、焦点及 17 个视图 × 4 视口检查。

浏览器输出 browser-report.json、screenshots/ 及明确标注合成的下载样例。static-report.json、model-tests.tap 分别记录本轮静态检查与模拟约束。

原始 verify_sources.py 仍是工作树逐字节验证；本机 CRLF 差异不被改写为“原件完全一致”。历史 tests/ 与 generated/v0.2/ 不在本轮重写范围。

配置检查覆盖草稿隔离、跨页字段、应用确认与取消、重复名 / 默认模型校验、新增配置授权、角色预览、旧快照保留、环境归属变化、撤权、审批卡失效、空状态、数值限制及演示审计。首轮发现的跨页失焦写入错误已修复并复测；正式发布事务与真实 ACL 不在检查范围。

新增接入 / 治理检查覆盖实际能力未知、ACP 草案分支、MCP schema / 认证 / 越界 / 结果未知、宿主写入 / 终端 / 网络门禁、代码版本、上下文预留、记忆审核及待删除、用户管理角色 / 范围 / 自提权、有效变更确认、暂停、记录范围及正文租约。已查看接入和用户记录截图；不构成真实协议和安全验收。

运行 `python scripts/audit_design_scope.py --browser` 核对设计索引、指定用例和当前浏览器源码哈希；`python scripts/record_initial_design_checks.py` 在浏览器检查后重录静态、内存、范围证据和交付摘要，可用 DESIGN_NODE 指定现有 Node。范围检查只核对章节和用例存在，不能代替语义设计评审。

整体链路的 [walkthrough-checks.cjs](../../prototype/tests/walkthrough-checks.cjs) 使用同一合成用户和会话，检查后台记录、模型 / 计划 / 配置修订、责任人批准、文件交付、结果版本、用户治理、MCP 观察、记忆来源撤权、租约与计划自动到期。已查看[本次记录截图](screenshots/walkthrough-record.png)，下载样例为 [walkthrough-difference.csv](walkthrough-difference.csv)。这些证据不替代真实接入、安全、并发或持久化验证。
