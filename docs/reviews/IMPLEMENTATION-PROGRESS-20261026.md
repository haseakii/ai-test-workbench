# 隔离开发阶段检查点

记录始于2026-10-26，最新验证2026-10-27。状态：**真实持久服务与合成 CSV 闭环已跑通；D0–D2 仍在推进，本页供阶段复核，不代表整个实施完成。**

最新工作包为**Windows最少首装操作准备**：推荐一条便携Java＋私有MySQL主路线，已有环境仅兼容。新增免Java依赖说明、只读首装检测/可读方案、守卫SQL/初始化及正常INI、DPAPI代码；Java针对性离线 **103/103**、Python **89/89**、打包后只读检测 **3/3**通过。旧a71f…预览包保持不变，另生成17文件准备包；没有新软件、初始化执行器或实际安装。详见文末及[工作包](implementation-d0-d2/portable-setup-work-package.json)。

**简单搭建没有验收完成**：3动作/0手填数据库密码是目标；官方Windows精确版本/SHA/许可、MSVC闭包、选定Windows机器和私有实例/凭据授权均未齐备，初始化执行器仍须在确切软件/机器范围确定后接通并实机验证。当前Linux测试不替代Windows/DPAPI/MySQL权限与首装实测。原完整服务证据仍绑定60d85b…旧制品，未重启原服务；A01新实例与远端CI仍未运行，未推送或部署。[退出映射](implementation-d0-d2/exit-criteria.json)共28条。

## 授权与基线

用户已要求修复设计并安排开工，授权本工作区设计、代码、测试和合成资源实验，范围为隔离 D0–D2；不含真实 Token、付费模型、公司业务库或改动总控。

仓库为 haseakii/ai-test-workbench，分支 work，HEAD 为 2ae5c516c04efcaefb2d0acbc659737effb3f1ab。原四份未提交文档先备份并保留 stash，再通过 Git fetch 和 fast-forward 完整同步；三处冲突保留远端完整正文及原本地新增章节。历史源稿未改。

## 已保存的实现

| 范围 | 实际内容 | 当前界限 |
| --- | --- | --- |
| 合同 | 安全整数、Java/TS 黄金样例、human/policy 身份、准入票据、原子快照游标、状态目录及 CP23–CP29 | 12 方法 Java 端口/DTO、完整 Schema 与 OpenAPI 已生成及校验；真实适配器未实现 |
| Java | provider-contract、platform-server、execution-runner 三个 Maven 模块和校验发行包的 Wrapper | Runner 只有边界及离线 SDK 验证，没有真实 Agent |
| 持久服务 | 成员授权、显式发布与依赖 CAS、对话/标题/事件、固定审批、额度总分账、活跃槽、outbox、持久替身与核查 | 仅 synthetic profile、固定合成组织和测试身份，不是生产登录或完整执行策略 |
| 材料与结果 | 受限 UTF-8 CSV 检查解析、输入哈希、BigDecimal 差异、文件导入/下载、结果确认和来源撤权 | 不接真实代码、数据库、MCP，也未支持其他附件解析器 |
| 正式前端 | React 对话、系统内环境、成员、审批列表和抽屉、执行及记录列表、下载与确认 | 已接正文资格/用途租约 UI、快照与 SSE 恢复；真实身份及完整后端边界未接入 |
| 访问控制 | 正文、事件、文件逐项鉴权，元数据不授予正文权，显式消息正文资格与短期租约 | 真实身份、多租户场景和全部失效传播仍需后续验证 |

[工程运行说明](../../backend/README.md) · [开发步骤](../development/DEVELOPMENT-STEPS.md) · [合同冻结及五项定向复核](../development/CONTRACT-FREEZE.md)。

fixture_submissions 是平台库内的持久测试替身，用于丢回执和故障试验；它不是 ACP Runner 日志，也不证明跨进程一致性。Runner 独立测试库及迁移历史已建立，真实执行功能未实现。

## 临时数据库账号结果

**已执行且验证通过，当前无此项权限阻塞。**

自动审批最初以“新账号和权限调整需单独确认”为由拒绝。用户随后在主会话明确回复“允许”。执行前确认两个拟建账号不存在，再按审阅 SQL 仅执行一次；没有绕过拒绝或盲重试。

| 身份 | 实际权限与检查 |
| --- | --- |
| workbench | 仅平台库读写；DDL、Runner 库和 mysql.user 访问拒绝 |
| workbench_runner | 仅 Runner 库读写；DDL 和平台库访问拒绝 |
| workbench_migration | 仅两个测试库的迁移权限，无 GRANT OPTION，不能读取 mysql.user |

3 项权限测试通过。Web 进程不持有迁移或 Runner 密码；凭据及私有连接配置位于仓库外。变更文件未发现本任务生成的数据库密码。

## 上一检查点测试证据（44 项版本）

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| Java 核心合同 | 9/9 | [当前 Java 44 项明细](implementation-d0-d2/java-contract-tests.json) |
| 真实 MySQL 与权限 | 9/9，0 跳过 | 同上；唯一绑定、精确键、UTC/Decimal、UUID/JSON、租户复合外键、最后一槽及权限拒绝 |
| SDK 与依赖方向 | 5/5 | 同上；ACP/MCP Jackson 3，ACP stdio 初始化/权限回调/取消，以及子进程无数据库秘密 |
| 身份与事务自审 | 13/13 | 3 项启动/安全整数拒绝，10 项实际业务事务回滚、并发、快终态和接纳核查 |
| 命令命名空间与当前成员状态 | 6/6 | 真实 MySQL 跨租户测试回执、跨主体/命令类型及撤权/暂停负面验证；测试数据全部回滚 |
| TypeScript 合同 | 10/10 | frontend/tests/contracts.test.ts，共享黄金样例、Schema 和状态边 |
| HTTP 服务 | 15/15 | [当前完整记录](implementation-d0-d2/service-checks-contracts.json) |
| 新增 HTTP 自审 | 15/15 | [修复后及最终复验](implementation-d0-d2/hardening-contracts.json) |
| 正式 UI | 5/5（最终制品复验） | [Playwright](implementation-d0-d2/ui-contracts-report.json) · [链路记录](implementation-d0-d2/ui-journey.json) |
| API 编译一致性 | 2/2 | 当前 Java 结果；控制器/请求记录导出比对及缺失/null 决定拒绝 |
| 进程中断 | 5/5 | [五个实际窗口](implementation-d0-d2/process-interruption-checks.json) |
| 构建 | TypeScript、Vite、Maven verify 通过 | Jar 摘要和前端资源一致性见清单；Wrapper -v 已验证 3.9.11 |
| 文档与原稿 | 静态一致性及 diff 格式通过，20 份历史 UTF-8 原稿 SHA-256 匹配 | 文档检查不代替服务验收 |

HTTP 检查覆盖实际差异文件到用户确认、越权、幂等、快照续读、固定范围审批、停止、排队过期、丢回执单次结算、输出哈希失败、来源撤权、元数据/正文隔离、依赖 CAS 与紧急停用、手动标题、成员撤权、正文资格及租约到期/撤销、服务重启。

首次 UI 从未开通成员申请开始，见[首次记录](implementation-d0-d2/first-ui-journey.json)。重复运行复用已开通成员，不重置数据库或伪称每次都新建身份。最终 UI 验证配置发布、授权、上传、审批、实际计算、下载、确认、管理查阅和 390 像素布局，另检查 Escape 焦点返回与身份切换。

合成明细为 100.00/200.00/50.00，报表为 100.00/190.00/70.00，实际产生两条差异 -10.00、20.00，总差额 10.00；下载哈希与服务端一致。没有真实模型调用或费用验收。

## 上一检查点命令与失败记录

Java 通过仓库外已验证的 Maven 3.9.11 执行，使用任务代理、CA 和缓存；测试凭据只通过进程环境注入。核心命令如下：

```sh
mvn -f backend/pom.xml package -B -ntp
npm --prefix frontend run test:contracts
npm --prefix frontend run build
python scripts/synthetic_service_check.py --runtime-dir <private-runtime-dir> --runtime-config <private-runtime.json> --java-home <jdk21>
npm --prefix frontend run test:e2e
python scripts/check_initial_design.py
python scripts/verify_sources.py
git diff --check
```

本次 Java 44 项为实际执行，0 失败、0 错误、0 跳过。无数据库变量时相关用例会跳过，不能称为 MySQL 通过。

- 早期 Java 编译曾因 FilterRegistrationBean 包名和匿名类型泛型推断失败，修正后完整构建通过；失败日志保留。
- 首次 npm 安装发现 Vite 8 与 React 插件 5 的 peer 冲突，改用受支持的插件 6.0.0，没有 force/legacy-peer-deps。
- tsx CLI 的 IPC 被默认沙箱拒绝后，采用同库 node --import tsx 入口，没有绕过网络或权限拒绝。
- 扩展 HTTP 首轮 13/14：下载已因来源失效返回 409，用例误期望无权对象的 404。改为断言 SOURCE_REVOKED_OR_CHANGED，仍要求无文件交付；[原失败记录](implementation-d0-d2/service-first-matrix-failure.json)保留，最终 15/15。
- 人工看图发现环境默认值和结果卡片验证状态不同步，修复重建后 UI 3/3 通过。

## 上一检查点环境与后续工作

构建和本地服务使用 Temurin 21.0.9、Maven 3.9.11；Boot 4.1.2、Connector/J 9.7.0、Flyway core/mysql 12.4.0、Jackson 3.1.7；Node 24.19.0、React 19.3.1、Vite 8.3.5。离线 SDK 为已发布的 ACP 0.80.0、MCP 2.1.0。

MySQL 固定镜像为 mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69，实际 8.4.13，READ-COMMITTED、UTC、ROW，JDBC 验证该合成实例 CA。平台迁移到 V4，Runner 独立历史到 V1。

检查点的进行中 outbox、未关闭 Binding、活跃槽及预占均为 0，见[数据库状态](implementation-d0-d2/database-state.json)。服务和 MySQL 保留运行，代码及数据保留，无挂起长工具调用或不明数据库写入。

当前无环境或权限阻塞。按阶段复核请求在此保存，本轮已补完整 Provider 端口/DTO/Schema、现有控制器 OpenAPI、CI 本地配置与五个服务进程中断窗口。CI 远端和全新 hosted-job 准备未运行；完整 MY/CP 矩阵及独立 Runner 仍见下方退出映射，不自动宣称 D0–D2 整体完成。

完整 MY01–MY14、全部 CP 用例、真实 Provider、Agent 隔离、生产身份及备份恢复尚未完成；D3/D4 真实接入不在本轮实施范围。阶段成果不代表整个平台或 D0–D2 全部验收完成。

## 本轮定向自审与修复

先对原运行服务复现 7 项，全部失败，证据为 [修复前](implementation-d0-d2/hardening-before-fix.json)。这些是实际代码缺口，已修改实现并以同一类 HTTP 场景复验，未以文档声明代替修复。

