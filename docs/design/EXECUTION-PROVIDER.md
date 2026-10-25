# ExecutionProvider 正式契约草案

状态：初始设计评审稿。字段见[类型声明](contracts/execution-provider.d.ts)，[示例](contracts/examples.json)均为合成数据。声明文件是设计材料，没有实现生产适配器。`contractVersion=initial-draft` 是机器兼容标识，不是产品版本。

## 1. 拥有者与信任边界

平台拥有问题、流程、审批责任、预算、文件 ACL 和用户验收；Provider 返回执行事实。总控拥有内部任务树及 attempts；直连宿主只管理单 Agent 进程、会话及调用。平台不得接管后端内部重试。

浏览器不直接调用执行端。所有方法均由平台服务认证调用；subjectContext 由服务端派生，不信任提示词或前端自报身份。凭据只保存引用。附件、事件、产物及下载逐项校验租户、用户、系统环境及可见范围，缓存不得绕过撤权。

## 2. 方法合同

所有方法返回 Result<T>：成功的判别式结果，或结构化错误。业务拒绝不能包装成成功；传输中断不等于明确拒绝。

| 方法 | 输入 → 输出 | 副作用与异常约束 |
| --- | --- | --- |
| inspectCapabilities | ConnectionRef → CapabilitySnapshot | 只读观察；带版本、配置哈希、观察/失效时间。advertised 不等于 verified。 |
| validateBinding | ExecutionSpec → ValidationReport | 不启动执行、不预占预算；报告能力、权限、预算执行等级及阻断项；过期观察不可用于启动。 |
| start | StartRequest → StartReceipt | 固定规格及稳定幂等键；只有 accepted 才返回已接收；响应丢失进入 acceptance_unknown。 |
| lookupSubmission | SubmissionRef → SubmissionLookup | 只查原提交：found / not_found / unknown；not_found 不是从未接收证明。 |
| closeSubmission | CloseSubmissionRequest → SubmissionClosure | 关闭原提交的后续接纳窗口：found / not_accepted_final / unknown；不得启动或取消已接收的执行。 |
| getSnapshot | BindingRef → ExecutionSnapshot | 只读权威快照：执行活跃、结果、副作用和权限请求；无法确认的字段保持 unknown。 |
| readEvents | EventReadRequest → EventPage | 有界、可重放、可去重；游标不授予权限；读取不触发执行。 |
| submitInput | InputRequest → InputReceipt | supplement / resume_checkpoint；accepted_pending 不等于 adopted；新轮次必须新 NodeRun。 |
| cancel | CancelRequest → CancelReceipt | 登记请求、停止中、停止确认及结果未知分开；不回滚已发生的动作。 |
| preparePermissionResume | ResumePreparation → ResumeReceipt | 仅在原拥有者安全检查点且动作未执行时取得新权限请求；不得顺带执行工具；无能力返回 UNSUPPORTED。 |
| respondPermission | PermissionResponse → PermissionReceipt | 绑定原生请求、业务批准、计划哈希、期限和命令键；外层批准不能绕过内层 deny。 |
| getArtifact | ArtifactRequest → ArtifactDescriptor | 限定用途引用、大小、哈希和导入状态；导入和完整性未确认不可下载；二进制不支持时阻断。 |

## 3. 固定规格、幂等与唯一拥有者

### 字段校验与兼容

ID/引用必须非空；revision、epoch、sequence 为非负安全整数（对象初始 revision/epoch 为 1），计数与字节数不可为负，limit=1–500。Sha256 为 64 位小写十六进制，Instant 必须显式 UTC；缺少已要求的字段报 VALIDATION_FAILED，未知合同版本报 UNSUPPORTED。可选但未知的事实用 null/unknown，不以空字符串或 0 替代。

startRequest 的租户、节点、Binding 与 Provider 必须跨 submission/spec 一致，specHash 必须匹配规格的规范摘要；接收回执回显同一 Binding 和 specHash。command.requestHash 是方法名及请求主体（排除 command 与认证/追踪 envelope）的规范摘要，独立于 specHash；示例中的重复字符哈希只是合成占位，不是实际计算结果。canStart=true 要求 blockers 为空且观察未过期；模型未确认或强制限制未验证的真实执行不得靠演示值通过。权限批准中的 operationHash 必须与当前请求和资源服务的固定计划相同。

CallContext.deadline 只限制本次等待；ExecutionSpec.deadline 是执行政策。任一超时都不能证明任务未接收或已停止。deadline 过后依旧核查原命令，不创建替代执行。未知外部枚举先保留诊断，不映射为成功；新增能力需合同协商。初始合同只允许原生 allow_once/reject_once，allow_always 不能扩展业务批准。

ExecutionSpec 冻结流程及节点、连接 revision、Agent、工作区授权、代码部署提交、材料哈希、模型请求、Skill 哈希、资源授权、预算预占、期限及输出合同。preparation 区分 inputRevision、冻结配置 configurationSnapshotRevision 与当前准入 admissionPolicyRevision，并固定接入快照、运行清单、上下文清单的引用与摘要，以及授权修订；清单按启动时的权限和能力准备，实际操作前仍复查当前授权。相关边界类型见 [service-boundaries.d.ts](contracts/service-boundaries.d.ts)。specHash 为规范 JSON（RFC 8785）UTF-8 的 SHA-256；数组顺序有意义，时间使用 RFC3339 UTC，金额为十进制字符串，缺省与 null 不混用。原型哈希均为标明的示例，不是安全令牌。

