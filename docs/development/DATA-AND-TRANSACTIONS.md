# 数据模型、事务与恢复

状态：**MySQL 8.4 LTS / InnoDB 实现合同草案，数据库方向已获用户确认。** 隔离合成环境已建立平台及 Runner 数据库和迁移，字段、索引及恢复的逐项验证以[实施记录](../reviews/IMPLEMENTATION-PROGRESS-20261026.md)为准。状态沿用[五层转换目录](../design/STATE-MACHINES.md)，不另造状态机。本轮在 `a03e4e0` 上修订，不迁移任何现有业务系统或总控数据库。

## 公共约定与 MySQL 类型

所有业务记录携带 tenant_id，首批一个组织也不省略；对象查询与关联带租户限定。跨租户防护同时落实在认证 Scope、查询条件和复合外键；被引用的 `(tenant_id,id)` 必须有明确主键或唯一键，父子字段类型一致，不依赖非唯一引用键。

| 对象 | 当前约定 | 不允许的处理 |
| --- | --- | --- |
| 内部 ID | UUID；存 `BINARY(16)`，API 仍用规范 UUID 字符串；Java mapper 统一 16 字节网络顺序 | 混用 UUID_TO_BIN 的不同 swap 规则；把内部 UUID 规则强加到不透明 Provider ID |
| 事件、批准与执行时间 | `DATETIME(6)`，**由应用约定存 UTC**；领域使用 Instant，业务时区另记 IANA 标识 | 认为 DATETIME 自带时区或自动转换；依赖 JVM / 主机默认时区 |
| 金额与精确数量 | 明确量纲、上界、精度的 `DECIMAL(p,s)` / BigDecimal；JSON 金额为十进制字符串 | DOUBLE 结算、未规定舍入、用显示两位精度覆盖原始用量 |
| 轮次、序号、revision | 有符号 BIGINT 配合非负校验和溢出检查 | 依赖无符号超出 Java long 的值；将自增 ID 当提交水位 |
| 快照 | `JSON`；常用查询、关联、权限、余额及唯一性字段独立建列 | 用一个 JSON 保存全部授权和账本，绕开外键或唯一约束 |
| 哈希 | 算法/合同版本明确，SHA-256 二进制存储或经校验的规范十六进制；各表一致 | 对数据库导出的 JSON 字符串直接重新散列 |
| 幂等键、Provider 事件 ID、外部身份 | 有长度上限、按精确 UTF-8 字节比较的 VARBINARY 或等价经验证方案；必需键 NOT NULL | 大小写/重音不敏感比较、静默 trim、截断、前缀唯一索引 |
| 人类可读文本 | `utf8mb4`，显式选择排序规则；名称搜索与协议标识符等同性分开 | 把默认文本排序规则当作命令或账号标识符的精确等同性 |

UTC mapper 将 Instant 显式转 UTC LocalDateTime 写 DATETIME，读取后显式恢复为 Instant。数据库连接会话也固定 UTC（例如 `+00:00`），池中新建/复用连接均检查；Connector/J 的 connectionTimeZone 与 forceConnectionTimeZoneToSession 设置按选定驱动验证，不能只设置一个旧 serverTimezone 参数。平台生成的权威时间在创建/签署清单前统一到微秒，拒绝越界或不能无损接受的输入；外部原始事件需要更高精度时保留原文，不通过截断后的数据库值改变其哈希或重放内容。[M3]

隔离合成实现统一注入 UTC Clock，业务期限、租约和队列领取使用同一时间入口。轮次预占在 V5 持久冻结 `total_account_key`、`model_account_key` 与 UTC `period_key`；只能修改该次冻结账户对，跨日接受或终态仍结算旧周期。未知接纳继续保留旧预占和槽位，不因新周期开始而释放或搬账。旧范围无法证明时返回 QUOTA_SCOPE_UNKNOWN，不能猜测账户。并发首次建账使用稳定租户守卫与复合唯一键；当前守卫粒度较粗，未宣称高吞吐验证。

A04 将事件方法自身的计数更新与插入加入同一事务，嵌套调用参与原业务事务；SQL 插入失败不能留下已推进的水位。重复终态在已关闭 Binding 上也须先核对同一事件的原哈希，冲突不能因关闭而被静默忽略。独立事务/连接的确定性重叠验证与回滚/迟到提交证据见实施记录；不以线程同时启动或顺序重放冒充事务重叠。

