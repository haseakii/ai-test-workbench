# 接入、执行与 API 开发设计

状态：待评审。补充已有[服务 API](../design/SERVICE-EXECUTION.md)及[ExecutionProvider](../design/EXECUTION-PROVIDER.md)，不是新增业务实现。对外路径统一加 `/api`；协议和绑定细节留后台按需查看。

## 先配置，后获得使用资格

| 对象 | 管理位置与绑定 | 执行时使用的内容 |
| --- | --- | --- |
| 运行配置 | 运行配置列表：节点、受控身份、工作区、出口和隔离证据 | RuntimeManifest；不是测试/生产业务环境 |
| Agent 连接 | Agent 接入：总控或直连、执行制品、运行配置、认证引用 | Provider 连接修订、Agent ID、真实能力观察 |
| 模型预设 | Agent 接入下：从该连接观察中选模型，设置显示名 | connection + agent + canonicalModelId；请求模型与实际观察分开 |
| 系统 / 环境 | 系统设置内：说明、时区、部署标记、可选模型、流程、上下文策略及资源 | 固定环境和结构化资源清单；说明仅作配置背景 |
| 代码项目 | 资源目录：GitLab/通用 Git 或执行节点本地目录 | 经授权的 commit/不可变内容快照、文件哈希与来源 |
| MCP | 资源目录：端点/启动模板、认证、发现、工具及参数核准 | 本流程所需、当前获权工具的 schema 和策略；不透传全部工具 |
| Skill | 资源目录：受信来源、版本、哈希、参数及工具依赖 | 经审阅的方法说明；首批不允许上传任意脚本即执行 |
| 成员策略 | 用户与权限：环境、模型、流程、执行方式、额度例外 | 当前身份求交集；默认配置减少填写但不自动授予权限 |

“保存”“发现”“核准”“发布”“绑定”“成员获权”是不同事实。可用功能计算为：已发布流程所需能力 ∩ 环境允许 ∩ 成员授权 ∩ 执行器已验证能力 ∩ 当前资源策略 ∩ 当前额度。任一必需项缺失则返回原因；不能靠名称或模型自行判断。

发布当前对象默认不携带其他草稿。发布变更集须显式选择依赖，在单事务中校验并写全部版本；未选择的草稿保留。计划只依赖真正使用对象的语义哈希，名称/说明中属于展示的字段不使批准失效；作为执行背景的说明变更则需要重新准备。禁用、撤权和秘密吊销对新操作即时阻断，在途仍核查。

## 一次用户操作的服务调用

1. `GET /api/workbench/options` 返回当前允许选项和不可用原因；选择器不返回不可枚举对象。
2. `POST /api/conversations` 固定系统环境；`POST .../messages` 保存文本和已导入附件引用。HTTP 回执只代表保存。
3. 服务判定理解授权。若允许，创建 `purpose=understanding` 的独立 Node/Binding、上下文和额度预占；若仅收集，只返回系统回执。理解阶段不装配业务工具，不发送未授权附件正文。
4. 固定流程的类型化计划生成器把模型建议转换为候选计划：目标数据集、时区、时间区间、状态、单位、项目和允许操作。JSON Schema 校验只保证结构，还要由领域规则校验资源与语义；缺条件就追问。模型不能输出任意 SQL/工具后直接执行。
5. 固定 PreparationManifest、审批操作与期限；自动只读必须匹配已授予的 AutoExecutionGrant，本人确认必须有该操作审批权，否则分配负责人。耗时等待不循环调模型。
6. 批准后排队，出队时复查依赖、权限、额度和期限，原子预占后提交唯一 Provider。返回进度来自持久事件，用户切页不取消。
7. 只读取证和确定性核对完成后，平台校验证据及产物，将结果置为待验证；用户确认针对当前 resultRevision，后台同步同一标题和记录。

准备计划并不自动授权取证。代码快照可由管理员提前在获权流程中准备；若必须读取新代码/数据才能形成计划，先形成受限的发现操作并获得相应只读授权，或向用户追问。不能在正式批准前用“预检”读取全部业务资源。