| 发现 | 实际修复 | 验证 |
| --- | --- | --- |
| mutation 重放及两个回执查询入口绕过当前对象权限 | 服务端 `CommandScope` 写入命令回执；等内容重放和查询在事务内重新鉴权。无可信 scope 的旧回执直接查询返回 410，等内容且仍获权的重放才补 scope | R01–R03 |
| Map 配置的 `1.5` 经 longValue 截断；任意 backend 声明被保存 | 精确整数转换和安全上界；model/workflow/environment 各自字段白名单、依赖类型与枚举；发布重新校验 | R04、R07，SyntheticBoundaryTest |
| 新消息仅阻止旧结果确认，仍将旧产物当作当前卡片；附件未推进输入修订 | 新消息/附件推进输入并使当前产物失效；旧结果下载/确认返回冲突；已算完但晚发布的产物保留为 superseded，不覆盖新输入状态 | R05、R08及两项 Java 新输入时序测试 |
| clientMessageId 换命令键重发被修订冲突拒绝 | 先按当前会话中的 clientMessageId 检查等内容；异内容明确冲突，不重复保存消息 | R06、R09 |
| 合成身份只有 profile 检查，且环境权限依赖请求初始角色 | 启动限制 exact synthetic profile、IPv4 回环绑定、回环平台测试库及数据库 marker；授权读取当前角色 | 2 项启动拒绝测试及实际启动 |
| 审批/分派原子性缺故障证据，分派审计未入同一事务 | 分派审计写入同一事务；测试在真实审计写入后抛错，验证业务记录全部回滚；两个事务竞争最后额度或槽位 | DispatchAtomicityTest 4 项 |
| 旧审批在已接纳但工具未启动时仍可能执行；新输入可被晚结果覆盖 | 执行前检查审批当前状态；已执行事实独立保留，结果按 inputRevision 判定当前性；停止使未闭合审批 superseded | Java 2 项新输入时序测试 |
| SSE 线程池无界排队、界面依赖轮询；正文租约 UI 未实现 | 限额虚拟线程流，每帧复查权限；前端从原子快照续接、断流恢复；成员页授予资格，记录页按用途读正文并检查租约状态 | R11、浏览器恢复/正文两项 |

正文租约只授予消息正文，未授予事件或文件。前端租约本地到期即清理，并每 1.5 秒向服务端检查有效性；撤权后下一次检查清除正文。这不能撤回用户此前已读取或复制的数据，也不宣称浏览器收到撤权的瞬间已清理。后端每次读取独立鉴权。

新增类型仍未形成完整 Provider Schema/Java DTO。`fixture_submissions` 仍在平台库，独立进程的网络/持久边界未被本轮测试覆盖。Runtime 启动门禁是合成环境误配置保护，不是生产身份方案。

### 本轮命令和重点验收对应

```sh
mvn -f backend/pom.xml package -B -ntp
npm --prefix frontend run build
npm --prefix frontend run test:contracts
python scripts/synthetic_hardening_check.py --output docs/reviews/implementation-d0-d2/hardening-after-fix.json
python scripts/synthetic_service_check.py --runtime-dir <private-runtime-dir> --runtime-config <private-runtime.json> --java-home <jdk21> --output docs/reviews/implementation-d0-d2/service-checks-hardening.json
npm --prefix frontend run test:e2e
```

Java 命令使用仓库外任务私有连接配置、既有获准账号及 CA；凭据只注入进程环境。Java 44/44、0 失败/错误/跳过，核心/完整 Provider 合同 9、MySQL 语义/账号 9、SDK 5、启动门禁/整数 3、业务事务与故障 10、命令命名空间/成员状态 6、API 编译一致性 2。

| 重点场景 | 实际测试名/证据 | 状态与范围 |
| --- | --- | --- |
| 首次快照已终态 | firstProviderSnapshotAlreadyTerminalSettlesOnce | 通过；真实 MySQL 持久替身，重复核查不多扣 |
| 接纳前取消 | 原 HTTP fixed scope approval and stop before external acceptance；concurrentStartAndPermanentCloseCannotResurrectKey | 通过；关闭 proof 后不能复活 |
| 接纳后取消/丢回执与关闭竞争 | lostReceiptReplayRacesCloseWithoutSecondAcceptanceOrCharge；HTTP queued approval expiry | 通过；保留原 receipt，一次入账 |
| 最后额度/槽位 | twoDispatchTransactionsCompeteForLastTotalAndModelQuota；twoDispatchTransactionsCompeteForLastActiveSlot | 通过；两线程调用实际分派事务，各只有一个成功 |
| 未知接纳不释放 | nonAuthoritativeNotFoundRetainsAllReservationsAndOwner | 通过；not_found 后 quota 与 slot 保持占用 |
| 发布后旧计划失效 | R10 publishing environment invalidates old plan | 通过；环境修订变化后旧批准返回 CONFIGURATION_CHANGED |
| 撤权后的回执/正文/文件/事件 | R01–R03、R11，原 HTTP membership revocation / source revocation / scoped content lease，新增 UI 正文场景 | 通过当前合成组织/身份范围；真实 Agent 私有会话尚未验证 |
| 快照 W 到 W+1 | R11 SSE snapshot W reconnects W+1 and revocation ends access | 通过；实际 HTTP SSE header 游标重连，连续 W+1/W+2 |
| 服务重启不重启原执行 | 原 HTTP service restart restores original execution and artifact | 通过；同一 Binding/产物、用量不增；执行中强杀窗口未运行 |
| 事务中途故障 | approvalAuditFailureRollsBackDecisionOutboxNodeAndCommand；dispatchAuditFailureRollsBackAllAccountsSlotBindingTicketOutboxAndEvent | 通过；实际 MySQL 回滚，不是 H2 或仅 mock |

本轮首次构建的负向 profile 测试受外层 SPRING_PROFILES_ACTIVE 影响，修正测试的属性隔离后通过。原 15 项 HTTP 初次重跑 14/15：同键异内容错误先返回新目标的 404，现已修复顺序并最终 15/15；[失败证据](implementation-d0-d2/service-hardening-first-failure.json)保留。等内容回执仍必须通过当前授权，未回退这项修复。

### 上一检查点的 D0–D2 条目矩阵（本轮增量见后文）

| 开发步骤/条目 | 已完成并有证据 | 未完成/未运行及依赖 |
| --- | --- | --- |
| D0 / 01 合同 | G01–G04 核心规则、状态、黄金样例及 7 项 TS/5 项 Java；本轮补齐遗漏的 revision/queue 数值别名 | 完整 12 方法 Java 类型、Provider/事件 Schema、OpenAPI 与逐字段一致性检查未完成；下一批先做 |
| D0 / 02 依赖、MySQL | 锁定工具链；真实 MySQL 8.4.13、UTC/Decimal/约束/键/事务及 SDK 离线夹具 | MY01–MY14 全覆盖未完成，真实后端能力仍 unknown/not_run |
| D1 / 03 工程迁移 | 三模块、Wrapper、锁文件、平台 V4/Runner V1、运行账号拒绝 DDL/跨库、增量迁移及重启 | CI 尚未建立；V4 全量从空库迁移未另开库运行，未破坏现有数据 |
| D1 / 04 身份配置 | 服务端测试身份解析；启动误配置拒绝；严格合成配置、发布依赖 CAS、紧急停用 | 生产身份不在本轮；完整多租户业务面场景未实现 |
| D1 / 05 对话事件 | 持久消息、跨命令键去重、标题 CAS、输入修订、回执重新鉴权、SSE 恢复 | 完整理解用途记录/结构化追问编排未实现；多标签浏览器并发尚未运行 |
| D1 / 06 审批额度执行 | 责任人固定范围审批；原子预占/审计/outbox；未知占用保留、fast terminal、关闭竞争及最后额度/槽位 | 独立 Provider/OpenAPI 接线后再跑完整 CP；sending/回执落库各窗口的进程强杀未运行 |
| D2 / 07 材料结果 | 实际 CSV 校验/计算、输入/产物哈希、下载确认、来源失效、旧结果清理；正文短租约 | 完整上下文清单、接管包与更多格式未实现；不声称有防病毒或真实资源网关 |
| D2 / 08 正式前端闭环 | 合成成员→配置→上传→固定审批→差异→下载确认；正文资格/租约、断流恢复 UI | 全部退出条件未达到；先补 01/03 的合同和 CI，再补故障/恢复矩阵 |

必须串行：完整边界/Schema冻结→适配接线与 CI→最终同版本全部检查。界面和只读说明可基于稳定合同并行，但最终证据必须指向同一制品。本轮没有环境/权限 blocker；剩余是上述明确的工程工作，不是需要用户再次批准的事项。真实 Provider/付费模型/公司库/部署仍在本任务授权边界之外。

核心路径：`backend/platform-server/.../infrastructure/CommandScope.java`、`Store.java`、`identity/Scope.java`、`SyntheticBoundary.java`、`ContentReview.java`、`conversation/ConversationService.java`、`configuration/ConfigurationService.java`、`execution/ExecutionWorker.java`、`FixtureProvider.java`、`material/Materials.java`；迁移 `V4__receipt_scope_and_synthetic_marker.sql`；测试 `DispatchAtomicityTest.java`、`SyntheticBoundaryTest.java`、`scripts/synthetic_hardening_check.py`；UI `frontend/src/shared/api.ts`、`frontend/src/main.tsx`、`frontend/tests/e2e/recovery-and-content.spec.ts`。

## 幂等冲突顺序的补充复核

本次按父端复核要求检查 409 是否可能暴露他人请求。现有 Store 查询本来就限定 `tenant_id + subject_id + kind + idempotency_key`，没有全局 key/hash 查询；本轮在该查询前增加当前成员状态检查，防止暂停/不存在的成员在仅哈希冲突路径越过当前身份门禁。HTTP 测试身份先由 SyntheticBoundary/Scope 从服务端夹具解析，调用方不能提交 actor UUID 或 tenant 作为命令身份。

现在的顺序为：服务端解析身份 → 事务内复查当前租户成员状态 → 在租户/主体/命令命名空间查询幂等记录 → 同命名空间异内容返回 409 → 等内容时复查当前环境/会话/正文/产物权限 → 才返回原回执。未开通成员只有 membership.request 可用；SUBJECT 回执也拒绝 suspended 成员。错误哈希不返回任何缓存字段，同内容重放没有绕过当前权限。

新增 `backend/platform-server/src/test/java/io/aitest/workbench/CommandNamespaceTest.java` 六项，全部用实际 MySQL：

