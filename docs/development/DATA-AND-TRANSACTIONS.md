# 数据模型、事务与恢复

状态：待评审的实现合同。字段类型和约束是开发规划，尚无数据库或迁移文件。状态值沿用[五层转换目录](../design/STATE-MACHINES.md)，不另造一套控制台状态机。

## 公共约定

所有业务记录携带 tenant_id，首批一个组织也不省略；对象查询与关联带租户限定，关键外键用 `(tenant_id,id)` 防止跨租户串联。ID 为不含业务含义的 UUID；时间存 UTC `timestamptz`，业务时区单独记录；钱和精确数量用 `numeric` / Java BigDecimal，JSON 金额用十进制字符串。枚举、非负值、哈希格式和 revision 由数据库及应用双重校验。

可变聚合以 revision 作 CAS，标题另用 title_revision。不可变发布版本、执行清单、证据和决定保留历史；JSONB 适合版本化快照，不把成员权限、唯一绑定和账本余额全藏入 JSON。请求身份来自登录会话，不能信任传入 tenant / subject。

## 首批逻辑表

| 表组 | 关键字段 / 约束 | 拥有模块 |
| --- | --- | --- |
| subjects / memberships / grants / membership_requests | `(issuer,external_subject)` 唯一身份；组织成员唯一；授权修订和范围；禁止用邮箱作授权主键 | identity |
| config_objects / config_drafts / config_revisions / config_dependencies | 对象类型与 ID；draft_base_revision；发布版本不可变；依赖按 object/revision/hash 记录 | configuration |
| conversations / messages / title_candidates | system/environment 固定；input_revision；title_revision；`(tenant,conversation,client_message_id)` 唯一 | conversation |
| workflow_runs / node_runs | 固定流程修订、purpose、业务轮次、input_revision、state/revision；内部 Agent attempt 不建平台节点 | execution |
| preparations / execution_bindings | 计划及依赖哈希；node_run；固定 provider/connection/epoch/spec_hash；外部 run/session；closed_at 与关闭证明 | execution |
| commands / outbox / provider_events / platform_events | 幂等请求哈希及回执；发送状态；Provider 事件去重；平台会话内 sequence | execution / infrastructure |
| business_approvals / permission_mappings / operation_consumptions | 固定计划与责任人、决定 CAS；原生请求上下文及送达状态；每个 operation 单次消费 | approval / resource |
| quota_accounts / quota_reservations / usage_facts | 用户总账与模型分项；周期；计量单位；原始来源；真实、估算、未知及结算状态分开 | quota |
| attachments / source_items / context_manifests / summaries | 扫描解析状态、来源 ACL、哈希、片段范围、摘要依赖与保留期 | material |
| evidence / artifacts / result_revisions / verifications | 证据时点和版本、文件导入状态、结果修订、验收人及适用输入修订 | material / conversation |
| audit_entries / content_leases / deletion_jobs | 操作元数据、正文租约范围、派生内容删除回执；秘密值不落库 | audit / identity / material |

长期记忆表在真正启用对应能力时再迁移；首批只实现可追溯会话摘要。连接认证保存 secretRef / secretRevision，秘密值由独立秘密提供器保存，发布快照不含明文。

### 必须由数据库守住的约束

- 每个 NodeRun 最多一条 `closed_at IS NULL` 的 Binding；外层重试创建新 NodeRun，并检查旧节点安全关闭，不能绕过旧占用。
- `(tenant_id,subject_id,command_kind,idempotency_key)` 唯一，保存规范化 request_hash；同键不同内容返回冲突。调用者换登录会话不会产生第二条命令。
- `(tenant_id,binding_id,provider_event_id)` 唯一；同事件 ID 不同内容进入核查。会话内 `(tenant_id,conversation_id,sequence)` 唯一。
- 额度预占以 `(tenant_id,account_id,operation_id,reservation_kind)` 唯一；一次平台分派只有一个 turn 预占，一次真实内部调用按其调用 ID 独立计量。
- `operation_consumptions` 以 `(tenant_id,operation_id)` 唯一且绑定 operation_hash；不同参数不能消费原批准。
- 一个审批修订只能有一个最终决定；重复决定返回原回执，竞争决定用 CAS 冲突。结果确认按结果修订及验收命令去重。

API 层验证不能替代这些约束。所有 Repository 的对象方法必须带经过认证的 Scope，不提供忽略租户的便捷查询给业务代码。

## 准备、审批、排队与真正分派

准备阶段先建立 NodeRun 与 `prepared` Binding 身份，以便审批引用，但没有外部执行和活跃槽占用。Node 尚缺审批或资源条件时保持 blocked 并记录原因；`waiting_approval` 用于已经 active 的执行发出原生权限请求。准备期可有短期预算报价/暂留，它不是实际消耗，也不是活跃并发。

| 时点 | 本地事务 | 事务外动作 / 失败处理 |
| --- | --- | --- |
| 保存消息 | 消息去重、input_revision、审计、理解用途待办 | 返回已保存；未取得模型授权则只收集 |
| 创建计划 | 固定准备清单、Node、prepared Binding、审批及依赖；可选短期暂留 | 不偷偷读取尚未获权代码/数据库来“准备计划” |
| 保存决定 | 资格 / 范围 / 哈希 / 期限检查，审批 CAS、审计、待分派通知 | 已批准仍不是已发送；负责人不存在则留待处理 |
| 等待并发 | 保存待执行项及排队原因 | 不占活跃槽、不发模型；不把等待时间当 Agent 在执行 |
| 出队准入 `T_DISPATCH` | 锁当前授权/依赖、总账和分项；重查期限、停止标记、余量；Node→dispatching、保留 prepared Binding、预算/并发预占及 start outbox 原子提交 | 不持有数据库锁等待 Provider；过期/变更则取消此未发送项、释放仅本地暂留、重新准备 |
| 外部提交 | 发送器先 CAS 领取发送权，核对当前阻断标记，按 B01/T_SEND 保存接纳日志并使 Binding→submitting | 以原 command/key/hash 调 Provider；读到 accepted 才记已接纳，丢失回执进入核查 |
| 收回执/事件 | 事件去重、快照和状态投影、水位、通知同事务 | SSE 读取平台事件；事件重复不再次收费或交付 |
| 交付与验收 | 必需节点关闭、产物 ready、证据检查；结果版本→待验证；独立记录用户确认 | 部分产物失败保持交付未就绪或接管，不能声称问题已解决 |