可变聚合以 revision 作 CAS，标题另用 title_revision。不可变发布版本、执行清单、证据和决定保留历史。规范化 JSON、数值和时间格式由既有跨语言合同决定，入库前拒绝重复对象键、计算哈希并保存合同版本；需要字节级重放时保存规范化原文的字节引用及哈希。MySQL 会规范化 JSON，因此数据库 JSON 序列化顺序不是批准依据。[M4]

枚举、非负值和 revision 由应用及已启用的数据库约束共同校验；必需字段还须 NOT NULL，不能把 CHECK 当作非空约束。所有表显式 InnoDB，严格 SQL mode；禁止靠静默截断、零日期或关闭外键检查通过迁移和业务写入。长度、精度、排序规则和复合索引字节预算在 D0 冻结。

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

长期记忆表在真正启用对应能力时再迁移；首批只实现可追溯会话摘要。认证保存 secretRef / secretRevision，秘密值由独立秘密提供器保存，发布快照不含明文。大文件保存在 BlobStore，不把业务附件放入 JSON 列。

### 必须由数据库守住的约束

- 每个 NodeRun 最多一条 `closed_at IS NULL` 的 Binding；外层重试创建新 NodeRun，并检查旧节点安全关闭，不能绕过旧占用。
- `(tenant_id,subject_id,command_kind,idempotency_key)` 唯一，保存规范化 request_hash；同键不同内容返回冲突。换登录会话不会产生第二条命令。
- `(tenant_id,binding_id,provider_event_id)` 唯一；同事件 ID 不同内容进入核查。会话内 `(tenant_id,conversation_id,sequence)` 唯一。
- 预占以 `(tenant_id,account_id,operation_id,reservation_kind)` 唯一；一次分派只有一个 turn 预占，一次真实内部调用按其调用 ID 独立计量。
- `operation_consumptions` 以 `(tenant_id,operation_id)` 唯一且绑定 operation_hash；不同参数不能消费原批准。
- 一个审批修订只能有一个最终决定；重复决定返回原回执，竞争决定用 CAS 冲突。结果确认按结果修订及验收命令去重。

唯一索引的所有业务判别列必须非空；只有下面用于历史关闭记录的 open_guard 有意允许 NULL。不能用 `UNIQUE(tenant_id,node_run_id,closed_at)` 代替未关闭唯一性。

**MySQL 落地示意，供 D0 约束测试，不是可直接上线的迁移：**假定 execution_bindings 已有非空的 tenant_id / node_run_id、与 NodeRun 的租户复合外键，以及 DATETIME(6) 类型的 closed_at：

```sql
ALTER TABLE execution_bindings
  ADD COLUMN open_guard TINYINT
    GENERATED ALWAYS AS (
      CASE WHEN closed_at IS NULL THEN 1 ELSE NULL END
    ) STORED,
  ADD UNIQUE KEY uq_binding_open (tenant_id, node_run_id, open_guard);
```

未关闭行的 guard=1，第二条相同租户/节点的未关闭行必须失败；已关闭行的 guard=NULL，允许多条历史记录。MySQL 支持生成列索引，UNIQUE 允许多个 NULL。[M2] `closed_at` 只能由已核验的安全关闭事务设置；不能为释放索引或额度提前填写关闭时间，已关闭 Binding 不重新打开。

幂等冲突不得用 `REPLACE`、`INSERT IGNORE` 或无条件 upsert 覆盖 / 吞掉。显式识别目标唯一键冲突，读取原记录并比较原键和 request_hash，再返回同一回执或冲突；不能把任何 duplicate key 都当作幂等成功。CAS 更新同时递增 revision，检查影响行数为 1，Connector/J 影响行数语义纳入集成测试。

API 校验不能替代上述约束。Repository 必须接受经认证的 Scope，不提供忽略租户的便捷查询；也不接受用户输入的库名、表名或原始 SQL。

## 隔离、锁与短事务

