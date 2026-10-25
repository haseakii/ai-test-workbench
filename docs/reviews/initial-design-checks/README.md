# 初始设计离线检查

本目录仅记录当前设计、合成内存模拟与浏览器交互检查；不是业务执行、Provider 共同契约或实机能力验收。

## 本轮结果（2026-10-25）

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 静态一致性 | 链接、合同、构建、清单一致性通过；49 条转换；22 项未来契约目录 | [static-report.json](static-report.json) |
| 内存模拟约束 | 33/33 通过，含未知接纳、取消竞态及审批恢复 | [model-tests.tap](model-tests.tap) |
| 实际 file:// 与 DOM 交互 | 173/173 通过，含 13 项执行 / 审批 / 资源扩展、9 项日常列表、13 项可用性路径、13 项流程关联 / 对比检查、7 项首次开通链路、8 项标题检查、7 项代码来源检查、8 项系统上下文 / 额度检查、13 项接入治理检查及 1 项不中断的完整配置交付链路 | [browser-report.json](browser-report.json) |
| 布局 | 1 个对话界面 + 13 个配置 / 接入 / 治理 / 管理 + 7 个契约演练视图 + 1 个首次开通演练，4 视口 = 88 组合；整页横向溢出 0 | browser-report.json 的 layouts |
| 浏览器 | Edge 156.0.4314.42；页面脚本错误 0；网页外网请求 0 | browser-report.json |
| 需求覆盖核查 | 14 项设计章节与指定离线用例匹配，浏览器源码哈希一致；真实实现未开始 | [scope-audit.json](scope-audit.json) |
| 历史源稿 | Git HEAD 中 20/20 SHA-256 匹配；本地 20 份 CRLF 差异单列 | static-report.json |

视口：1440×1000、1366×768、1024×900、390×844。已查看[对话首页](screenshots/chat.png)、[对话过程](screenshots/chat-conversation.png)、[窄屏对话](screenshots/chat-mobile.png)、[系统配置](screenshots/admin-systems.png)、[权限配置](screenshots/admin-permissions.png)、[窄屏管理](screenshots/admin-mobile.png)和管理审批截图；检查不构成完整视觉/无障碍认证。当前源码和输出摘要见 [delivery-manifest.json](delivery-manifest.json)。

本轮结果与历史 32 项检查分开。TypeScript 声明未编译；真实 Provider 共同契约、身份/ACL、事务持久性、收费阻断、真实证据正确性及总控关闭后的直连均未验收。CI 配置已加入静态和模拟检查；本次检查在本地完成；归档提交后的远端 CI 以该提交的实际状态为准，历史远端通过不代表当前版本。

检查入口：

- `python scripts/check_initial_design.py`：链接、正式方法目录、转换表/文档一致性、构建一致性及历史源稿 Git HEAD 哈希；单列工作树 CRLF 差异。
- `node docs/prototype/tests/model.test.cjs`：模拟状态与恢复约束；报告为 model-tests.tap。
- `node docs/prototype/tests/browser-check.cjs`：环境提供 Playwright（可使用 NODE_PATH）和 CHROMIUM_PATH；实际 file:// 导航、DOM 操作、下载、焦点及 22 个视图 × 4 视口检查。

浏览器输出 browser-report.json、screenshots/ 及明确标注合成的下载样例。static-report.json、model-tests.tap 分别记录本轮静态检查与模拟约束。

原始 verify_sources.py 仍是工作树逐字节验证；本机 CRLF 差异不被改写为“原件完全一致”。历史 tests/ 与 generated/v0.2/ 不在本轮重写范围。

配置检查覆盖草稿隔离、跨页字段、应用确认与取消、重复名 / 默认模型校验、新增配置授权、角色预览、旧快照保留、环境归属变化、撤权、审批卡失效、空状态、数值限制及演示审计。首轮发现的跨页失焦写入错误已修复并复测；正式发布事务与真实 ACL 不在检查范围。

新增接入 / 治理检查覆盖实际能力未知、ACP 草案分支、MCP schema / 认证 / 越界 / 结果未知、宿主写入 / 终端 / 网络门禁、代码版本、上下文预留、记忆审核及待删除、用户管理角色 / 范围 / 自提权、有效变更确认、暂停、记录范围及正文租约。已查看接入和用户记录截图；不构成真实协议和安全验收。

运行 `python scripts/audit_design_scope.py --browser` 核对设计索引、指定用例和当前浏览器源码哈希；`python scripts/record_initial_design_checks.py` 在浏览器检查后重录静态、内存、范围证据和交付摘要，可用 DESIGN_NODE 指定现有 Node。范围检查只核对章节和用例存在，不能代替语义设计评审。

整体链路的 [walkthrough-checks.cjs](../../prototype/tests/walkthrough-checks.cjs) 使用同一合成用户和会话，检查后台记录、模型 / 计划 / 配置修订、责任人批准、文件交付、结果版本、用户治理、MCP 观察、记忆来源撤权、租约与计划自动到期。已查看[本次记录截图](screenshots/walkthrough-record.png)，下载样例为 [walkthrough-difference.csv](walkthrough-difference.csv)。这些证据不替代真实接入、安全、并发或持久化验证。