| 测试名 | 负面条件与断言 |
| --- | --- |
| crossTenantSameSubjectKindAndKeyCannotExposeConflictOrCachedReceipt | 另一租户、相同主体/方法/键分别植入同哈希和异哈希回执；当前租户正常新建自己的回执，既不返回 409，也不返回另一租户的 canary |
| crossSubjectSameTenantKindAndKeyCreatesSeparateOwnedReceipts | 同租户两主体复用同键，各得到独立会话与回执，互读对方会话被拒绝 |
| crossCommandKindSameTenantSubjectAndKeyCannotCauseHashConflict | 另一方法名下同键异哈希回执不影响当前方法；只有当前方法自己的异内容重试返回 409 |
| inactiveOrUnknownMembershipIsRejectedBeforeEqualOrConflictingHash | 暂停成员的等内容/异内容均返回 403；不存在的主体返回 401，均不走可泄露哈希冲突的分支 |
| revokedObjectEqualContentCannotReplayAFormerlyAuthorizedReceipt | 成员仍存在但环境授权已撤销，等内容 mutation 重放和回执读取的 scope 检查均返回 404 |
| pendingMembershipCanRequestAccessButCannotProbeOtherCommandHashes | 未开通成员可保存/重放开通申请，不能借相同 key 访问其他命令 |

跨租户测试只在既有获准的平台测试库内插入第二租户的表行及回执 canary，不创建数据库或账号；测试事务全部回滚，后续实际检查 foreignTenantRows 和 namespaceFixtureSubjects 均为 0。运行服务仍固定一个合成租户；未宣称新增多租户 HTTP 入口或完成多租户业务验收。

新增 HTTP R12–R15 验证另一用户盲用相同键时不会得到 409/缓存回执，获权后同键各自产生独立回执，不同命令同键独立，缺身份先返回 401，以及来源撤权后等内容产物确认与回执读取都重新鉴权。R01–R03 继续验证原用户撤权后的等内容重放与查询。该批 Java 38/38（当前累计 44/44）、原 HTTP 15/15、新增 HTTP 15/15 通过；具体命令不变，完整测试名见当前 JSON 证据。没有新增权限或环境 blocker。

## Provider / API / CI / 中断恢复检查点

本轮新增完整边界与故障证据，全部改动仍未提交、推送、部署。当前分支 work、HEAD 2ae5c516c04efcaefb2d0acbc659737effb3f1ab。保留此前改动、服务及数据库，没有新建当前任务数据库账号或修改权限。

### 合同与 API

唯一 Provider 结构来源仍是 `docs/design/contracts/execution-provider.d.ts`。`scripts/generate_provider_contract.mjs` 先做严格 TypeScript 编译，再生成 `ProviderTypes.java`、`ExecutionProvider.java`、完整 `provider.schema.json` 与 `core.schema.json` 投影；`--check` 逐字检查产物是否过期，遇到不支持的语法会失败。内部 Binding/ticket/command 及明确的平台对象使用 Uuid，外部 Provider 引用保留不透明 Id；游标独立为非空 Cursor。

Java 端口有完整 12 方法；DTO 用 record、封闭联合类型和严格编解码器，拒绝缺字段、额外字段、重复键、非法判别式、小数整数、越界整数及时间精度丢失。共享 `provider-vectors.json` 覆盖 12 方法请求/响应、未知接纳错误，以及 ExecutionSpec 排除 admission 的内容哈希、完整 specHash 和 command 请求哈希。Java 往返和 TS Schema 验证同一份样例。它们是 wire fixtures，不是已执行的后端能力声明。

`ApiContractTest` 从实际控制器和请求 record 导出 OpenAPI：34 条路径、36 操作，42 个已审阅响应投影与错误响应；没有创造 `/provider/start` 等不存在的 HTTP 入口。`check_api_responses.mjs` 编译 322 个响应校验器；最终原 HTTP 15/15 与新增 HTTP 15/15 共实际校验 380 个 JSON 回包，操作/状态覆盖数量在各自 JSON 的 apiContract 字段，不能把编译了 322 个校验器说成实际跑了所有响应分支。

当前 Maven verify 44/44、0 跳过；TS 10/10、前端构建通过，正式 UI 5/5。完整明细分别见 [Java](implementation-d0-d2/java-contract-tests.json)、[原 HTTP](implementation-d0-d2/service-checks-contracts.json)、[新增 HTTP](implementation-d0-d2/hardening-contracts.json)、[UI](implementation-d0-d2/ui-contracts-report.json)。

### 运行制品与真实进程中断

故障测试初次在准备配置时超时，尚未发出终止信号，见[首次失败](implementation-d0-d2/process-first-setup-failure.json)。排查发现运行 target Jar 被后续构建覆盖，延迟类加载报 NoClassDefFoundError。已修启动脚本：按 SHA-256 复制到任务私有只读制品路径，核验摘要后启动，并记录 PID、Java、源 Jar、实际 Jar 与摘要。迁移也使用固定副本。后续构建不覆盖运行文件；[构建后读取验证](implementation-d0-d2/immutable-artifact-check.json)通过。

SyntheticCheckpoints 默认关闭，仅私有进程配置可指定一个 Binding 和一个窗口，无 HTTP 开关；非 synthetic/非回环启动仍被拒绝。测试在标记文件到达后核验 PID、Java 可执行文件、固定 Jar 路径/摘要和 synthetic 进程环境，再用 pidfd 终止这个任务进程。没有信号发给 MySQL、共享或用户进程，没有删除数据库、文件产物或未知占用。

| 实际中断窗口 | 恢复结果 |
| --- | --- |
| approval_before_commit | 已写但未提交的审批/outbox/回执全部回滚；按原键重试，最终只接纳和扣一轮 |
| dispatch_committed | 已预占但还无接纳事实；重启后 acceptance_unknown，额度/槽位保持占用，显式关闭证明后释放，零扣费、零产物 |
| provider_acceptance_committed | Provider 接纳持久化而平台尚无回执；查回原 receipt，原 Binding 完成，只扣一轮 |
| platform_receipt_committed | 平台接纳及结算记录已落库；重启不再扣第二轮，继续原执行 |
| provider_result_committed | Provider 结果及 Blob 已持久化，平台尚未导入；重启沿用原 artifactId，未重新计算/生成新 Blob |

五项均通过。每项记录前/中断/恢复状态、receipt、Binding、产物数量、实际新增 Blob 数、占用及轮次增量；模型调用均为 0，费用为不适用，不能泛化为真实模型费用验证。

中断矩阵使用固定制品 ea67c030e5c9274cf6d965e0ed2cd8c3c68daddeb0ef891f58f31d05886e8af8；随后补生成类型的标识约束并最终编译，最终 Jar 摘要见检查点。两制品的 **43 个平台运行类字节完全一致**，见 immutable-artifact-check.json；只有契约生成类型等制品内容变化，没有把早期不同的执行代码测试冒称当前代码证据。当前最终 Jar 已重新启动并通过 HTTP/Schema 与 UI 复验。

### CI 配置与验证界限

`.github/workflows/development-ci.yml` 使用 5 个从官方 actions 仓库核实并固定的 SHA，只给 contents:read，checkout 不保留凭据；MySQL 固定同一镜像且只映射回环。工作流覆盖生成检查、TS/构建、真实 MySQL 权限/事务、HTTP Schema、进程中断、UI 和历史源稿检查。测试汇总脚本会拒绝数据库用例被跳过或必要测试类缺失。

`ci_prepare_mysql.py` 只在 GitHub-hosted job 内接受该 job 的精确服务容器 ID，配置临时的两个合成库、三个分权账号与 CA。私有配置仅写 RUNNER_TEMP，不上传。**本任务未执行该准备脚本的有效分支，没有创建额外容器或账号；非 CI 环境拒绝测试通过。** YAML、权限/端口、脚本语法与本地等价验证命令已检查，[CI 证据](implementation-d0-d2/ci-configuration-checks.json)。远端 CI 与 hosted-job 新库准备尚未运行，未因此擅自推送。

### 退出条件与后续衔接

27 条映射见 [exit-criteria.json](implementation-d0-d2/exit-criteria.json)。仅 A 为当前可继续的 D0–D2 工作；B 为未推送而尚未执行的远端 CI；C 为 D3/D4 后续接入，不计为 D0–D2 阻塞。MY12 独立 Runner、MY14 恢复保留作阶段参考，已明确 countsTowardD0D2=false；MY13 的账号隔离已通过，真实 Agent 攻击测试移 C。

下表保留工作包依赖；A02/A07 已在后续授权下完成本包验证，其余未实施。每包为一次可独立复核的改动/验证单元，预计用例数仅用于拆分；没有创建新库/账号或修改网络安全设置。

| A 包 | 具体缺失功能/测试与交付 | 前置依赖 | 预计验证单元 |
| --- | --- | --- | --- |
| A01 迁移就绪/中断 | **门禁/中断已完成，原始空库链待授权**：运行账号只读校验 V1–V5 成功历史与 checksum；前缀夹具验证缺失/失败/未来/改动历史及迁移子进程中断，不改原历史、不 repair。原始空库链不能用前缀 DDL 冒充；新增一个私有 MySQL、两个合成库与三个分权账号的[精确方案](implementation-d0-d2/a01-empty-instance-proposal.json)已申请，未执行。 | 既有账号内 4 项 MySQL 测试通过；额外空实例待明确批准 | 剩余：原始平台 V1–V5、Runner V1 空链/重启/幂等与账号拒绝 |
| A02 精确总分账/周期预占 | **本包完成**：QuotaLedger 与 V5 冻结 total/所选模型键和 UTC 周期，9 项隔离/周期测试通过；未选账户不变、总额不逃逸、首次并发建账、跨日未知保留及重复终态。两个模型/连接为合成标识，不是真实适配器验证。 | A07；V5 使用既有迁移账号 | 3 项失败复现→通过，另 6 项周期/数值测试 |
| A03 统一端口/替身未知核查 | 当前 Worker 直接依赖 FixtureProvider 的 Map，未运行完整 Java 端口。加仅合成适配层，映射实际支持的方法；不支持的权限/恢复明确 UNSUPPORTED。补 executing 后进程中断：未知继续占用，只有当前替身可核实的终态证明才关闭，不重跑。不是实现真实适配器或独立 Runner。 | A02 账户快照；A09 固定清单 | 1 包，6–8 场景 |
| A04 并发唯一性/幂等/事件 | **本包完成**：9 项独立活动事务在首次提交前重叠的验证，覆盖 NodeRun 唯一绑定、同键/消息 ID、回滚重试、终态一次结算与迟到事件/快照续接；另 2 项失败复现修复后通过。Store.event 自身持有事务；关闭前后均比较重复终态事件哈希。 | 原租户守卫/唯一索引保留；屏障仅测试代码；A07/A02 账户对未改 | 11 项 MySQL 检查，9 组活动连接/提交窗口证据 |
| A05 队列/锁/COMMIT 断连 | 双消费者锁住队列项及额度项，检查 SKIP LOCKED 仅作用队列、实际索引与等待；专用测试连接制造死锁/锁超时；用该连接的透明故障代理区分 COMMIT 请求/回执丢失，TLS 保持验证。按原键查真实提交事实，未知不释放，不终止 MySQL/共享连接。 | A04 屏障；A02 账户锁序；自有连接故障代理 | 1 包，6–8 场景 |
| A06 准入竞争 | 确定性交错发布/撤权/降限/停止/期限与出队、分派已提交、首次接纳；校验守卫获胜时不新接纳，先接纳则保留事实、不重复扣费。 | A07；A02；A04 屏障 | 1 包，4–6 组交错 |
| A07 可注入时间/精度边界 | **本包完成**：统一注入 Clock，UTC 跨日、非 UTC Clock zone、23/25 小时 DST、首次接纳/正文租约/队列微秒边界、366 天范围及 Decimal 上界/超限/10,000 行汇总均通过；未改系统时间。 | 时钟端口；既有真实 MySQL | 10 项 TimeAndDecimalTest＋周期测试 |
| A08 未知写入/双标签 | **实现及定向检查已完成**：标签页内持久保存原键/消息 ID，查原回执后原键重试；切身份清正文、不自动回放，参数不同不能替代未知提交。双标签名称、草稿哈希 CAS、审批冲突保留输入并显式更新基础；分步建环境保存已确认步骤。另修复后台刷新覆盖明确环境选择的竞态。 | 命令查询与服务端重新鉴权；不把双标签顺序冲突测试当作 A04 同时提交矩阵 | 9 项客户端逻辑、8 项新增 E2E及真实 MySQL草稿覆盖回归 |
| A09 规则补齐/上下文清单 | 补原步骤05/07的无模型规则补齐/独立理解用途记录，工具集为空；冻结输入修订、消息/附件引用和摘要、配置/授权引用及用途，持久化规范哈希；新增输入/撤权使旧清单不可用。不是接真实 Agent。 | 既有输入/授权；原步骤05/07 | 1 包，4–6 场景 |
| A10 合成接管包 | 原步骤07的计划/审批摘要、Binding/接纳/停止事实、输入/算法/产物证据及用户验证/未决项可核对导出；复用现有详情入口，独立鉴权审计，不包含凭据/私有路径。不是备份恢复。 | A09；A03 统一事实 | 1 包，3–5 场景 |
| A11 授权入口/响应补齐 | 对现有上传、他人附件关联、正文租约、产物和事件补跨用户/环境/撤权交错；新增清单/接管包也逐项鉴权。不存在的原附件下载路由的404不算权限证明；补尚未实际采集的API操作/状态回包验证。 | A03/A08/A09/A10；现有角色 | 1 包，6–8 场景 |