平台和 Runner 的状态短事务采用 **READ COMMITTED** 作为当前实现基线，在 Spring 最外层用例显式声明或由专用连接池统一设置并验证。MySQL 默认是 REPEATABLE READ，不依赖环境默认值。[M1] 业务数据源的报表快照可能需要别的隔离策略，不能随平台状态库一起修改。

READ COMMITTED 不会自动防止两请求同时通过额度检查。准入必须锁住当前授权/配置守卫、总账、分项和执行对象，或使用等价的受约束条件更新；不得使用先普通 SELECT 再盲目 UPDATE 的模式。对于不存在的成员/周期账户行，先按统一顺序锁稳定父/守卫行，再创建带唯一键的账户；并发首次创建以唯一约束收敛并重新读取。查不到一行不是已经获得这段业务范围的锁。配置新增/撤销与准入也锁同一个稳定对象及修订，不能只锁可能消失的版本行。

锁顺序统一：授权与配置依赖对象按稳定 ID 排序 → 用户总账（跨周期活跃槽及周期预算按稳定键排序）→ 模型分项账本 → 会话/节点/绑定 → 审批。每条用例遵守相同顺序；账本、审计和 outbox 使用同一个 Spring 数据源事务。事务内不调用模型、网络、文件解析或外部数据库。资格、期限在取得必要锁后按当前时间复查，不把等待锁之前的检查结果当准入证明。

死锁通常回滚整个事务，锁等待超时默认只回滚失败语句。[M5] 应用对二者都将本地用例标记失败并回滚整个事务，之后仅对**未产生外部动作**的用例按原幂等键做有界退避重试；不在部分成功的事务里继续写账本。CAS 冲突按业务语义返回冲突或重新准备，不一律重试。连接在 COMMIT 时丢失，先查询原命令回执，不能推断提交失败。

## 准备、审批、排队与真正分派

准备阶段先建立 NodeRun 与 `prepared` Binding 身份，以便审批引用，但没有外部执行和活跃槽占用。Node 尚缺审批或资源条件时保持 blocked 并记录原因；`waiting_approval` 用于已经 active 的执行发出原生权限请求。准备期可有短期预算报价/暂留，它不是实际消耗，也不是活跃并发。

| 时点 | 本地事务 | 事务外动作 / 失败处理 |
| --- | --- | --- |
| 保存消息 | 消息去重、input_revision、审计、理解用途待办 | 返回已保存；未取得模型授权则只收集 |
| 创建计划 | 固定准备清单、Node、prepared Binding、审批及依赖；可选短期暂留 | 不偷偷读取尚未获权代码/数据库来“准备计划” |
| 保存决定 | 资格 / 范围 / 哈希 / 期限检查，审批 CAS、审计、待分派通知 | 已批准仍不是已发送；负责人不存在则留待处理 |
| 等待并发 | 保存待执行项及排队原因 | 不占活跃槽、不发模型；不把等待时间当 Agent 在执行 |
| 出队准入 `T_DISPATCH` | 按统一顺序加锁，重查授权、依赖、期限、停止标记和总分账余量；Node→dispatching、保留 prepared Binding、预算/并发预占及 start outbox 原子提交 | 不持锁等待 Provider；过期/变更则关闭此未发送项、释放仅本地暂留、重新准备 |
| 外部提交 | 发送器先 CAS 领取发送权，复查当前阻断及准入有效期，按 B01/T_SEND 保存接纳日志并使 Binding→submitting | 原 command/key/hash 调 Provider；accepted 才记已接纳，丢回执进入核查 |
| 收回执/事件 | 事件去重、快照和状态投影、水位、通知同事务 | SSE 读取平台事件；重复事件不再次收费或交付 |
| 交付与验收 | 必需节点关闭、产物 ready、证据检查；结果版本→待验证；独立记录用户确认 | 部分产物失败保持未就绪或接管，不声称问题已解决 |

配置发布和准入使用同一守卫 / 修订；发布完成后出队不能仍使用已失效批准。出队成功不等于永久获得启动资格；发送延迟、审批过期与 Provider 首次接纳的竞争纳入 D0/D1 合同验证。现有批准期限、ExecutionSpec.deadline 和接纳证明保持独立，不通过换库放宽。