本轮新增 [execution-resource-checks.cjs](../../prototype/tests/execution-resource-checks.cjs)，验证资源目录、环境关联、策略交集、自动 / 逐次审批 / 仅收集、审批拒绝及两条合成运行中仅停止指定一条。已检查[环境资源](screenshots/environment-resources.png)、[执行管理](screenshots/execution-management.png)、[审批管理](screenshots/approval-management.png)与[决定确认](screenshots/approval-decision.png)截图。这些检查在本地执行；前次远端 CI 不代表当前归档修订。

日常列表的 [management-list-checks.cjs](../../prototype/tests/management-list-checks.cjs)覆盖审批表格首屏、搜索 / 环境 / 状态与空状态、抽屉和焦点、示例决定隔离、角色、历史计划自己的快照，以及桌面 / 平板 / 手机下固定动作位置。虚拟时钟用例与新列表用例放在不同浏览器上下文，避免模拟时钟跨页干扰定时回复。

当前视觉证据：[审批列表](screenshots/approval-list.png)、[审批抽屉](screenshots/approval-drawer.png)、[手机抽屉](screenshots/approval-mobile.png)、[执行列表](screenshots/execution-list.png)、[成员列表](screenshots/member-list.png)。这些截图和结果仍属合成离线原型；不代替完整无障碍认证、真实服务端分页和并发审批验收。

本轮新增 [usability-checks.cjs](../../prototype/tests/usability-checks.cjs)，验证跨页纠错、目录往返、草稿确认前后区别、焦点、搜索和重置、成员草稿 / 身份隔离、完整现有环境、理由草稿放弃、具体审批条件 / 意见、记录授权与手机操作。

已复查[配置纠错](screenshots/usability-validation.png)、[成员草稿](screenshots/usability-member-draft.png)、[审批条件](screenshots/usability-approval-plan.png)和[窄屏设置](screenshots/usability-mobile.png)，[目录返回](screenshots/usability-resource-return.png)保留原环境草稿。这些只对应本地离线浏览器实例；未控制用户当前内置浏览器文件页，未进行真实用户可用性验收。

新增 [flow-links-checks.cjs](../../prototype/tests/flow-links-checks.cjs)的 13 项用例，核对用户范围与状态、流程草稿 / 发布、环境 / 角色限制、限定 MCP 连接 / 工具、Skill、必需代码、未知或不支持的目标、新输入选择流程、用户问题信息和历史原型对照。真实能力仍为 unknown / not_run。

对照截图：[历史任务](screenshots/reference-tasks.png)、[历史流程](screenshots/reference-workflows.png)、[历史对话](screenshots/reference-chat.png)；当前：[任务中心](screenshots/task-center.png)、[检查流程](screenshots/workflow-list.png)、[用户对话](screenshots/user-journey.png)、[问题信息](screenshots/user-problem-info.png)。历史截图只是只读页面渲染，不是旧版本重新通过交互验收。

首次开通的 [onboarding-checks.cjs](../../prototype/tests/onboarding-checks.cjs)新增 7 项检查：部署 / 身份未验证提示及接入依赖顺序入口、未加入 / 待开通阻断、重复申请、管理员明确范围、开通后的审批交付与独立验收、同一记录正文租约、已有记录禁止重置与窄屏可用性。已查看[首次开通](screenshots/first-use.png)及[手机流程](screenshots/first-use-mobile.png)。

初次失败记录 [first-use-pre-fix.json](first-use-pre-fix.json)保留焦点与等待问题；[first-use-stale-run.json](first-use-stale-run.json)是一轮运行期间测试源发生变化的无效结果，不作为验收。该轮固定源码重建后完整复验为 136/136，88 组布局无溢出，页面错误和外网请求均为零；当前总数见上表。真实部署、SSO、邀请 / 拒绝申请和真实后端仍未运行。

新增 [title-checks.cjs](../../prototype/tests/title-checks.cjs)的 8 项离线检查，覆盖自动概括 / 附件占位 / 一次细化、手动锁定、前后台标题一致、审批与验收不变、输入校验、取消与焦点、同名与修订、移动端及撤权。标题补充当轮完整复验 144/144 通过；[初次失败证据](task-title-pre-fix.json)保留审批筛选误判，已核对全部相关记录。

已实际查看[重命名弹窗](screenshots/task-title-rename.png)和[手机重命名](screenshots/task-title-mobile.png)，窄屏标题与操作分两行显示。自动标题是本地规则演示，真实模型概括、跨客户端推送、服务端 ACL / CAS 和完整敏感识别仍未验收。