A02/A07、A08 和 A04 已按包修复；A01 门禁/前缀中断已完成，原始空链仍需额外隔离实例授权。其余 A03、A05、A06、A09–A11 按表依赖推进，未纳入本包。A04 独立并发事务证据已补，不以先前顺序双标签 CAS 代替；A05 队列/锁范围/死锁/COMMIT 断连仍未完成。

B01：仅指未推送而未触发的 GitHub hosted 工作流、该 job 专用 MySQL 准备与浏览器安装/完整 job 首跑。**本地原始完整空库链归 A01，逻辑上不需要推送。** 当前 V4→V5 增量通过不能替代空链，也不能替代远端结果。没有已知远端运行失败，本轮不推送或创建新环境。

C01（D3）：真实总控/ACP适配器、实际能力与共同CP。C02（D3）：独立Runner日志/进程拥有者/fencing、Agent OS/网络/真实资源门禁和原生会话净化。C03（D3/D4）：真实身份/组织、业务资源/模型凭据及外部部署。C04（D4）：两库、文件、密钥引用恢复、RPO/RTO和运维试点。C均不阻塞D0–D2。

此前材料复核轮只纠正44/32、44/38两处计数；后续本包才修改 A07/A02 实现并运行下列测试。不要把材料轮与实现轮混为一轮，亦不把 C 混入本阶段退出条件。

### A07＋A02 定向实现与证据

先在真实 MySQL 8.4.13 上运行 QuotaIsolationTest，3/3 失败，分别复现未选模型 reserved=1、consumed=1 及无关模型余额耗尽阻塞本次分派，见[修复前记录](implementation-d0-d2/quota-clock-before-fix.json)。修复后这三项和原事务/权限回归均通过；最终 Java [63 项逐项结果](implementation-d0-d2/java-quota-clock-tests.json)中，新增 QuotaIsolationTest 3、QuotaPeriodTest 6、TimeAndDecimalTest 10 项。

QuotaLedger 取代按 subject/period 的批量更新；reservation 固定 `total_account_key`、`model_account_key` 和原 `period_key`。消费与释放只改这对账户，重复终态不重复扣费/放槽；范围未知返回 QUOTA_SCOPE_UNKNOWN，保留占用。首次账户创建受稳定租户守卫和唯一键保护。该守卫粒度较粗，本包验证正确性，没有声称并发吞吐达标。

TimeConfiguration 注入 UTC Clock，Store.now() 统一微秒精度；审批、接纳、正文租约、审计/事件和队列租约统一入口。亚微秒计划范围拒绝，366 天上界精确比较；CSV 原始高精度事件仍按原值匹配半开区间。实际 23/25 小时 DST 日验证重复小时及排除结束点；10,000 行最大金额差异汇总为 `19999999999999800.00`，保持十进制字符串，不经 double。

V5 只向获准平台库迁移，Runner 保持 V1；[迁移前后摘要](implementation-d0-d2/quota-v5-migration.json)证明余额、状态、周期及槽位未变。162 条旧记录可确认账户对，2 条已关闭的旧失败测试记录范围不明，保留 NULL；开放未知范围为 0，没有猜测或更正旧账。未新增库、账号或权限。

实际命令（私有配置与密码不入库）：

```sh
# verify-backend.py 在仓库外注入获准数据库环境，实际执行 Maven 3.9.11：
mvn -f backend/pom.xml verify -B -ntp
python scripts/collect_java_results.py --require-complete --output docs/reviews/implementation-d0-d2/java-quota-clock-tests.json
python scripts/synthetic_service_check.py --validate-api --runtime-dir <private-runtime-dir> --runtime-config <private-runtime.json> --java-home <jdk21> --output docs/reviews/implementation-d0-d2/service-quota-clock-checks.json
python scripts/synthetic_hardening_check.py --validate-api --output docs/reviews/implementation-d0-d2/hardening-quota-clock-checks.json
python scripts/synthetic_crash_check.py --runtime-dir <private-runtime-dir> --runtime-config <private-runtime.json> --java-home <jdk21> --output docs/reviews/implementation-d0-d2/process-quota-clock-checks.json
```

当前 Java 63/63、HTTP 服务 15/15、鉴权自审 15/15，[五个进程中断窗口](implementation-d0-d2/process-quota-clock-checks.json) 5/5；均使用本包最终 Jar，源码/数据摘要见检查点清单。分派提交后保持未知占用，其他持久化窗口恢复后沿用原执行/产物，不重复结算。本包未改前端，UI/TS 保留上一检查点结果，未冒称当前制品复跑。A07/A02 完成不代表 D0–D2 整体完成；A01、A03–A06、A08–A11 仍需后续工作包。本轮安全停点供父端复核，再衔接 A08/A01。

### A08＋A01 定向实现与证据

先复现三项实际缺口：旧前端在服务端保存消息后丢回执、刷新即丢失提交恢复入口，见 [Playwright 原失败](implementation-d0-d2/submission-before-fix.json)；仅有 marker 的启动检查错误放行缺迁移历史，见[门禁失败](implementation-d0-d2/readiness-before-fix.json)；同发布版本的第二次草稿保存覆盖第一标签内容，见[MySQL 草稿覆盖失败](implementation-d0-d2/draft-cas-before-fix.json)。

客户端 `CommandJournal` 在 fetch 前持久保存一次逻辑提交的命名空间、键、消息 ID、固定前提和内容摘要。未知结果只查询原键，查不到回执时按原键/内容重试；网络、5xx、非权威 not_found 不被当成未执行证明。刷新、切换身份/环境不会自动发送。身份切换清除 sessionStorage 中的正文、草稿和文件引用，仅留下原命令引用；切回后先取得服务端 subjectId，再显式核查。若正文已清除且回执不存在，要求重新填写相同内容并验证摘要，仍沿用原键。授权查询失败保留原引用但清除正文，不能因撤权而忘记未知提交身份。身份切走又切回的 ABA 响应由代次检查拒绝。

附件只保留名称/媒体类型/摘要和原键，不保存文件字节；未到达时须重新选择原文件。环境创建的三步流程保存已确认的阶段，不因首步丢回执而重建已存在模型。取消未发送草稿和已发但未知的提交明确区分；未知提交不能通过换参数/换键替代。

改名与配置编辑在 CAS 冲突时保留输入，并提供显式载入当前基础后再保存；审批冲突载入当前决定，不能自动改成另一批准。配置列表新增 draftHash，PATCH 草稿必填 expectedDraftHash，事务内以发布 revision 和当前草稿规范哈希共同比较；OpenAPI、响应 Schema、已有 HTTP 调用与 CI 必需检查同步更新。未新增产品页面。

浏览器初轮发现选择当前身份仍清空已验证身份，修复后新增 7 项恢复/双标签验证通过；完整 UI 又复现后台刷新覆盖明确环境选择，[trace 摘要](implementation-d0-d2/selection-refresh-before-fix.json)中选择值与实际提交值不同。改为函数式状态更新保留仍有效的当前选择，另加确定性延迟响应测试。失败报告保留，[首次回归](implementation-d0-d2/submission-first-regression.json)、[选择器歧义](implementation-d0-d2/submission-selector-regression.json)、[完整 UI 首轮](implementation-d0-d2/ui-submission-first-full-regression.json)分别记录实际错误与用例问题，不掩盖失败。

`SchemaReadiness.platform()` 在 Spring 启动阶段用运行账号校验原 Flyway 表的精确 V1–V5 成功记录和 SQL checksum，缺失/失败/未来版本均拒绝启动；不持有迁移凭据，不自动 migrate/repair。4 项 MySQL 测试使用获准平台库内唯一 a01_* 表和独立历史：新测试历史明确 baseline 0，不作用于原历史；中断仅终止测试直接创建并核对 marker PID 的 Java 子进程。DDL 已落库而 V2 未记成功时不就绪，原 Flyway 表保留 V1–V5。测试表/历史保留，原库不清空。

本地原始空库链仍属 A01，不能用以上前缀实验代替。额外实例、仅回环端口、两合成库和三分权账号的[审批方案](implementation-d0-d2/a01-empty-instance-proposal.json)已提交；未收到明确批准，未创建或执行。该项不是模型、真实资源或远端 CI 阻塞。

实际命令：仓库外 verify-submission-final.py 注入既有获准账号，执行 Maven 3.9.11 `clean verify -B -ntp`；Java [69 项结果](implementation-d0-d2/java-submission-readiness-tests.json)无失败/跳过。`npm --prefix frontend run test:submissions` 9/9、`test:contracts` 10/10，见[逐项结果](implementation-d0-d2/typescript-submission-readiness-tests.json)。`npm --prefix frontend run build` 及 Provider/API 生成一致性检查通过。UI 与 HTTP 最终同制品记录见检查点；历史五窗口中断结果仍属 A07/A02，本包未据此声称新制品再跑五窗口。