## 用户与后台 API 补充

所有变更都带幂等键和对应 expectedRevision，身份服务端派生；命令超时查原回执，禁止前端通用拦截器自动重试所有 POST。

| API（加 `/api` 前缀） | 关键输入 / 输出 | 实现要求 |
| --- | --- | --- |
| GET /commands/{commandId} | 原命令 saved / pending / accepted / unknown / rejected 及允许下一步 | 校验原调用者范围；无法得到 commandId 时按原幂等键查询相同 kind 的命令，不新发执行 |
| POST /command-lookups | kind、原 idempotencyKey → 原回执或查无结果 | 无执行副作用；不可用查无结果作为 Provider 未接纳证明 |
| PATCH /conversations/{id}/title | title、expectedTitleRevision → 规范标题、来源和修订 | 自动候选不得覆盖手动名称，两个客户端竞争返回冲突并保留输入 |
| GET /admin/executions | 系统、状态、用户、时间、游标 → 元数据列表 | 同一 conversation / run；重试与换后端不作为通用列表按钮 |
| GET /admin/approvals | 待我处理、系统、期限、状态 → 固定操作摘要 | 批量选择不得绕过逐条权限/版本；首批逐条审批即可 |
| POST /admin/config-publications | 对象及基础修订清单、预览哈希 → 全部发布回执 | 服务器重算依赖；预览不是授权快照 |
| POST /admin/secrets | 类型、秘密值、目标引用 → 不透明 secretRef | 单独权限，TLS，禁止日志/回显；只支持替换/吊销，通用 GET 不取回明文 |
| POST /admin/agent-connections/{id}/discovery | 已保存配置修订 → discoveryId | 显式操作、审计、成本上限；不携带业务材料；真实连接需授权 |
| POST /admin/mcp-connections/{id}/discovery | 已保存端点修订 → schema 观察 | 新工具默认禁用；核准后才能被环境和流程引用 |
| POST /attachments/import + POST /attachments/{id}/complete | 会话、类型、大小、上传引用 → 扫描/解析状态 | 文件解析 ready 之前不准入上下文；越权引用拒绝 |
| GET /artifacts/{id}/download | 结果与文件授权 → 受控文件流 | 首批推荐平台代理下载，每次校验 ACL；对象存储 URL 不直接暴露 |

错误保持既有 code/correlationId；恢复分类为 `read_only / reconcile / correct_input / none`。401 登录失效、403/404 范围拒绝、409 修订或同键内容冲突、410 过期引用、422 条件不全、429 门限、503 依赖不可用。HTTP 超时是“结果待核查”，不自动映射业务 failed。

平台 SSE 使用自身 sequence，`Last-Event-ID` 或显式 cursor 二选一且冲突拒绝。浏览器断开只停止订阅，不取消 Provider。MCP 新版流断开可能含协议取消语义，因此两个流必须由服务端持久执行层隔离，不能直接把浏览器连接转接为 MCP 连接。

## Java Provider 边界与实现顺序

`provider-contract` 以 Java record / sealed 类型表达当前声明中的 12 个方法及判别式回执；异步实现可以返回 CompletionStage，但业务事务不等待网络。协议 SDK 类型和异常不跨越边界，错误统一包含 phase、acceptance、retryAdvice 和 correlationId。

| Provider | 实现要点 | 未满足时怎么办 |
| --- | --- | --- |
| 测试替身 | 持久可控接纳、重复事件、断线、过期、取消/完成竞争 | 只用于服务测试，不计真实适配通过 |
| 总控适配器 | 外部认证入口、命令幂等、原键查询、事件、审批桥和二进制产物导入 | 本轮未刷新其源码/部署，实施前记录精确版本；不支持即标 capability mismatch，不改对方库补事实 |
| ACP 直连 Runner | 先持久接纳日志再启动单 Agent；协商、认证、受限 cwd/MCP、实际模型观察、事件持久化、取消及子进程核查 | ACP 没有天然提供整个平台契约；宿主崩溃/进程存活未知先核查，不能重新 prompt 假装恢复 |