并发预占发生在外部接纳之前，避免两个工作线程都看到空槽。prepared Binding 若已有 T_DISPATCH 槽预占也计入占用，不能只按 Binding 状态统计；纯排队不计入。`submitting / acceptance_unknown / accepted / active / waiting_permission / reconciling / stopping` 的未关闭执行保留相应占用。用户等待输入或审批若仍保有外部进程，另记进程资源；不得仅凭 UI 显示等待就释放未决占用。

## 事务 outbox 不等于恰好执行一次

领取 outbox 使用独立短事务和 `SELECT ... FOR UPDATE SKIP LOCKED`，保存 worker、lease、attempt 和发送意图，提交后网络调用。该语句只跳过可被其他消费者领取的队列项，不用于跳过锁住的额度、权限或审批行。[M6] 领取索引围绕状态、可领取时间和稳定 ID 设计，在目标数据量下检查执行计划与锁范围；索引候选不是已验证性能结论。跳过锁不保证严格 FIFO，最老待办等待时间需要监控。

当前 MySQL 合成检查复现原 `ix_outbox` 多范围查询经 filesort 锁住全部候选；实现改用 `FORCE INDEX(PRIMARY)` 的 tenant/id 有序扫描，两个真实消费者可分别领取，释放后可领取原先锁住项。发送意图提交前，以原 worker、原 lease 和 lease 仍有效做条件更新；失去发送权时回滚整段账户预占、槽位、票据及事件。该选择验证正确性，会扫描租户历史，尚不作为吞吐优化结论。COMMIT 故障覆盖本任务专用 JDBC 连接在发出前关闭、真实提交后丢回执；不冒充 TLS/代理层报文故障验收。

队列领取和准入分开事务，避免先锁队列再倒序锁额度造成锁序反转。租约到期表示可恢复管理，不证明外部命令没执行；接管者按原键 lookup，不换键重发。启用 binlog 时使用 ROW，并核验持久化设置，不能以降低落盘可靠性换取测试性能。

| 崩溃窗口 | 恢复动作 |
| --- | --- |
| 消息/批准提交前 | 事务回滚；客户端按原键查询/重试本地命令 |
| outbox 提交后、尚未领取 | 再次准入后发送同一命令；已过期则本地关闭未发送项 |
| 已登记 sending，不知是否发送 | 接纳未知，查原提交；不凭 outbox 未标 done 重发 |
| Provider 已接纳、平台未落回执 | 原键 lookup 恢复同一执行引用 |
| lookup 返回 not_found | 保持未知；需要最终封闭证明，不解释为从未执行 |
| 事件落库前崩溃 | 用 Provider 游标重读；事件唯一键去重 |
| 状态已投影、SSE 未送达 | 客户端从平台游标继续，不重新启动执行 |

即使适配器有本地接纳日志，外部总控不支持稳定幂等/查询/最终封闭时，也无法凭日志补出丢失的执行事实。能力缺口阻断所需流程或转人工。Runner 接纳日志的 start/closeSubmission 竞争、唯一启动拥有者和租约 fencing 同样必须经过真实 MySQL 与进程故障验证，不能只验证平台库。

停止和分派竞争以同一 Binding 锁协调。本地确定未领取的命令先抑制，由适配器接纳日志永久封闭原键，取得明确未接纳证明后按 B04 安全关闭；仅删除 outbox 不算关闭。一旦发送意图已持久化且结果不明，停止为 cancel_requested + reconciliation，不写“未执行”。审批撤销立即阻断未来资源许可；已发出动作保留实际事实。分布式撤权不能瞬时消除已发生效果，必须单列核查。

## 额度账本

用户总账 `(tenant,subject,period)` 与模型分项 `(tenant,subject,connection,agent,canonical_model,period)` 在同一 InnoDB 事务预占。别名只改变展示；新增连接不能绕过用户总限额。全局默认与成员例外决定限额，环境/角色硬上限取严；改限额不重置当期消耗。

所有预占记录与余额更新、并发槽、命令/outbox 必须一起成功或回滚，不在不同数据源或独立提交中分别扣账。首次创建周期账户纳入缺行竞争规则。限额下降可以低于既有消耗/预占，此时禁止新增，不删除旧消耗、不把未知预占归零；因此不能建立会导致合法降额失败的 `consumed + reserved <= current_limit` 全局 CHECK。