最终同制品结果：[UI 13/13](implementation-d0-d2/ui-submission-readiness-checks.json)、[HTTP 15/15](implementation-d0-d2/service-submission-readiness-checks.json)、[鉴权自审 15/15](implementation-d0-d2/hardening-submission-readiness-checks.json)。最后一项输入时序失败保留在 [submission-new-draft-race.json](implementation-d0-d2/submission-new-draft-race.json)：现在保存中固定输入，未决提交正文仍在时先核查再新建；身份切换后正文清除的场景允许重新填写相同内容并核对摘要。8 项新增 E2E 包含未到达/回执丢失、取消和新提交、身份/环境切换、分步配置恢复、双标签名称/草稿/审批及晚刷新选择保护；审批用例验证仅一个 Binding、一个接纳事件及总账只增加 1。

原 Flyway 历史仍为 V1–V5 全成功；16 个唯一前缀测试历史保留供复核，没有存活迁移子进程，见[迁移状态](implementation-d0-d2/migration-readiness-state.json)。最终未关闭 Binding、待处理 outbox、活跃槽及预占均为 0，见[数据库状态](implementation-d0-d2/database-submission-readiness-state.json)。[制品核对](implementation-d0-d2/submission-readiness-artifact.json)确认 46 个平台类和 Provider 合同 Jar 与已测构建一致，打包/实际提供的静态文件与当前 frontend/dist 完全匹配。

复核入口：`frontend/src/shared/command-journal.ts` 的 execute/guarded 是原回执查询、原键重试及重新鉴权失败处理；command 在异步计算摘要前锁定本次准备，保留 fixed 参数。`SchemaReadiness.verify` 校验完整成功版本序列，再运行 Flyway validateWithResult；SyntheticBoundary 在启动时调用 platform。关键顺序为：

```text
客户端：确认当前服务端身份/身份代次 → 已尝试则查原 kind/key 回执 →
        仍有权限且未查到则持久保存原 attempted 标记 → 原键/原内容发送。
就绪门禁：目标 V5 → 原历史版本精确为 1,2,3,4,5 且均 SUCCESS →
          Flyway 校验与当前 SQL checksum 一致 → 才允许启动。
```

本轮 A08 完成，A01 仅剩原始完整空库链；隔离实例授权尚未到达，不能执行依赖它的动作。其他 A 包与远端 CI 均未扩展，D0–D2 不宣称整体退出。

### A04 并发唯一性、事件与终态一致性

先在实际 MySQL 8.4.13 上复现 2/2 失败，见[修复前结果](implementation-d0-d2/concurrency-before-fix.json)：event 插入被严格 SQL 拒绝时，计数已独立提交从 1 变为 2；同一终态事件改内容重放在 Binding 关闭后被静默忽略。修复仅涉及 Store.event 和 ExecutionWorker.finish：事件计数与插入自身加入同一事务；先比较已存终态事件的规范哈希，再处理已关闭 Binding。原终态哈希域未改变，旧记录不重写；冻结账户键、周期、准入、客户端恢复和迁移门禁均保留。

原有约束继续生效，没有新迁移或索引改写：

```sql
UNIQUE KEY uq_binding_open(tenant_id,node_run_id,open_guard)
UNIQUE KEY uq_conversation_execution(tenant_id,conversation_id,open_guard)
UNIQUE KEY uq_command(tenant_id,subject_id,kind,idempotency_key)
UNIQUE KEY uq_message(tenant_id,conversation_id,client_message_id)
```

open_guard 是未关闭时为 1、关闭后为 NULL 的生成列；Binding 约束测试用两个原始事务同时插入同一 NodeRun，明确检验数据库拒绝第二个拥有者，不靠应用先查后写。探针从未批准/入队/接纳，确认 outbox 和 Provider 行都不存在后安全关闭，关闭历史保留，新 Binding 可创建；这不是对实际在执行任务的强制关闭。

其余并发检查调用实际业务方法，首个事务完成写入但尚未提交时保持屏障，第二个独立事务已经开始、autocommit=false 并尝试守卫。连接 ID 不同，第二个调用不能提前完成；随后放行提交或回滚。证据见[9 组连接和提交窗口](implementation-d0-d2/concurrency-overlap-proofs.json)，不是仅在第一个提交后再发第二次调用。屏障和观察 JdbcTemplate 只存在于测试代码，没有生产 HTTP failpoint。

| 检查组 | 实际断言 |
| --- | --- |
| 同键同内容 / 同键异内容 | 只提交一条消息和一个命令回执；等内容回原 commandId，异内容明确 IDEMPOTENCY_CONFLICT |
| 同消息 ID、不同命令键 | 等文字只保存一条消息/一次输入修订；异文字 CLIENT_MESSAGE_CONFLICT，不覆盖原文 |
| 不同消息、同基础修订 | 第一条保存，第二条 REVISION_CONFLICT，不能自动改基础版本 |
| 首个事务回滚、第二个原键重试 | 未提交消息/事件/回执均不可见；回滚不占序号或键，第二个事务提交一次，同键再读原回执 |
| 重复终态同时交付 | 一条终态事件、一个产物，冻结账户对各消费 1、预占 0，槽位只释放一次 |
| 并发事件、迟到提交与快照 | 未提交时仍只见原水位；快照与提交串行一致；分页 W+1/W+2 连续，按原快照游标续接不漏不重 |
| 插入失败与关闭后冲突 | SQL 拒绝不推进水位，重试复用正确序号；关闭后同事件异内容明确 PROVIDER_EVENT_CONFLICT，不改既有结果 |

新增用例在 [Java 80 项完整明细](implementation-d0-d2/java-concurrency-tests.json)，类为 ConcurrentTransactionsTest（9）与 EventTerminalConsistencyTest（2）。全量 `clean verify` 80/80，无失败/跳过；CI 汇总门槛要求这两类存在。既有账户/周期和迁移就绪检查均复验通过。这里验证当前粗粒度租户守卫的正确性，不宣称吞吐/细粒度锁性能，也不替代 A05 的多消费者/死锁/COMMIT 网络故障或真实 Provider CP。

首轮失败探针仅有创建事件、没有消息/附件/Binding；保留失败证据后，对其唯一未产生事件的计数推进做条件回滚，未删除事件或其他数据，见[探针回滚记录](implementation-d0-d2/concurrency-prefixed-failure-fixture.json)。

首次最终回归出现 HTTP 14/15、UI 12/13，[HTTP 原记录](implementation-d0-d2/concurrency-first-http-regression.json)和[UI 原记录](implementation-d0-d2/concurrency-first-ui-regression.json)保留。诊断为 Alice 当日合成额度已真实消费 100/100，准入正确排队而不是执行；没有重置消费或修改 A02 账本逻辑。先通过正常停止接口关闭两项失败检查留下的、从未接纳/预占的计划，见[关闭证据](implementation-d0-d2/concurrency-exhausted-quota-fixtures.json)。随后只在测试夹具中，经受审计的现有管理员配额 API 将 Alice 合成额度 100→200，新测试环境采用同一上限；消费与预占值逐项核对未改变，槽位上限保持 1。没有新增数据库账号/权限、真实资源或费用；产品默认额度没有改动。

修正夹具前置条件后，[HTTP 15/15](implementation-d0-d2/service-concurrency-checks.json)、[鉴权自审 15/15](implementation-d0-d2/hardening-concurrency-checks.json)通过。客户端合同 10/10、恢复逻辑 9/9 的本轮结果见[TS 证据](implementation-d0-d2/typescript-concurrency-tests.json)。最终 UI、五窗口中断和静止状态见最终检查点；不把首轮失败或历史制品结果冒称最终通过。

最终同一制品已完成 [UI 13/13](implementation-d0-d2/ui-concurrency-checks.json)、[五窗口中断 5/5](implementation-d0-d2/process-concurrency-checks.json)，HTTP 实际 405 个响应通过 Schema 校验。制品 SHA-256 为 `77adbdc14fe9868f0dc3f9dc721bd5192a911e6ff27e80ba9373ca0d87b62b0d`；[制品证据](implementation-d0-d2/concurrency-artifact.json)核对 46 个平台类和 Provider 合同 Jar 与已测构建一致，实际静态资源与 frontend/dist 完全一致。

[最终数据库状态](implementation-d0-d2/database-concurrency-state.json)中，未关闭 Binding、待处理 outbox、活跃槽、预占、事件计数不匹配及序号缺口均为 0；原迁移 V5 全成功，没有存活迁移子进程。Alice 当日总账/模型账各保留消费 114、预占 0，上限为测试配置 200，没有归零旧账。源文件摘要共 99 份，A02 账户/V5、A08 客户端和 A01 门禁 6 个关键文件与前一检查点完全一致。

完整测试名、命令、首次失败、重叠窗口与剩余边界见 [A04 工作包](implementation-d0-d2/concurrency-work-package.json)。本包已安全收口，不扩 A03/A05/A06/A09–A11；A01 新空实例仍未批准或创建，远端 CI 未运行，D0–D2 不宣称整体退出。


### A09/A03 与并行 A05/A06 的统一集成

本节为当前检查点，上述 A04 及更早的缺口表和数字保留其当时含义。代码仍在 `work`，HEAD 为 `2ae5c516c04efcaefb2d0acbc659737effb3f1ab`；未提交/推送。主任务负责核心与迁移；五个内部子 agent 分别准备队列故障、准入竞争、Provider、ExecutionSpec、接管/授权模块。数据库和浏览器验证串行执行，子 agent 自报没有直接计入总验收。完整范围、首次失败与最终证据见 [工作包](implementation-d0-d2/context-port-work-package.json)。

**A09：理解与固定上下文。** 先复现“计划没有清单”的失败，再以 V6 保存 `understanding_runs` 和 `context_manifests`。独立理解用途为 understanding，顶层和运行工具集均为空、modelCalls=0；规则仅处理既有有界 CSV，推断材料角色、唯一币种/状态及业务日历范围，缺项成为追问，建议仍由用户确认。调查清单固定消息摘要、附件 ID/摘要、输入修订、不可变配置引用/摘要、授权引用/修订、用途和 CSV/规则代码摘要；原消息正文没有复制进 Provider 清单。计划与审批绑定 contextManifestRef/hash 和理解输出哈希。新增消息/附件、来源撤权、授权撤销均在后端使旧清单失效；等键理解回执重放也重查当前清单。审批前撤权会创建持久核查 outbox，不把停止中的未接纳 Binding 留成无队列孤儿。

原“确认检查范围”弹窗已接规则建议与缺项提示，没有新增工作流页面。管理列表的 100 条边界在累计合成数据下使同一问题难以定位，已在原列表增加参数化标题筛选，筛选仍保留原角色/环境限制；界面验收继续核对原问题而不是改找另一行。源码和迁移不删除历史数据。

**A03：运行链路实际消费统一端口。** `ExecutionWorker` 的依赖现为生成的 `ExecutionProvider`，提交、原键核查、关闭、取消、快照、事件和产物均经类型化 DTO；不调用 FixtureProvider 的 UUID/Map 编排钩子。后者仅为既有事务测试保留包内可见兼容层。替身实际实现 12 方法；输入续接、权限恢复/响应明确 `UNSUPPORTED`，不伪造可用能力。