两个后端使用同一 conformance 目录。先验证总控路径，再在总控关闭时验证直连，不把实现顺序写成上线能力。缺少实际模型确认或必要硬额度能力的目标 Agent 不能进入要求这些能力的流程。

`purpose`、数据外发授权和工具授权分开：understanding/title/summary 使用空业务工具集，模型辅助调用仍记账；investigation 只装配本计划工具。允许未上报用量的策略必须有风险接受记录且不要求内部硬限额；该例外不适用于外部接纳未知、工具副作用未知或身份未知。

## 审批与原生权限桥

人批准的是固定 operationHash，Agent 请求的是特定 permission request。桥接须核对 Binding、原生 requestId/contextHash、映射修订、操作参数和期限，不能见到“允许”就回复任意请求。资源服务独立复查一次消费和当前权限。

长等待时保留计划与责任人，原生请求若失效，旧决定/映射转为失效并核查原动作；只有原拥有者证明未执行且支持安全检查点，才准备新请求。人工模式重新确认，自动策略模式重新匹配当前授权并生成新决定。没有安全恢复能力就交接，不通过新 prompt 重跑旧动作。

## 代码、MCP 与上下文的真实限制

GitLab 默认用只读仓库凭据；Token 经秘密接口保存，仅代码获取服务短时使用，不出现在 URL、命令行、Git remote、日志、上下文或产物。凭据方式必须与实例支持的 token 类型/username 配对，字段不硬编码一种 GitLab 认证规则。HTTPS 目标、重定向、DNS 结果及端口按允许列表检查；子模块、LFS、钩子和仓库脚本默认不自动执行，需显式资源策略。branch/tag 解析为 commit 后固定。

本地目录选择的是登记在执行节点的 root/directory 引用，不是浏览器本地任意路径。先检查真实路径、链接和挂载边界，再在只读复制/快照窗口生成内容清单；复制期间发现文件变化则重取或拒绝一致性结论。记录 dirty、capture time 和缺失项；没有 commit 时用内容哈希定位，不伪造 Git 版本。部署标记只是配置背景，需实际观测才能作为匹配证明。

MCP 采用核准的 endpoint 或制品模板，工具按 connection/tool/schemaHash 定位；参数除 schema 外还校验环境、数据集、路径和区间。外部资源链接再次授权导入，禁止任意 URL 抓取。stdio 与 HTTP 按各自认证模型处理；新旧 MCP 版本分支分别验证，不能混用会话或流恢复规则。[官方传输说明](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)

上下文包按当前 ACL 先过滤再排序，加入必要背景、直接材料、匹配证据和带来源摘要；凭据及私有路径排除。持续对话以平台消息为权威，原生 session 只作可复用执行状态。撤权后禁止重用可能含被撤材料的原生 session；核查原执行安全结束后，用新获权上下文开始后续节点，不能声称摘要能清除 Agent 私有状态。长期记忆默认关闭；后续启用时必须实现来源撤权、派生内容失效及删除回执。

差异计算由受控确定性工具执行，以 BigDecimal / 明确舍入和单位计算；输入哈希、时间区间、watermark、截断和计算规则随产物保存。模型解释不能替代全量核对证明。CSV 导出按文本防公式注入，保留原数值与单位；下载、管理员正文查看和接管包导出逐项鉴权。

## 合同冻结工作

现有 `.d.ts` 和占位 JSON 仍是设计材料。实施开始先定义规范化 JSON、UTF-8、时间/Decimal/null/数组排序规则及 SHA-256 输入域，生成至少一组可复算黄金样例，验证 Java 与 TypeScript 一致。将核心 SQL、API、事件和 Provider 的 schemaVersion 纳入兼容检查；不可用对象名或含 Token 的序列化结果作哈希。

OpenAPI 用于业务 REST，JSON Schema 用于 Provider/事件和 manifest。声明、Java DTO 与前端类型由同一合同生成/校验，避免同时维护三个独立真相。能力发现、幂等保留期、事件保留期及封闭证明必须真实验收后才能标 verified。