锁使用统一顺序：授权与配置依赖对象按稳定 ID 排序 → 用户总账 → 模型分项账本 → 会话/节点/绑定 → 审批。相关用例均遵守顺序；出现死锁或 CAS 冲突只可重试未产生外部动作的本地事务。配置发布和准入锁定同一依赖版本，发布完成后出队不能仍使用旧批准。

并发预占发生在外部接纳之前，避免两个工作线程都看到空槽。prepared Binding 若已有 T_DISPATCH 槽预占也计入占用，不能只按 Binding 状态统计；没有预占的纯排队不计入。`submitting / acceptance_unknown / accepted / active / waiting_permission / reconciling / stopping` 的未关闭执行保留相应占用。用户等待输入或审批若仍保有外部进程，另记进程资源；不得仅凭 UI 显示等待就释放未决占用。

## 事务 outbox 不等于恰好执行一次

领取 outbox 可用短事务与 `FOR UPDATE SKIP LOCKED`，保存 worker、lease、attempt 和发送意图。执行网络请求在事务外。租约到期表示工作线程可恢复管理，不证明外部命令没执行；接管者先按原键 lookup，不能直接用新键再发。

| 崩溃窗口 | 恢复动作 |
| --- | --- |
| 消息/批准提交前 | 事务回滚；客户端用原键查询/重试本地命令 |
| outbox 提交后、尚未领取 | 再次准入后发送同一命令；已过期则本地关闭未发送项 |
| 已登记 sending，不知是否发送 | 接纳未知，查原提交；不要仅凭 outbox 未标 done 就重发 |
| Provider 已接纳、平台未落回执 | 原键 lookup 恢复相同执行引用 |
| lookup 返回 not_found | 保持未知；需要最终封闭证明，不能解释为从未执行 |
| 事件落库前崩溃 | 用 Provider 游标重读；事件唯一键去重 |
| 状态已投影、SSE 未送达 | 客户端从平台游标继续；不重新启动执行 |

即使适配器本地有接纳日志，外部总控不支持稳定幂等/查询/最终封闭时，也无法凭日志补出其丢失的执行事实。该能力缺口必须阻断所需流程或转人工核查。

停止和分派竞争以同一 Binding 锁协调。本地确定未领取的命令先抑制，由适配器接纳日志永久封闭原键，取得明确未接纳证明后按 B04 安全关闭；仅删除 outbox 不算关闭。一旦发送意图已持久化且结果不明，停止为 cancel_requested + reconciliation，不写“未执行”。审批被撤销时立即撤销未来资源许可；已经发出的动作保留实际事实。分布式撤权传播无法保证瞬时消除已发生效果，必须单列核查。

## 额度账本

用户总账 `(tenant,subject,period)` 与模型分项 `(tenant,subject,connection,agent,canonical_model,period)` 在同一事务预占。别名只改变展示；新增连接不能绕过用户总限额。全局默认与成员例外决定限额，环境/角色硬上限再取严；改限额不重置当期消耗。

平台轮次、分派并发、真实模型调用、Token 与金额各有计量单位和来源。平台 turn 在接纳时从 reserved 转 consumed；最终未接纳证明允许释放预占；结果未知保留。内部调用只有覆盖全部子调用的强制网关才能保证硬限额。Token/费用缺失不是零，也不能由上下文窗口占用换算成累计消费。

跨日按组织时区计算周期：平台 turn 归分派预占周期；跨日新增内部调用归其开始周期并重新准入；旧未知预占留在旧周期。用户活跃并发跨周期计算，午夜不能重置为零。改变时区只影响下一周期，避免同一天重复获得整份额度。

累计回执按可信 source_id 的高水位求增量，父汇总和子明细选择一种权威来源；重复不加账，修正关联原记录。非硬限额流程允许未知计量，必须存在有效风险接受记录；仍阻止接纳未知的另投、已知超限的新动作及硬限额要求不满足的调用。

## 文件、事件与恢复基础

附件路径：quarantined → scanned → parsed → ready / rejected；扫描、解压和解析在受限工作进程，限制字节、页数、行数和耗时。对象写入与数据库无全局事务：先写隔离对象并核哈希，再提交 ready 引用；孤立临时对象有清理任务，不删除已引用对象。产物同理。

平台事件 sequence 由会话计数行在投影事务内分配，避免自增序列先返回大序号后小序号晚提交导致客户端跳过事件。SSE 游标只是位置，不携带权限；每次读取及续流复查授权。保留期外返回授权快照与新游标，不能重放业务动作。

备份包含业务库、Runner 接纳日志、文件、密钥引用与密钥恢复方案。恢复时默认暂停新分派，核查所有未关闭 Binding、外部动作及账本，再解除门禁；不可用旧数据库快照证明外部没有执行。真实试点前做恢复演练并由负责人确定 RPO/RTO，当前没有可承诺的恢复指标。