T_DISPATCH 在原账户对/UTC 周期预占之后构建完整 ExecutionSpec；V7 同事务保存 spec、能力/额度原快照及三个哈希域（完整 spec、去 admission 内容、method/body 命令）。配置、来源、上下文、批准身份与期限均来自实际记录。固定单节点合成流程把 nodeRunId 同时作为本次 workflowRunId、环境作为 system 范围；这不冒充独立真实流程系统。额度引用精确含原 subject、账户键与周期；费用币种/估算未知，不用 CSV 业务币种冒充费用。

Provider 自己推进计算，读取快照/事件不启动计算。命令、事件水位、回执、执行阶段与拥有者、两份产物元数据持久保存。成功后端输出为 CSV＋结构化摘要 JSON，Worker 分别经产物端口取描述、核对绑定/来源/摘要并导入，用户仍只收到原 CSV 结果。Blob 根仅以不透明 scope 摘要进入清单，跨 scope 不能接纳、读取或取消别处的执行。

**执行中断。** 保留原五窗并新增 `provider_execution_started`：计算已发生、尚未登记写入意图/写 Blob 时终止本任务精确 PID。重启后 Provider 保留同回执、attempts=1、computing、outcome_unknown；三次原提交观察不重跑，已消费轮次和槽位继续保留。显式停止仅在同主机启动标识、PID/启动时间证明原本地进程已死亡且尚未进入 writing 时取得权威取消，终态事件/结算各一次。writing 未知保守保留，不宣称已有部分写入自动恢复。六窗均核对同一个不可变 Jar，未向共享或用户进程发信号。

**A05：两项真实失败后修复。** [首轮 6/8](implementation-d0-d2/a05-before-fix.json) 锁定旧 Worker 失去 lease 后仍预占，以及 ix_outbox 多范围/filesort 锁住全部候选。当前领取用生产 `CLAIM_SQL` 的 PRIMARY 有序扫描；最终发送状态必须以原 owner＋原 lease＋未到期条件更新，否则抛内部失权信号回滚整个 T_DISPATCH，再返回 false。额度不足重新排队也用同样条件。[最终 8/8](implementation-d0-d2/a05-final-context-port.json) 含两个消费者、最老项解锁后可领、领取回滚、真实 1205/1213 整段回滚、COMMIT 前断开、真实 COMMIT 后丢回执、未知预占核查。故障仅作用于自己的 JDBC 物理连接，不冒充 TLS/代理报文级验收；PRIMARY 会扫描租户历史，不宣称吞吐结论。

**A06：最终 13/13。** [真实连接/COMMIT 屏障](implementation-d0-d2/a06-final-context-port.json) 交错配置/依赖发布、撤权、降限、停止与出队/首次接纳，并验证等锁期间到期、接纳后撤权阻断工具。首次集成的三项断言仍要求旧异常；新发送权门禁安全返回 false。测试只在确认 owner/lease 已失效时接受 false，并逐域核对没有票据、预占、spec、事件或执行写入；首次接纳继续严格验证错误码。保留 [首轮失败](implementation-d0-d2/java-context-port-first-full.json)，没有放宽产品限额或削弱无副作用断言。

| 最终验证 | 实际结果与证据 |
| --- | --- |
| Java clean verify | [121/121、0 跳过](implementation-d0-d2/java-context-port-final.json)；明确排除 AuthorizationMatrixTest、HandoverPackageTest、HandoverServiceTest，不能称全项目全量通过 |
| HTTP 与回包 | [服务 15/15](implementation-d0-d2/service-context-port-checks.json)、[上下文 3/3](implementation-d0-d2/service-context-checks.json)、[原鉴权回归 15/15](implementation-d0-d2/hardening-context-port-checks.json)，共 496 个回包通过 Schema |
| 正式界面 | [13/13](implementation-d0-d2/ui-context-port-checks.json)：理解 2、原闭环 3、提交恢复 8；本次不包含 recovery-and-content 的 2 项，旧结果不计入当前数字 |
| 真进程中断 | [6/6](implementation-d0-d2/process-context-port-checks.json)，含 executing 后未知保留与显式权威取消 |
| TS / 静态 | [合同 10＋恢复 9](implementation-d0-d2/typescript-context-port-tests.json)；485 项静态一致性和 20 份原 UTF-8 摘要通过；不是实机功能证明 |
| 制品 | [Jar、74 个平台类、合同 Jar、服务静态文件](implementation-d0-d2/context-port-artifact.json) 与本轮构建相符；编译/字节一致性不代表 A10/A11 业务已验收 |

最终 Jar SHA-256：`84869024bf8ce5fa7bded1ce2739b96dc25e57576e7905ed4fd4e169400f7b2e`。重跑命令和各测试名见工作包及证据 JSON；启动/检查方法见 [backend/README](../../backend/README.md)。生成合同仍为 12 方法/76 定义，OpenAPI 36 路径/38 操作，340 个响应校验器。A02 QuotaLedger/V5、A04 Store 和 A08 API 恢复核心摘要未变。

首轮 Context 测试有数值类型断言和收尾夹具错误；首轮 HTTP/界面有有界列表定位及等待业务状态的假设问题，均保留修复前结果。已知撤权 pending 夹具通过正常受审计的 revoke 重排后取得永久未接纳证明而关闭，见[收尾记录](implementation-d0-d2/context-leftover-fixture-recovery.json)，未删除数据、没有消费轮次。[最终数据库快照](implementation-d0-d2/database-context-port-state.json) 的开放 Binding、待处理 outbox、活跃槽、预占、执行未知、事件水位不匹配及序号缺口均为 0；原迁移 V1–V7 成功，只有已获准的原 MySQL 容器，没有迁移子进程。

**该检查点的剩余与审批阻塞（已由后续 A10/A11 更新）。** A10 的不可变 DTO、导出服务和 9 项服务测试源码已保存，主工程可编译；纯 DTO 的独立 9 项早期通过不代表导出服务已验收。导出 API/Schema/UI 未接入，A11 的 7 项新矩阵未运行。包含 A10/A11 的集成命令和一次附父端扩展证据的重试均被自动审批拒绝；理由为父端转述不能代替直接用户授权。已停止该动作并请求直接确认，没有换入口执行。精确记录见[审批阻塞](implementation-d0-d2/integration-approval-blocks.json)。A01 新空实例仍待单独授权；本轮未创建新实例/库/账号。B01 只有远端 hosted CI 未运行；真实 Provider/身份/资源/独立 Runner 属于 D3/D4。当前是安全复核停点，不代表 D0–D2 整体退出。


### A10/A11 接管包与授权矩阵最终检查点

用户直接允许继续导出接口/UI和现有合成环境的授权隔离测试后，先核对 `work`/HEAD、旧不可变 Jar/PID、数据库零占用，以及没有 A10/A11 Surefire 结果，确认被拒操作此前未执行，见[恢复起点](implementation-d0-d2/handover-start-state.json)。随后按新授权重试并实际执行；没有换入口绕过自动审批。旧拒绝及直接授权解除记录保留在[审批记录](implementation-d0-d2/integration-approval-blocks.json)。没有新建 MySQL 实例、库、账号或权限。

**接口与导出内容。** 原对话页新增“导出接管包”，调用 `GET /api/conversations/{id}/handover`，返回 `{package, packageSha256}`。[HandoverService](../../backend/platform-server/src/main/java/io/aitest/workbench/handover/HandoverService.java) 在单一 Store 事务内重查 owner 和当前环境授权，再对来源、产物分别鉴权并核验哈希。admin 的元数据权限、reviewer 的消息正文租约均不赋予导出权。导出唯一业务写入是 `handover.export` 审计，不更新消息、节点、Binding、事件水位、预占、消费或槽位。

```java
var c = scope.ownConversation(actor, conversation);
// 同一业务守卫内，逐附件/产物授权并核对持久证据。
String hash = value.sha256();
db.audit(actor.id(), "handover.export", conversation,
    Map.of("packageSha256", hash, "schemaVersion", HandoverPackage.VERSION,
           "eventWatermark", number(c.get("event_sequence")),
           "purpose", "fixed_csv_handover"));
return Map.of("package", value, "packageSha256", hash);
```

包的固定投影含 CSV 范围与原审批哈希/决定/身份、Binding 和接纳/停止事实、上下文及输入摘要、已验证算法/产物、用户验证、原冻结账户对/周期和未决项。它不含消息正文、附件字节、原始配置/spec、凭据、Blob 私有路径或任意 Provider URL。包哈希、原完整操作哈希和固定范围投影哈希各有不同含义，未将投影哈希冒充批准哈希。责任字段保持未分派，导出不是自动接管或解决确认。

`stopRequested` 只取平台停止标记，`providerCancelRequested` 只取实际 V7 日志（旧历史为空），`closureProofRef` 只取经过关联核验的持久关闭/取消回执。自然完成不造取消证明；原 snapshot revision/hash、providerEventWatermark 和 providerResumeCursor 直接来自持久快照，不重新生成游标。所有开放 Binding 保守保留 ownership=unknown、原账户对和周期。缺少旧摘要锚时数值为空并列入未决项；新输入后包标注旧 context 为 stale 且不附旧产物；来源撤权时拒绝整包导出。

**先失败再修复的完整性检查。** 首次授权运行 25 项为 17 通过、1 失败、7 错误：SQL `state` 列歧义和“目标必在前100条”的夹具假设已修正，原证据在[首次运行](implementation-d0-d2/handover-authorized-baseline.json)。随后[4 项完整性失败](implementation-d0-d2/handover-integrity-before-fix.json)真实复现，再修复为：平台摘要必须与 V7 摘要文件的描述/字节/摘要相符；context 的 providedScope 七字段、历史 input/auth/env、消息/附件所属会话及摘要、不可变配置引用集合必须与原审批一致。还复现并修复[可变 Blob/元数据一起改写但持久 ArtifactReady 不一致](implementation-d0-d2/handover-artifact-ready-before-fix.json)；导出现在核对原发布事件，不只核对可变副本。上述用例均已转绿。

**客户端隔离。** [api.ts](../../frontend/src/shared/api.ts) 在导出前后及下载前检查身份代次、当前会话/水位和 JCS/SHA，不缓存包。差异 CSV 同样检查身份代次和当前视图：补测[界面已观察来源撤权后的 CSV 晚响应](implementation-d0-d2/ui-csv-revocation-before-fix.json)复现真实下载漏洞，修复后通过。身份切走再返回仍使旧请求失效，不以当前 alias 相同替代代次检查。

```typescript
if (generation !== subjectGeneration || activeSubject !== subject)
  throw new ApiError(409, 'STALE_IDENTITY_RESPONSE');
if (!currentScope()) throw new ApiError(409, 'STALE_DOWNLOAD_SCOPE');
// 接管包还验证 JCS/SHA，成功后才创建一次性下载 Blob URL。
```