平台轮次、分派并发、真实模型调用、Token 与金额各有单位和来源。turn 在接纳时从 reserved 转 consumed；最终未接纳证明允许释放；结果未知保留。内部调用只有覆盖全部子调用的强制网关才能保证硬限额。Token/费用缺失不是零，不能由上下文窗口占用换算。

跨日按组织时区计算周期：turn 归分派预占周期；跨日新增内部调用归其开始周期并重新准入；旧未知预占留在旧周期。用户活跃并发跨周期计算，午夜不能重置。时区调整只影响下一周期，避免同一天重新获得整份额度。

累计回执按可信 source_id 高水位求增量，父汇总与子明细选择一种权威来源；重复不加账，修正关联原记录。非硬限额允许未知计量须有有效风险接受记录；仍阻止接纳未知的另投、已知超限的新动作及硬限额要求不满足的调用。

## 文件、事件与恢复基础

附件路径：quarantined → scanned → parsed → ready / rejected。扫描、解压和解析在受限进程，限制字节、页数、行数和耗时。文件与数据库无全局事务：先写隔离对象并核哈希，再提交 ready 引用；清理孤立临时对象但不删除已引用文件。产物同理。

平台事件 sequence 由会话计数行在投影事务内分配，先锁该会话计数再分配、写事件和投影、提交；不使用 AUTO_INCREMENT 作为会话提交水位，避免大序号先提交后跳过迟到的小序号。事务回滚、竞争投影、断线续流均测试。SSE 游标不携带权限；读取和续流复查授权。保留期外返回授权快照及新游标，不重放业务动作。

平台库与 Runner 日志独立 database / 账号 / Flyway 历史；应用不跨库读写或使用跨库事务。备份覆盖两库、文件、秘密引用及密钥恢复方案。恢复后默认暂停分派，核对未关闭 Binding、外部动作与账本，再解除门禁；旧数据库快照不能证明外部从未执行。实际试点前做恢复演练并确定 RPO/RTO，当前无承诺指标。

## Flyway 与 DDL 恢复

Connector/J 与 Flyway core/mysql 按[技术选型](TECH-STACK.md)共同冻结。平台和 Runner 使用各自迁移目录、目标 database 和历史表。迁移账号持有必要 DDL 权限；运行账号没有 CREATE/ALTER/DROP，也不持有对方库权限。

MySQL 原子 DDL 不等于可以将多条 DDL/DML 置于一个事务回滚，DDL 可能隐式提交。[M7] 迁移采用小步、向前兼容、可检查前后条件的脚本：先加兼容结构，再升级应用/回填，最后另行评审收缩；账本 DML 不混在依赖 DDL 回滚的业务事务中。

迁移中断后先核对实际结构、数据和 Flyway 历史，暂停服务准入，由授权维护者决定补完或恢复。`repair` 不会撤销已完成 DDL，不得盲目 repair、clean、改旧 checksum 或关闭外键检查。新建索引/生成列须记录锁表与耗时风险，不承诺所有 ALTER 都在线无阻塞。正式部署前做空库、增量升级、中断恢复和跨库权限拒绝测试。

## 官方依据

以下仅支持引擎语义，不表示本项目已运行上述 SQL：

- M1：[事务设置与隔离级别](https://dev.mysql.com/doc/refman/8.4/en/set-transaction.html)。
- M2：[生成列索引与 UNIQUE NULL](https://dev.mysql.com/doc/refman/8.4/en/create-index.html)。
- M3：[Connector/J 时间映射](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html)。
- M4：[JSON 存储及规范化](https://dev.mysql.com/doc/refman/8.4/en/json.html)。
- M5：[InnoDB 错误与回滚行为](https://dev.mysql.com/doc/refman/8.4/en/innodb-error-handling.html)。
- M6：[锁定读与 SKIP LOCKED](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)。
- M7：[原子 DDL 的边界](https://dev.mysql.com/doc/refman/8.4/en/atomic-ddl.html)。

D0/D1 的 MySQL 专项验收见[开发计划](IMPLEMENTATION-PLAN.md)。初稿阶段只修订文档；后续已获授权建立隔离迁移并开展合成验证，当前事实以实施记录为准。