[code-source-checks.cjs](../../prototype/tests/code-source-checks.cjs)新增 7 项：GitLab 默认 / Token 不留存、凭据替换与清除、Windows / POSIX 本地目录、路径拒绝、环境与计划快照、来源切换及认证匹配、未保存输入清空与窄屏。代码来源补充当轮完整复验 151/151 通过。已查看 [GitLab 配置](screenshots/code-source-gitlab.png)、[本地目录](screenshots/code-source-local.png)；[窄屏表单](screenshots/code-source-mobile.png)亦保留为布局证据。未连接任何 GitLab、读取业务文件或使用真实 Token，秘密存储及真实路径隔离仍待实现。

[context-quota-checks.cjs](../../prototype/tests/context-quota-checks.cjs)新增 8 项检查，覆盖系统内环境列表 / 归属、背景白名单与资源绑定、旧计划失效、全局草稿与数值校验、成员 Agent / 模型例外、零额度阻断、恢复继承、模型别名共用计量键及窄屏。完整固定源码回归 159/159 通过，88 组布局无整页溢出，页面错误及 HTTP(S) 请求为零。

首轮 [context-quota-pre-fix.json](context-quota-pre-fix.json)保留 155/159 的失败记录：旧菜单断言未更新，以及数值框失焦重绘导致额度输入丢失和连带失败；已修复并完整复验。已查看[系统所属环境](screenshots/system-environments.png)、[全局默认额度](screenshots/global-model-quotas.png)、[成员模型额度](screenshots/member-model-quotas.png)与[手机成员设置](screenshots/member-model-quotas-mobile.png)。截图是独立离线浏览器的合成数据；真实身份、配置持久化、并发预占、Token 统计、内部调用拦截及两种真实执行后端均未验收。

[governance-checks.cjs](../../prototype/tests/governance-checks.cjs)新增 13 项离线检查：合并入口与默认收起、连接模拟发现和指纹失效、直接创建带入连接的模型预设、环境与用户双重授权、撤销环境模型绑定、停用默认连接不换 Agent、运行范围校验、上下文与个人记忆所有者、MCP 变化重新确认、治理角色拒绝、成员草稿预览、固定记录摘要及窄屏。另核对 Agent 身份变更不能继续使用旧模型与额度绑定（已计入上述 13 项）。

接入治理当轮固定源码完整回归 **172/172**；88 组布局无整页横向溢出，页面脚本错误和 HTTP(S) 请求均为零。首轮 [governance-pre-fix.json](governance-pre-fix.json)保留 157/159：两条旧用例遗漏新增的 MCP 工具范围再确认步骤；已更新真实操作路径并复验。静态复核另补 Agent 身份一致性守卫，未将先前通过当作该新约束的证据。

已查看[Agent 接入](screenshots/governance-agents.png)、[运行配置](screenshots/governance-runtime.png)、[上下文与记忆](screenshots/governance-context.png)、[MCP 配置](screenshots/governance-mcp.png)、[成员权限](screenshots/governance-member.png)、[记录摘要](screenshots/governance-record.png)及[窄屏接入表单](screenshots/governance-mobile.png)。所有发现、身份及执行均为合成；真实认证、隔离、模型 / MCP 观察、持久化、并发、凭据轮换、计量和生产权限未验收。


连续走查 [complete-journey-checks.cjs](../../prototype/tests/complete-journey-checks.cjs) 在预置目录内通过 UI 新建运行配置、上下文、ACP Agent、模型预设、代码、MCP 和 Skill，再绑定环境 / 流程 / 角色，完成待开通阻断、管理员授权、同一对话、审批中心决定、下载复算、用户确认和后台记录审阅。配置到审阅没有刷新或直接改写业务状态。证据为 [complete-journey.json](complete-journey.json)、[合成差异文件](complete-journey-difference.csv)和[同一记录截图](screenshots/complete-journey-record.png)。已查看截图；实际模型仍未知，actualExecution 仍为 not_run。这不覆盖空库部署、真实身份或真实执行。

首轮完整回归 [complete-journey-pre-fix.json](complete-journey-pre-fix.json)为 172/173；新增连续用例通过，旧环境返回用例未等待异步 hash 路由完成便读取列表数量。修正等待当前父系统入口出现，并继续校验选中父系统和三条环境，保留原权限断言。用例编写阶段另修正动态复选框索引及折叠详情的可见文本读取；这些属于测试操作路径修正，未变更产品执行逻辑。最终当前结果以上表为准。

第二次回归 [complete-journey-route-followup.json](complete-journey-route-followup.json)也保留为失败证据：新增环境用例已通过，另一个返回环境用例读到了上一系统页的同名说明字段。环境 / 父系统导航现等待目标对象出现后继续；同组 8 项定向检查已复验通过。没有调整业务配置逻辑或降低原断言。

当前固定源码最终完整回归 **173/173**，88 组布局无整页横向溢出，页面错误与 HTTP(S) 请求均为零；连续链路记录与浏览器源码摘要一起进入交付清单。真实服务、身份和两种执行后端仍未运行。