首轮浏览器 22/24 的两个失败是测试等待已丢弃 fetch 的 `requestfinished` 超时；改为确认原授权响应到达后，继续严格断言无下载/无旧缓存，定向 2/2 转绿。之后发现旧用例假定 bob 没有任何环境，与新矩阵合法授予其他环境冲突；现先实际显示原问题正文，再切身份，并验证其他环境权限不能打开原问题、文件、事件或接管包。修正夹具没有放宽产品授权；两轮失败和 trace 均保留。

**最终验收。** 同一不可变 Jar SHA-256 为 `1e1e87e20a4a5dbf1b838b06d0577659f4ce599ce02b63eb2ae416da7de2abb7`。

| 验证 | 实际结果与范围 |
| --- | --- |
| Java 全量 clean verify | [156/156，零失败/跳过](implementation-d0-d2/java-handover-final.json)，没有排除 A10/A11；HandoverPackageTest 11、HandoverServiceTest 17、AuthorizationMatrixTest 7 |
| 真实 HTTP | [原服务15](implementation-d0-d2/service-handover-checks.json)＋[上下文3](implementation-d0-d2/context-handover-checks.json)＋[原鉴权15](implementation-d0-d2/hardening-handover-checks.json)＋[新授权矩阵9](implementation-d0-d2/http-authorization-checks.json)，42/42；953 个 JSON 回包通过 Schema |
| 正式界面 | [25/25](implementation-d0-d2/ui-handover-checks.json)，包含接管包/CSV晚响应、身份ABA、同身份已知撤权、正文租约、SSE和原提交恢复；无跳过或 flaky |
| 真进程中断 | [6/6](implementation-d0-d2/process-handover-checks.json)，原提交/单次结算/未知占用和显式关闭保持正确 |
| 合同与构建 | [TS 10＋9＋9](implementation-d0-d2/typescript-handover-tests.json)，Provider 12 方法/76 定义，Handover 15 record/16 定义；生成器 check 无漂移，OpenAPI 37路径/39操作、349响应校验器 |
| 运行制品 | [74 个平台类、合同 Jar 和实际静态资源](implementation-d0-d2/handover-artifact.json)与最终构建一致；不作真实 Provider 或生产部署证明 |

新 HTTP 矩阵逐请求记录主体、操作、状态和 Schema。负例先有同路由正例，覆盖同环境 peer、有效其他环境、他会话材料关联、正文资格/租约到期及撤回、owner撤权终止原SSE、下载/验证/旧回执/清单/接管包、新输入、来源撤权和 W→W+1 续接。没有原附件下载或独立正文 lease-revoke 路由，报告明确排除，不拿不存在路由的404作鉴权证明。

具体服务测试包括 `completedFixedCsvFlowExportsAuthorizedFactsHashesVerificationAndAudit`、`unknownSubmissionAcrossUtcRolloverExportsFrozenOldPairWithoutReleaseOrNewExecution`、`stopBeforeAcceptanceExportsActualPermanentProofWithoutTouchingAnotherReservation`、`acceptedStopExportsPersistedCancellationProofAndOriginalProviderResumePosition`、`legacyWithoutProviderSummaryAnchorExportsNullNumbersAndNoInventedRunOrClosureProof` 及前述5个完整性拒绝用例。启动/检查命令见[运行说明](../../backend/README.md)，证据与首次失败索引见[工作包](implementation-d0-d2/handover-work-package.json)。

[最终数据库状态](implementation-d0-d2/database-handover-state.json)：MySQL 8.4.13、平台 V7/Runner V1；开放 Binding、待办、活跃槽、预占、执行未知、事件计数不匹配和序号缺口均为0，导出审计实际存在。仅保留原获准容器，原金融历史和业务数据未删除，没有迁移子进程。

**A10/A11 已完成，不再等待该项授权。** 剩余 A 类只有 A01 原始空库全链：新空实例尚未获批，未创建；B01 远端 CI/job 准备未运行，未擅自推送。D3/D4 的真实身份、资源、模型/适配器和独立 Runner 不混入本阶段阻塞。当前按要求安全收口，不把该结果写成 D0–D2 整体退出或真实接入完成。


### 用户新增部署复杂度约束（上轮仅文档记录，后续已续接实施）