提交键作用域为 `(tenantId, providerId, nodeRunId, commandType, idempotencyKey)`；同键同 specHash 返回同一接收事实，同键异内容报 IDEMPOTENCY_CONFLICT。取消、补充和权限响应各有独立键。幂等记录覆盖运行、核查和审计保留期；过期键不可当新键重放。

平台原子保存 NodeRun、不可变 Binding、预算预占引用及待分派记录后才发送 start。适配器在接触后端前持久化接纳记录；后端无幂等能力时，外部调用结果未知就停止重发，不声称 exactly-once。

每个 NodeRun 最多一个未安全关闭的 Binding。默认连接变更只影响后续 NodeRun。租约过期、网络离线、task completed 但进程仍活跃，均不能释放拥有权。ownershipEpoch 只在旧拥有者终止并完成核查后推进；fencing 由接纳端和资源服务落实。

### 提交无响应

1. 原 Binding 进入 acceptance_unknown，保留键、哈希、预算，冻结新分派。
2. lookupSubmission 找到原执行则补录引用；只读快照/事件，不重新 start。
3. not_found 仍可能延迟接纳，继续核查或 closeSubmission。
4. 只有 not_accepted_final 与可验证 admissionFence 才证明旧请求此后也不能接纳；封闭与 start 必须在接纳端线性化。404、缓存未命中或本地无记录均不足。
5. 无最终排除证明则保持未知并交人工，不切后端重做。
6. 已接收的恢复使用原 Binding。允许新后端时创建新 NodeRun、预算与 Binding，并记录替代关系。

## 4. 快照、事件与游标

事件唯一键 `(providerId, providerRunId, providerEventId)`；sequence 是适配器持久化后的运行内递增序号，外部 sourceSequence 可未知。业务投影另有 revision。事件含 Binding、来源、时点、类型、payloadVersion、可见级别及判别式 payload。

readEvents 按序返回，limit=1–500；无新增时空页、原 cursor、hasMore=false。重复页不可重复推进状态、扣费或通知。跳号/乱序先缓冲并取快照；过期游标报 CURSOR_EXPIRED，取带 eventWatermark 的快照后续接。游标绑定 Binding 与权限投影，跨用户使用拒绝。

旧快照和迟到事件不可回退较新终态。权威快照冲突记录异常进入 reconciling。快照分别提供 permissionDeliveries（按原决定命令核查送达）和 operationFacts（按原动作核查是否发生）；看不到回执仍为 unknown，不能推断未执行。安全关闭同时要求终态结果、executionActive=false、effectStatus 已知及回执完整。未满足时保留占用和预算挂账。

## 5. 取消、补充与权限

cancel requested 仅为命令登记，stopping 表示收敛；confirmed 须有不活跃及最终副作用证明；否则 unknown。重复请求复用同一决定。取消和完成竞态允许“请求停止后正常完成”，按最终事实显示。

补充 accepted_pending 不可显示已纳入计算；adopted 返回实际采纳边界。无法恢复原会话时，新轮次明确“从授权上下文重建”，不声称迁移缓存。

权限响应前复核审批人资格、计划/参数/目标/环境哈希、Binding、requestId、mappingRevision、期限、前置状态、撤权和预算。决定落库、响应送达、动作执行是三个事实，见[审批恢复](APPROVAL-RECOVERY.md)。

## 6. 错误及允许恢复动作

| code | 含义 | 下一步 |
| --- | --- | --- |
| UNAUTHENTICATED / FORBIDDEN | 身份失效/越权 | 重新认证或联系责任人；不得借共享管理员绕过。 |
| VALIDATION_FAILED / CAPABILITY_MISMATCH / UNSUPPORTED | 合同或能力不满足 | 新配置/计划重校验，未知不可视为支持。 |
| REVISION_CONFLICT / IDEMPOTENCY_CONFLICT | 并发或同键内容冲突 | 读最新记录，禁止覆盖决定。 |
| PROVIDER_UNAVAILABLE / DEADLINE_EXCEEDED | 离线/超时 | 读操作退避；变更命令先核查。 |
| SUBMISSION_CLOSED | 原提交最终封闭 | 旧命令永久拒绝，新节点需安全关闭证据。 |
| CURSOR_EXPIRED / EVENT_GAP | 游标失效/事件缺口 | 取权威快照补齐，不据缺失判成功。 |
| PERMISSION_STALE / APPROVAL_EXPIRED / PRECONDITION_CHANGED | 请求/批准失效或漂移 | 冻结动作，新请求及新审批。 |
| BUDGET_BLOCKED / USAGE_UNKNOWN | 额度耗尽/未知 | 禁止新增计费，仅不计费核查与停止。 |
| ARTIFACT_UNAVAILABLE / INTEGRITY_MISMATCH | 文件不可用/哈希不符 | 不给下载票据，不自动重跑生成。 |
| RESULT_UNKNOWN | 接纳/送达/副作用未知 | 保留占用与挂账，只核查或人工接管。 |

错误必须含 correlationId、phase、acceptance、retryAdvice；not_accepted 必须带最终不接纳证明，否则为 unknown。建议仅 read_only / reconcile / correct_input / none，不提供会被误解为可重放 start 的通用 retryable=true。

## 7. 共同契约验收

两个真实 Provider 均须通过[共同用例目录](contracts/conformance-cases.json)。当前模拟只验证设计和交互，不计为 Provider 通过。待评审项包括保留期、检查点能力、身份映射及预算执行等级。