已将“普通使用者可简单搭建”写入[交付计划 D4-DEPLOY](../development/IMPLEMENTATION-PLAN.md#简单搭建交付验收d4-deploy用户首装待验收)、步骤12和[内部运行说明](../../backend/README.md)，并在退出清单新增一个 `planned_not_run` 的 D4 验收项（当前共28条；原27条及既有实测结论未改）。目标为预构建制品、单一主路线/操作入口、少量必填输入、自动预检及获准的最小权限初始化；内部测试账号和多实例不成为用户逐项手工 SQL/配目录的要求。

建议必填预算不超过3个独立值，属于待冻结方案目标，并非用户指定数字或已测结果；当前合成运行2个常驻单元，后续带独立Runner为3个运行角色。目标主机/OS、交付格式、数据库管理方式、首位管理员身份及准确配置数量尚待确认。首装、重启/停止、升级、初始化失败继续和备份恢复均列出可验证结果及证据要求；不以 root 运行应用或绕过安全检查来降低复杂度。

本次没有新增安装实现、运行安装/数据库实验、软件安装、实例/账号/权限，也未提交推送或部署。A01新空实例和远端CI仍保留原未运行状态；先前156/25/42/6等结果是既有合成闭环证据，不能证明该用户首装要求已完成。


### 简单搭建交付准备：统一入口与本地验证（2026-10-27）

用户要求继续项目并保持搭建简单，本批在原获准库和账号范围实现，未创建 A01 新实例。沿用原 `scripts/synthetic_runtime.py`，保留原4动作兼容，新增 `template/doctor/up/status`；`up` 显式接收两库独立迁移配置后自动协调 Flyway 与启动，日常 `up/restart` 不重复初始化。仅管理平台 JVM，不接管原 MySQL 生命周期；运行/迁移身份不能互换，Web 不获得迁移或 Runner 密码。配置模板、错误处理和启动/停止方法见[统一入口说明](../../backend/README.md#统一入口与健康检查)。

`RuntimeProbe` 从完整 Jar 启动只读 Java CLI，不加载 Spring/Worker；数据库连接与查询有界，核验 MySQL8.4/InnoDB/strict/UTC/READ-COMMITTED、合成 marker 和精确 V1–V7 历史/校验和。预检不建目录、改权限、执行迁移或写审计。Runner V1 迁移已随 Jar 打包，自动迁移不依赖源码树。

启动固定制品、记录 PID/启动ticks/boot代次，目录操作用锁互斥；停止用 pidfd 再校验身份，只发送 SIGTERM。旧进程 `/proc/fd` 读取实际被内核拒绝，没有扩大权限或绕过；新服务在真实 WebServer `ApplicationReadyEvent` 后向私有目录原子写唯一nonce/PID/实际端口回执，入口关联原进程并检查 active 合成会话，避免误认别的端口服务。未知启动/停止保留原记录，不另起进程、不重放执行。

| 验证 | 最终结果 | 证据 |
| --- | --- | --- |
| Java完整 `clean verify` | 182/182，零跳过；含 RuntimeProbe16、RuntimeReadiness10，原业务/权限/事务/SDK回归均通过 | [Java明细](implementation-d0-d2/setup-java-final.json) |
| 脚本与本机自有socket安全 | 28/28；配置权限/重复键、角色隔离、环境注入、PID/pidfd、并发锁、迁移失败、nonce与TIME_WAIT | [脚本明细](implementation-d0-d2/setup-script-tests.json) |
| 实际统一入口生命周期 | 11/11；模板、只读doctor、up原PID、运行中拒绝迁移、缺配置/权限/占端口/DB拒绝、保留Blob停启/重启 | [实际停启](implementation-d0-d2/setup-lifecycle-checks.json) |
| 原库迁移协调 | 平台V7/RunnerV1，两次新迁移数均0；原历史7/1条保留 | [工作包](implementation-d0-d2/setup-work-package.json) |
| HTTP与正式界面 | HTTP15＋3＋15＋9=42/42，963JSON验Schema；Playwright25/25，0跳过/重试 | [服务](implementation-d0-d2/setup-service-checks.json) · [上下文](implementation-d0-d2/setup-context-checks.json) · [自审](implementation-d0-d2/setup-hardening-checks.json) · [授权](implementation-d0-d2/setup-authorization-checks.json) · [界面](implementation-d0-d2/setup-ui-checks.json) |
| 原执行进程中断 | 6/6，同一固定Java制品；未知占用保留，权威核查后关闭，不重启原执行或重复结算 | [六窗口](implementation-d0-d2/setup-process-checks.json) |

实际命令包括 `python -m unittest discover -s scripts/tests -v`、配置获准测试环境后的 `mvn -f backend/pom.xml clean verify -B -ntp`，以及统一入口 `doctor/up/status/stop/restart`、`synthetic_setup_check.py`、4套HTTP检查（`--validate-api`）、`synthetic_crash_check.py` 和 `npm --prefix frontend run test:e2e`。完整参数见运行说明，私有配置只传文件路径，不把秘密写进命令或证据。CI配置已增加对应检查；没有推送，远端未执行。

真实失败及修正均保留：首次完整Java执行时旧服务仍在扫描同库，3项旧并发测试失败；精确优雅停止后独占构建182项通过，不改核心事务。首轮脚本有UTC样例/沙箱socket错误，扩展阶段假Jar缺新类导致4个fixture失败，均修正夹具。实际停启首次9过1败：TIME_WAIT被误当活跃端口；修正SO_REUSEADDR、仍拒绝真正活跃监听，最终11项通过。HTTP旧断言未带账户周期，跨日保留历史时失败；现在依据导出中原冻结账户对/周期，严格断言settled、恰好两账户各消费一次、其余账户/周期完全不变。中间状态断言误写consumed的失败也保留。详见工作包的失败证据索引，未降低业务限额或清零历史。

最终固定Jar SHA-256为 `60d85b5844a67850271784133387bbc7fa8b6ccaca62f5e721ac55bf98e01654`；80个当前平台class及其余资源均与Jar一致，运行私有副本及3份前端资源实际响应摘要一致。[制品证据](implementation-d0-d2/setup-artifact.json)、[源码检查点](implementation-d0-d2/checkpoint.json)记录130个源文件哈希；7个核心事务/配额/Provider/上下文/迁移门禁文件与上一检查点一致。

[最终只读数据库检查](implementation-d0-d2/setup-database-state.json)为MySQL8.4.13，平台7/Runner1；开放Binding、未知执行、待办、活跃槽、预占、事件计数差异/序号缺口、失败迁移均0，只有原 `workbench-mysql-d0`。当前本地合成服务就绪，所有测试进程已结束，保留原数据和私有运行证据。

**剩余与下一步**：完整空库链A01仍待原新实例授权；远端CI没有推送故未运行。完整用户安装路线、目标主机/打包形式、真实首位管理员、精确输入数量未冻结。当前6个运行键及独立URL参数如实计数，未满足或宣称已冻结建议3项；Linux/Java21/Python3.12和已有MySQL/TLS/账号是内部验证前提。下一项可推进交付制品与配置/升级/备份的安全准备，目标环境确定后再验普通使用者首装；不能通过新建未批资源或root/TLS放宽来补证。此批完成交付准备，不等同整体项目或真实部署完成。


### Windows 发行目录、单一入口与离线验收（2026-10-27）

用户明确选择Windows电脑，当前executor是Linux。本批沿现有Java/MySQL/内置前端收敛，生成 `backend/target/distribution/ai-test-workbench-0.1.0-SNAPSHOT-synthetic.zip`（26,312,313字节），入口 `Workbench.cmd` 直接启动Jar内 `RuntimeLauncher`，不增加Python/PowerShell/DockerDesktop或后台基础设施。内嵌合成身份/执行器仍明确标为预览，真实登录/ACP/业务资源未接入。

主菜单可达配置已有环境、只读预检、启动、状态、停止、显式迁移已有两库并启动。向导6个提示，DB/UI端口2个有缺省，另外4个为现有PKCS12路径与3个已有密码；配置数量如实计数，未宣称达到建议3项。只把配置存入当前用户私有目录，不创建MySQL实例/库/账号、证书或新的持久身份。Windows用户前提是已有Java21、MySQL8.4和原获准TLS/分权账号；维护者打包才需要Maven/Node/Python。详见[Windows运行说明](../../backend/README.md#windows-发行准备尚未实机验收)。

运行入口保留原PID/启动时间/Java/固定Jar摘要和每run nonce，启动意图结果未知时阻止新进程；重复up沿用原拥有者。停止只向原私有run写严格nonce/PID/action请求，服务关闭Spring后退出；不开放shutdown路由、不强杀PID。POSIX/Windows ACL策略与私有原子文件代码已实现，无法验证权限/身份时保守失败；真实NTFS继承、ProcessHandle参数可见性与正常双击仍需Windows实机验证。原Linux仅READY配置继续兼容owner0755目录，启用控制则要求新的严格私有run目录。

`release_bundle.py` 从固定白名单复制Jar、3占位模板、CMD、维护者工具、README/COMPATIBILITY/LICENSE并生成manifest/SHA256SUMS，共12文件；不复制整个源码工作区、运行配置、测试输出、数据库、Blob、日志或缓存。填写秘密或扩展的模板、缺Windows类/前端/迁移/JDBC的Jar均拒绝。清单包含实际版本、嵌套运行库摘要和观察到的源树未提交状态，不冒充已提交发布/CI证明。归档/目录校验拒绝额外/缺失/篡改、路径穿越/ADS/Windows保留名、链接/重复/大小写冲突和超限内容，读取/解压前对小文件限长；既有输出目录不覆盖。

| 实际验证 | 结果 | 证据 |
| --- | --- | --- |
| 新Java入口/权限/控制/原Probe与Readiness定向测试 | 77/77，0跳过；mock外部进程/HTTP/DB，实际私有临时文件，不是完整业务或Windows实测 | [Java明细](implementation-d0-d2/windows-offline-java-tests.json) |
| Python最终代码回归 | 60/60，0跳过：入口35、归档21、真实Jar类加载4；自有临时socket/无服务或数据库 | [Python明细](implementation-d0-d2/windows-offline-python-tests.json) |
| 中文/空格目录解包、任意cwd下Jar/独立校验工具 | 7/7：help、缺配置doctor/up、未运行status/stop、无控制台configure均不创建运行目录；未执行CMD/Windows | [解包冒烟](implementation-d0-d2/windows-unpacked-offline-smoke.json) |
| 干净构建和归档可复算 | 两次实际Maven clean package的Jar相同；两次独立组装ZIP相同；打包时-DskipTests，不拿它作测试通过 | [构建](implementation-d0-d2/windows-reproducible-build.json) · [制品/载荷/依赖](implementation-d0-d2/windows-release-artifact.json) |

自审修复两个实际入口问题：发行脚本原本硬编码源码target/config路径，解包后不可用；新增7个布局用例修复并独立通过35项。另确认Boot PropertiesLauncher会读cwd/loader.properties，能替换入口类；用仅打印固定字符串的私有测试类实际复现Launcher/Probe/Migrate三条旧命令形态，固定classpath配置位置与home后均不再执行替身，并纳入真实Jar回归。没有执行攻击性副作用或访问数据库，见[前后证据](implementation-d0-d2/windows-loader-override-checks.json)。启动前/子进程清理JVM及Loader环境覆盖，未知start fencing、严格JSON尾随/类型/nonce/metadata检查也已纳入测试。

核心命令为既有Maven的5类定向测试（RuntimeProbe/Readiness/Files/LifecycleControl/Launcher）、`JAVA_HOME=<existing-jdk21> python -m unittest discover -s scripts/tests -v`、两次 `mvn -o -f backend/pom.xml clean package -DskipTests -B -ntp`，以及 `release_bundle.py build/verify`。CI本地配置已调整为先构建Jar再跑发行测试，未推送，远端未运行。环境曾短暂显示starting，恢复后确认源文件/分支/日志保留，77项构建实际成功，没有盲重启服务。

候选Jar SHA-256：`b330bcba35e3677dd243c0b65a39c7d65ba86c5bc4528a9a3e6076c074e1d1b9`；ZIP SHA-256：`a71f61adb819d078c20ace7c5c3282766a7dcd96d0968fa235c23a9e55fe0fc7`。规范时间为构建可复算用途，不是发布日期；摘要验证完整性，不冒充签名。源码状态、限制、依赖和未运行项见工作包/清单。

**剩余最小条件**：目标OS已经决定，无需再问Linux/Docker。下一步确认获准Windows验收主机上是否已有Java21/MySQL8.4、受信TLS与分权账号；没有时安装/账号准备须单独明确授权，不能拿用户选择Windows当作许可。当前没有Windows executor，故双击、实机ACL/拥有者信息、原进程优雅停止、干净首装和非开发人员搭建均未验。升级/备份/恢复只在包内[兼容约束](../../backend/distribution/COMPATIBILITY.md)写明，不在现有数据上实验。A01新空实例仍待批，原完整服务回归仍绑定旧60d85b制品，本批不虚报新候选的真实部署或整体项目完成。


### Windows 最少首装操作准备：主路线与待批准对象（2026-10-27）

推荐主路线为应用/用户私有目录内便携Java21＋新私有MySQL8.4，不注册服务、改PATH/防火墙/全局权限或要求Docker。目标用户3动作：解压完整获准包、双击、确认首次私有实例范围；目标不手填DB密码。旧环境路径仍6提示/4必填且有管理员准备工作，仅作兼容，不再把它说成简单搭建完成。

实际新增：CMD `requirements` 在没有Java时可读；未核验portable候选只检测并拒绝执行。`setup`/`--json` 的只读检查器检查依赖/主机/数据目录和模板哈希，输出两库三分权账号/11动作/确认范围，恒定 `canInitialize=false`。SQL每条变更和INI都有拒绝守卫，初始化配置不引用尚不存在的TLS材料；未知初始化冻结不重跑。DPAPI代码通过系统静态PowerShell调用CurrentUser保护，秘密不入argv/env/日志，保留继承执行策略、不明文降级；本批未调用真实DPAPI或持久化凭据。

最终[Java定向检查](implementation-d0-d2/portable-offline-java-tests.json)103/103（含SetupInspector13、DPAPI13），[Python检查](implementation-d0-d2/portable-offline-python-tests.json)89/89（模板22、CMD静态6、归档22、真实Jar类加载4、原入口35），[准备包只读检查](implementation-d0-d2/portable-inspection-offline-smoke.json)3/3。未执行SQL、mysqld、PowerShell、Windows CMD或真实初始化；这些不是Windows/安全存储/权限实机证明。命令为此前7类目标Java测试、`JAVA_HOME=<existing-jdk21> python -m unittest discover -s scripts/tests -v`、不含clean的Maven package、release_bundle build/verify、实际Jar的setup只读输出。未反复跑无关业务回归。

[官方来源检查](implementation-d0-d2/portable-dependency-sources.json)确认MySQL源码默认动态MSVC、微软app-local存在但有许可/维护限制；不能承诺ZIP零前提。Windows精确包与SHA仍null/not_approved，api.github.com/dev.mysql.com元数据请求遇代理403、校验文本404后停止，未绕过。Temurin21.0.9+10/MySQL8.4.13只是Linux参考，不伪造Windows可下载文件或哈希。

新准备包在 `backend/target/portable-setup-preparation/`，v2固定17文件，未包含第三方运行时/数据库软件或初始化执行器；旧v1仍能校验、原a71f…ZIP完整保留。制品见[摘要](implementation-d0-d2/portable-preparation-artifact.json)，它是可审查准备，不是三步安装器。唯一需继续的边界是先核实确切官方包/摘要/许可，再由用户选择具体Windows机器和私有路径，分别批准获取软件、该私有实例/两业务库三账号/初始root轮换/TLS/DPAPI/回环端口；MSVC安装或app-local分发单列。现有数据和服务不在范围内。

恢复风险仍包括MySQL多DDL不能假定整批回滚、未知初始化不能清库重试、两库/Blob/秘密引用需同点恢复，以及DPAPI不能保证换用户/机器可恢复。备份位置与恢复保护材料须先定，不在本轮创建。初始化执行器尚未接线是明确剩余实现，不把“方案准备通过”当成全部余项都只是审批；需先有确切软件和Windows宿主事实，不能继续以假适配器替代其验收。


### GitHub 阶段交付收尾（2026-10-28）

本次按用户要求整理并提交当前实现。交付定位为可复核的合成环境阶段版本，功能尚未全部完成：真实身份/模型/执行后端与业务资源未接入；A01 完整空库安装链、Windows 实机首装/停启/权限及初始化执行器仍待完成，便携依赖尚未核定和获准下载。已有合成服务端闭环、正式前端、契约、迁移、接管包、回归证据和 Windows 发行准备随源码交付；不把只读向导或离线检查当作安装成功。

提交前检查覆盖工作区差异、历史只读档案、契约生成一致性、前端纯测试和源码凭据扫描；既有完整 MySQL/HTTP/界面/中断回归与新离线准备检查仍分别绑定上文制品，不声称在本次提交时重跑了所有数据库或 Windows 验收。构建包、依赖缓存、私有运行配置和数据库不入 Git；此前证据中的基线提交号及未提交状态保留为当时事实。远端 CI 是否运行及结果以 GitHub 实际状态为准。

本次收尾实际复跑：设计静态493项/历史SHA20项通过；Provider12方法与76定义、接管包15记录与16定义生成一致；API37路径/39操作/349响应校验器检查通过；前端契约10、提交恢复9、接管包9共28项通过，TypeScript/Vite构建通过；Python离线89项通过。621个Git候选文件扫描未发现3个已知私密值或常见令牌/私钥模式。暂存差异以CRLF兼容规则检查通过（Windows CMD及原CSV保留CRLF），未改动原始档案。未重跑完整数据库/浏览器/Windows验收。
